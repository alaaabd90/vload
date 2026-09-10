package io.nekohasekai.sagernet.localtether

sealed interface CompatibilityState {

    data object Idle : CompatibilityState

    data object Checking : CompatibilityState

    data class Complete(val results: List<CapabilityResult>) : CompatibilityState

    data class Failed(val problem: String) : CompatibilityState
}

val CompatibilityState.isCompatible: Boolean
    get() = this is CompatibilityState.Complete && results.all { it.isPresent }

val CompatibilityState.reportedResults: List<CapabilityResult>
    get() = when (this) {
        is CompatibilityState.Complete -> results
        else -> emptyList()
    }
