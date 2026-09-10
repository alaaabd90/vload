package io.nekohasekai.sagernet.localtether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin suspend wrapper around an already-bound ITetherService binder.
 * Binding/rebinding lifecycle (Shizuku permission, bindUserService) lives in
 * LocalShizukuTether; this class only ever talks to whatever binder it was
 * constructed with.
 */
class TetherClient(private val service: ITetherService) {

    private fun verifyContract() {
        val remote = service.contractVersion
        check(remote == TetherService.CONTRACT_VERSION) {
            "privileged helper is stale: app is build ${TetherService.CONTRACT_VERSION}, " +
                "helper reports $remote — toggle the setting off and on to reload it"
        }
    }

    suspend fun checkCompatibility(): List<CapabilityResult> = withContext(Dispatchers.IO) {
        verifyContract()
        parseCapabilities(service.checkCompatibility())
    }

    suspend fun start(logging: Boolean): String = withContext(Dispatchers.IO) {
        verifyContract()
        service.start(logging)
    }

    suspend fun stop(): String = withContext(Dispatchers.IO) {
        service.stop()
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        service.status
    }

    fun setLogging(enabled: Boolean) {
        runCatching { service.setLogging(enabled) }
    }

    suspend fun clearLog(): String? = withContext(Dispatchers.IO) {
        runCatching { service.clearLog() }.fold(
            onSuccess = { null },
            onFailure = { failure -> failure.message ?: "could not reach the privileged helper" },
        )
    }

    fun shutdown() {
        runCatching { service.shutdown() }
    }
}
