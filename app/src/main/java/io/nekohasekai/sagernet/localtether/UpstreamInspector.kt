package io.nekohasekai.sagernet.localtether

import android.os.Build
import java.io.BufferedReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class UpstreamObservation(
    val rawOutput: String,
    val interfaceNames: List<String>,
    val didTimeout: Boolean,
) {

    fun liveInterfaceNames(owned: String): List<String> {
        val (live, absent) = interfaceNames.partition { it == owned || interfaceExists(it) }
        if (absent.isNotEmpty()) {
            SessionLog.warn("ignoring upstream that no longer exists: $absent")
        }
        return live
    }
}

private fun interfaceExists(name: String): Boolean =
    runCatching { java.io.File("/sys/class/net/$name").exists() }.getOrDefault(true)

class UpstreamInspector(
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    private val processFactory: (String) -> Process = { ProcessBuilder("dumpsys", it).start() },
) {

    fun observe(): UpstreamObservation = run("tethering")

    fun observeWifi(): UpstreamObservation = run("wifi")

    private fun run(service: String): UpstreamObservation {
        val process = processFactory(service)
        val drainPool = Executors.newFixedThreadPool(2)

        val stdout = drainPool.submit<String> { process.inputStream.drain() }
        val stderr = drainPool.submit<String> { process.errorStream.drain() }

        try {
            val didExit = waitForProcess(process)
            if (!didExit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly()
                else process.destroy()
            }
            val output = collectOutput(stdout, stderr)
            return UpstreamObservation(output, parseUpstreamInterfaces(output), !didExit)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            // Cancellation must not leave dumpsys or its output-drain threads
            // alive after the tethering probe has stopped.
            process.destroy()
            stdout.cancel(true)
            stderr.cancel(true)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            drainPool.shutdownNow()
        }
    }

    private fun waitForProcess(process: Process): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(deadlineMs, TimeUnit.MILLISECONDS)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        do {
            try { process.exitValue(); return true } catch (_: IllegalThreadStateException) { }
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(10)
        } while (true)
    }

    private fun collectOutput(
        stdout: java.util.concurrent.Future<String>,
        stderr: java.util.concurrent.Future<String>,
    ): String {
        fun read(future: java.util.concurrent.Future<String>): String = try {
            future.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) { "" }
        val out = read(stdout)
        val err = read(stderr)
        return if (err.isBlank()) out else "$out\n[stderr]\n$err"
    }

    private fun parseUpstreamInterfaces(output: String): List<String> {
        val interesting = output.lineSequence()
            .map(String::trim)
            .filter { line -> UPSTREAM_HINTS.any { hint -> line.startsWith(hint, ignoreCase = true) } }

        return interesting
            .flatMap { line -> INTERFACE_PATTERN.findAll(line).map { it.value } }
            .distinct()
            .toList()
    }

    private fun java.io.InputStream.drain(): String =
        bufferedReader().use(BufferedReader::readText)

    companion object {
        const val DEFAULT_DEADLINE_MS = 3_000L

        private val UPSTREAM_HINTS = listOf(
            "current upstream interface(s):",
            "current upstream:",
            "selected upstream:",
            "upstream network:",
            "mCurrentUpstream",
        )

        private val INTERFACE_PATTERN =
            Regex("""\b(testtun\d+|wlan\d+|rmnet[a-z_]*\d*|eth\d+|ap\d+|swlan\d+)\b""")
    }
}
