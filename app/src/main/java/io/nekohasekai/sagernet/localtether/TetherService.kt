package io.nekohasekai.sagernet.localtether

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Implements ITetherService — hosted by whatever process Shizuku launches this
 * class in (via Shizuku.bindUserService, referencing this class by
 * ComponentName). Ported from vhost/shizzi's TetherService: the actual
 * TUN/test-network/tethering mechanism is identical regardless of how the
 * hosting process was privileged.
 */
class TetherService : ITetherService.Stub {

    private val context: Context

    private val shellContext: Context by lazy { asShellContext(context) }
    private val runner: ProbeRunner by lazy { ProbeRunner(shellContext) }
    private val session: TetherSession by lazy { TetherSession(shellContext) }
    private val compatibility: CompatibilityCheck by lazy { CompatibilityCheck(shellContext) }

    @Suppress("unused")
    constructor() : this(acquireSystemContext())

    constructor(context: Context) {
        this.context = context
        liveInstance = this
    }

    override fun getContractVersion(): Int = CONTRACT_VERSION

    override fun start(logging: Boolean): String {
        SessionLog.setEnabled(logging)
        return runCatching { session.start() }
            .getOrElse { failure -> sessionError("start", failure) }
    }

    override fun stop(): String {
        runCatching { runner.teardown() }
            .onFailure { failure -> Log.w(TAG, "stop: probe teardown ${failure.message}") }

        return runCatching { session.stop() }
            .getOrElse { failure -> sessionError("stop", failure) }
    }

    override fun getStatus(): String =
        runCatching { session.status() }
            .getOrElse { failure -> sessionError("getStatus", failure) }

    override fun setLogging(enabled: Boolean) {
        SessionLog.setEnabled(enabled)
    }

    override fun checkCompatibility(): String =
        runCatching { compatibility.run().toJson() }
            .getOrElse { failure -> errorReport("checkCompatibility", failure) }

    override fun shutdown() {
        Thread {
            try {
                Thread.sleep(SHUTDOWN_DELAY_MS)
            } catch (interrupted: InterruptedException) {
            }
            Runtime.getRuntime().exit(0)
        }.start()
    }

    override fun clearLog() {
        runCatching { SessionLog.clear() }
            .onFailure { failure -> Log.w(TAG, "clearLog: ${failure.message}") }
    }

    override fun runProbes(attemptTethering: Boolean, availabilityTimeoutMs: Int): String =
        publish(
            runCatching { runner.run(attemptTethering, availabilityTimeoutMs) }
                .getOrElse { failure -> errorReport("runProbes", failure) },
        )

    private fun publish(report: String): String {
        runCatching { java.io.File(REPORT_PATH).writeText(report) }
            .onFailure { failure -> Log.w(TAG, "publish: ${failure.message}") }

        Log.i(TAG, "report: $report")
        return report
    }

    private fun sessionError(operation: String, failure: Throwable): String {
        Log.e(TAG, "$operation failed", failure)
        return org.json.JSONObject().apply {
            put("state", SessionState.ERROR.name)
            put("detail", "$operation: ${failure.javaClass.simpleName}: ${failure.message}")
        }.toString()
    }

    private fun errorReport(operation: String, failure: Throwable): String {
        Log.e(TAG, "$operation failed", failure)
        return org.json.JSONObject().apply {
            put("verdict", "ERROR")
            put("operation", operation)
            put("error", "${failure.javaClass.name}: ${failure.message}")
            put("stackTrace", failure.stackTraceToString().take(STACK_TRACE_CHARS))
        }.toString(2)
    }

    companion object {

        // Bump manually when ITetherService's contract changes, so a stale
        // privileged helper left running across an app update is detected
        // instead of silently answering with an old implementation.
        const val CONTRACT_VERSION = 1

        @Suppress("unused")
        @JvmStatic
        private var liveInstance: TetherService? = null

        private const val TAG = "LocalTetherService"
        private const val STACK_TRACE_CHARS = 4000
        private const val SHUTDOWN_DELAY_MS = 200L

        const val REPORT_PATH = "/data/local/tmp/vload-tether-probe-report.json"

        private const val SHELL_PACKAGE = "com.android.shell"

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun acquireSystemContext(): Context {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain").invoke(null)
            return activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
        }

        private fun asShellContext(context: Context): Context {
            val rebased = runCatching { context.createPackageContext(SHELL_PACKAGE, 0) }
                .getOrElse { failure ->
                    throw IllegalStateException(
                        "asShellContext: could not rebase context (package=" +
                            "${context.packageName}) onto $SHELL_PACKAGE",
                        failure,
                    )
                }
            forceOpPackageName(rebased)
            return rebased
        }

        private fun forceOpPackageName(context: Context) {
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        rebaseAttributionSource(context)

                    else -> rebaseOpPackageName(context)
                }
            }.getOrElse { failure ->
                throw IllegalStateException(
                    "forceOpPackageName: could not attribute context to $SHELL_PACKAGE",
                    failure,
                )
            }
        }

        private fun rebaseAttributionSource(context: Context) {
            val field = context.javaClass.getDeclaredField("mAttributionSource")
            field.isAccessible = true

            val current = field.get(context) ?: error("mAttributionSource was null")
            val rebased = current.javaClass
                .getMethod("withPackageName", String::class.java)
                .invoke(current, SHELL_PACKAGE)

            field.set(context, rebased)
        }

        private fun rebaseOpPackageName(context: Context) {
            val field = context.javaClass.getDeclaredField("mOpPackageName")
            field.isAccessible = true
            field.set(context, SHELL_PACKAGE)
        }
    }
}
