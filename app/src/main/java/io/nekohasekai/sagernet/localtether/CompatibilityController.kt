package io.nekohasekai.sagernet.localtether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompatibilityController(
    private val client: TetherClient,
    private val scope: CoroutineScope,
) {

    private val localState = MutableStateFlow<CompatibilityState>(CompatibilityState.Idle)
    val state: StateFlow<CompatibilityState> = localState.asStateFlow()

    fun reset() {
        localState.value = CompatibilityState.Idle
    }

    fun check() {
        if (localState.value is CompatibilityState.Checking) return
        localState.value = CompatibilityState.Checking

        scope.launch {
            localState.value = runCatching { client.checkCompatibility() }
                .fold(
                    onSuccess = { results -> CompatibilityState.Complete(results) },
                    onFailure = { failure ->
                        CompatibilityState.Failed(
                            "${failure.javaClass.simpleName}: ${failure.message}",
                        )
                    },
                )
        }
    }
}
