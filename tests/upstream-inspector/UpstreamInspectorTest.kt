import android.os.Build
import io.nekohasekai.sagernet.localtether.UpstreamInspector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private class TrackedInput(text: String) : ByteArrayInputStream(text.toByteArray()) {
    var closed = false
    override fun close() { closed = true; super.close() }
}

private class ProbeProcess(private val completed: Boolean) : Process() {
    val stdout = TrackedInput("Current upstream interface(s): wlan0\n")
    val stderr = TrackedInput("")
    val stdin = ByteArrayOutputStream()
    val waiting = CountDownLatch(1)
    var destroyed = false
    override fun getInputStream() = stdout
    override fun getErrorStream() = stderr
    override fun getOutputStream() = stdin
    override fun exitValue(): Int {
        waiting.countDown()
        if (!completed && !destroyed) throw IllegalThreadStateException()
        return 0
    }
    override fun waitFor(): Int { waiting.countDown(); Thread.sleep(60_000); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waiting.countDown()
        if (!completed) Thread.sleep(unit.toMillis(timeout))
        return completed
    }
    override fun destroy() { destroyed = true }
    override fun destroyForcibly(): Process { destroy(); return this }
}

fun main() {
    for (sdk in listOf(21, 26)) {
        Build.VERSION.SDK_INT = sdk
        val complete = ProbeProcess(true)
        val result = UpstreamInspector(40) { complete }.observe()
        check(!result.didTimeout && result.interfaceNames == listOf("wlan0"))
        check(complete.destroyed && complete.stdout.closed && complete.stderr.closed)

        val stalled = ProbeProcess(false)
        check(UpstreamInspector(30) { stalled }.observe().didTimeout)
        check(stalled.destroyed && stalled.stdout.closed && stalled.stderr.closed)

        val canceled = ProbeProcess(false)
        val interrupted = AtomicBoolean()
        val worker = Thread {
            try { UpstreamInspector(60_000) { canceled }.observe(); error("cancellation ignored") }
            catch (_: InterruptedException) { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        worker.start()
        check(canceled.waiting.await(1, TimeUnit.SECONDS))
        worker.interrupt(); worker.join(1_000)
        check(!worker.isAlive && interrupted.get())
        check(canceled.destroyed && canceled.stdout.closed && canceled.stderr.closed)
    }
    println("PASS: success, timeout and cancellation cleanup on API 21 and API 26 paths")
}
