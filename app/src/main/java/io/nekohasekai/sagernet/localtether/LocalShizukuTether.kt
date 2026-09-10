package io.nekohasekai.sagernet.localtether

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import rikka.shizuku.Shizuku

/**
 * Wi-Fi tethering over a Shizuku-privileged test network, transparently
 * through whatever VPN vload already has running — no per-device proxy
 * configuration needed on hotspot clients. Ported from vhost/shizzi's
 * TetherSession mechanism (see TetherService.kt and friends in this
 * package); the privilege bootstrap here is Shizuku's own official client
 * library instead of vhost's from-scratch ADB pairing daemon.
 *
 * Mirrors LocalShareServer's startShared()/stopShared() shape, but the whole
 * bind → compatibility-check → start sequence is asynchronous (Shizuku's
 * permission grant and bindUserService are both callback-driven), so state
 * is exposed as a StateFlow instead of a simple boolean.
 *
 * startShared()/stopShared() are both just launch { opMutex.withLock { ... } }
 * around fully-suspending implementations, so repeated/rapid toggling can
 * never interleave a start with an in-progress stop (or vice versa) — a
 * second call simply waits for the mutex instead of racing. Earlier, both
 * were a mix of synchronous early-outs and independently-launched
 * coroutines: a start called shortly after a stop could see a stale (not
 * yet Idle) state and silently no-op, or a stop's late cleanup could null
 * out a client/connection a newer start had already bound. That's what
 * "works sometimes, not others, especially with repeated toggling" was.
 */
object LocalShizukuTether {

    sealed interface State {
        data object Idle : State
        data object RequestingPermission : State
        data object Binding : State
        data object CheckingCompatibility : State
        data object Starting : State
        data object Running : State
        data class Incompatible(val results: List<CapabilityResult>) : State
        data class Error(val message: String) : State
    }

    private const val PERMISSION_REQUEST_CODE = 41829
    private const val PERMISSION_TIMEOUT_MS = 60_000L
    private const val BIND_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opMutex = Mutex()

    private val localState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = localState.asStateFlow()

    private var client: TetherClient? = null
    private var connection: ServiceConnection? = null

    @Volatile
    private var pendingPermissionResult: CompletableDeferred<Boolean>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(SagerNet.application, TetherService::class.java))
            .daemon(false)
            .processNameSuffix("local_tether")
            .debuggable(false)
            .version(TetherService.CONTRACT_VERSION)
    }

    // Registered once for the process lifetime; only ever completes whichever
    // deferred is currently pending, so it's safe even if a permission
    // request from a previous, already-abandoned attempt somehow still
    // resolves late.
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            pendingPermissionResult?.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }

    init {
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
    }

    /** True once Shizuku's own daemon is reachable — i.e. the separate app is installed and running. */
    fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isRunning(): Boolean = localState.value == State.Running

    /**
     * Starts the whole sequence: request permission if needed, bind the
     * privileged TetherService, check compatibility, then start tethering.
     * Safe to call when Shizuku isn't installed/running — logs and reports
     * State.Error rather than throwing; the Settings UI is responsible for
     * telling the user to install/enable Shizuku before ever calling this.
     */
    fun startShared() {
        scope.launch {
            opMutex.withLock { doStart() }
        }
    }

    /** Stops the tethering session and unbinds from Shizuku. Safe to call even if never started. */
    fun stopShared() {
        scope.launch {
            opMutex.withLock { doStop() }
        }
    }

    private suspend fun doStart() {
        if (localState.value == State.Running) return

        if (!isShizukuAvailable()) {
            SessionLog.warn("shizuku: not available (not installed, or not running)")
            localState.value = State.Error("Shizuku is not installed or not running")
            return
        }

        if (!hasPermission()) {
            SessionLog.info("shizuku: requesting permission")
            localState.value = State.RequestingPermission

            val deferred = CompletableDeferred<Boolean>()
            pendingPermissionResult = deferred
            val sent = runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure { failure ->
                    SessionLog.error("shizuku: requestPermission failed: ${failure.message}")
                    localState.value = State.Error("Could not request Shizuku permission: ${failure.message}")
                }
                .isSuccess
            if (!sent) {
                pendingPermissionResult = null
                return
            }

            val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() } ?: false
            pendingPermissionResult = null

            if (!granted) {
                SessionLog.warn("shizuku: permission denied (or timed out waiting for a response)")
                localState.value = State.Error("Shizuku permission was denied")
                return
            }
            SessionLog.info("shizuku: permission granted")
        }

        localState.value = State.Binding
        SessionLog.info("shizuku: binding TetherService")

        val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { bindSuspend() }
        if (binder == null) {
            if (localState.value == State.Binding) {
                SessionLog.error("shizuku: bindUserService timed out after ${BIND_TIMEOUT_MS}ms")
                localState.value = State.Error("Timed out binding the privileged helper")
            }
            return
        }

        client = TetherClient(ITetherService.Stub.asInterface(binder))

        localState.value = State.CheckingCompatibility
        val results = runCatching { client!!.checkCompatibility() }
            .getOrElse { failure ->
                SessionLog.error("compatibility check failed: ${failure.message}")
                localState.value = State.Error("Compatibility check failed: ${failure.message}")
                return
            }

        if (!results.all { it.isPresent }) {
            SessionLog.warn("incompatible: $results")
            localState.value = State.Incompatible(results)
            return
        }

        localState.value = State.Starting
        val statusJson = runCatching { client!!.start(true) }
            .getOrElse { failure ->
                SessionLog.error("start failed: ${failure.message}")
                localState.value = State.Error("Start failed: ${failure.message}")
                return
            }
        SessionLog.info("start result: $statusJson")
        localState.value = State.Running
    }

    /**
     * Binds TetherService and suspends until onServiceConnected/
     * onServiceDisconnected fires (or the caller's withTimeoutOrNull gives
     * up), instead of the old fire-and-continue callback style. That's what
     * lets the whole start sequence run as one straight-line suspend
     * function inside doStart(), with nothing left to race against a
     * concurrent stop.
     */
    private suspend fun bindSuspend(): IBinder? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) {
                    SessionLog.error("shizuku: onServiceConnected with a dead/null binder")
                    if (cont.isActive) cont.resume(null)
                    return
                }
                SessionLog.info("shizuku: bound")
                if (cont.isActive) cont.resume(binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                SessionLog.warn("shizuku: service disconnected")
                client = null
                // Only a real async death report if we'd already resolved the
                // bind (i.e. this fires later, independent of any in-flight
                // doStart/doStop) - if the continuation is still active this
                // is just the bind itself failing, and resuming(null) below
                // already reports that through doStart's own timeout/error path.
                if (cont.isActive) {
                    cont.resume(null)
                } else if (localState.value == State.Running) {
                    localState.value = State.Error("Privileged helper disconnected")
                }
            }
        }
        connection = conn

        runCatching { Shizuku.bindUserService(userServiceArgs, conn) }
            .onFailure { failure ->
                SessionLog.error("shizuku: bindUserService failed: ${failure.message}")
                localState.value = State.Error("Could not bind privileged helper: ${failure.message}")
                if (cont.isActive) cont.resume(null)
            }
    }

    private suspend fun doStop() {
        // Any in-flight permission request from a start this stop is
        // interrupting can't grant anything useful anymore.
        pendingPermissionResult?.complete(false)
        pendingPermissionResult = null

        val bound = client
        val conn = connection
        client = null
        connection = null

        if (bound != null) {
            runCatching { bound.stop() }
                .onFailure { failure -> SessionLog.warn("stop failed: ${failure.message}") }
        }

        if (conn != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
                .onFailure { failure -> SessionLog.warn("unbindUserService failed: ${failure.message}") }
        }

        localState.value = State.Idle
        SessionLog.info("stopped")
    }
}
