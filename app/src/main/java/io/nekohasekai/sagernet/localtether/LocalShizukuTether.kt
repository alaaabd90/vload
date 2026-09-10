package io.nekohasekai.sagernet.localtether

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val localState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = localState.asStateFlow()

    private var client: TetherClient? = null
    private var connection: ServiceConnection? = null
    private var wantsToRun = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(SagerNet.application, TetherService::class.java))
            .daemon(false)
            .processNameSuffix("local_tether")
            .debuggable(false)
            .version(TetherService.CONTRACT_VERSION)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                SessionLog.info("shizuku: permission granted")
                bindAndStart()
            } else {
                SessionLog.warn("shizuku: permission denied")
                localState.value = State.Error("Shizuku permission was denied")
                wantsToRun = false
            }
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
        if (localState.value != State.Idle && localState.value !is State.Error) return
        wantsToRun = true

        if (!isShizukuAvailable()) {
            SessionLog.warn("shizuku: not available (not installed, or not running)")
            localState.value = State.Error("Shizuku is not installed or not running")
            return
        }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        if (hasPermission()) {
            bindAndStart()
        } else {
            SessionLog.info("shizuku: requesting permission")
            localState.value = State.RequestingPermission
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure { failure ->
                    SessionLog.error("shizuku: requestPermission failed: ${failure.message}")
                    localState.value = State.Error("Could not request Shizuku permission: ${failure.message}")
                }
        }
    }

    private fun bindAndStart() {
        localState.value = State.Binding
        SessionLog.info("shizuku: binding TetherService")

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) {
                    SessionLog.error("shizuku: onServiceConnected with a dead/null binder")
                    localState.value = State.Error("Shizuku handed back a dead binder")
                    return
                }
                SessionLog.info("shizuku: bound")
                client = TetherClient(ITetherService.Stub.asInterface(binder))
                checkThenStart()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                SessionLog.warn("shizuku: service disconnected")
                client = null
                if (wantsToRun) localState.value = State.Error("Privileged helper disconnected")
            }
        }
        connection = conn

        runCatching { Shizuku.bindUserService(userServiceArgs, conn) }
            .onFailure { failure ->
                SessionLog.error("shizuku: bindUserService failed: ${failure.message}")
                localState.value = State.Error("Could not bind privileged helper: ${failure.message}")
            }
    }

    private fun checkThenStart() {
        val bound = client ?: return
        localState.value = State.CheckingCompatibility

        scope.launch {
            val results = runCatching { bound.checkCompatibility() }
                .getOrElse { failure ->
                    SessionLog.error("compatibility check failed: ${failure.message}")
                    localState.value = State.Error("Compatibility check failed: ${failure.message}")
                    return@launch
                }

            if (!results.all { it.isPresent }) {
                SessionLog.warn("incompatible: $results")
                localState.value = State.Incompatible(results)
                return@launch
            }

            localState.value = State.Starting
            val statusJson = runCatching { bound.start(true) }
                .getOrElse { failure ->
                    SessionLog.error("start failed: ${failure.message}")
                    localState.value = State.Error("Start failed: ${failure.message}")
                    return@launch
                }
            SessionLog.info("start result: $statusJson")
            localState.value = State.Running
        }
    }

    /** Stops the tethering session and unbinds from Shizuku. Safe to call even if never started. */
    fun stopShared() {
        wantsToRun = false
        val bound = client
        val conn = connection

        scope.launch {
            if (bound != null) {
                runCatching { bound.stop() }
                    .onFailure { failure -> SessionLog.warn("stop failed: ${failure.message}") }
            }

            if (conn != null) {
                runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
                    .onFailure { failure -> SessionLog.warn("unbindUserService failed: ${failure.message}") }
            }

            client = null
            connection = null
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
            localState.value = State.Idle
            SessionLog.info("stopped")
        }
    }
}
