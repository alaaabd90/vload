package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs

/** Which physical network a vload slot should be bound to. */
sealed class NetworkSelector {
    object WiFi : NetworkSelector()
    data class Sim(val subscriptionId: Int) : NetworkSelector()
}

/**
 * Acquires and tracks the two physical networks (slot 0 / slot 1) a vload
 * load-balance session is bound to. Unlike [DefaultNetworkListener] (a
 * process-lifetime singleton tracking "the current default network"), this
 * is owned by the running vload session and torn down with it, since it can
 * hold two concurrent [NetworkRequest]s that intentionally do NOT track the
 * default network.
 *
 * Per-SIM targeting (`NetworkSelector.Sim`) requires API 30+ ([TelephonyNetworkSpecifier]
 * didn't exist before Android 11); on older API levels a SIM slot request
 * falls back to "any cellular network" since a specific subscription can't
 * be targeted.
 */
class VloadNetworkController(
    private val onSlotChanged: (slot: Int, network: Network?) -> Unit,
) {
    companion object {
        // requestNetwork(request, callback) - the no-timeout overload - never
        // calls onUnavailable(): that only exists on the separate timeout
        // overload, which auto-unregisters the request once it fires (a SIM
        // regaining signal later would then never be seen again). A network
        // that was simply never available to begin with also never fires
        // onLost, since that only fires for a network that WAS delivered and
        // then went away - so a slot with no real network stayed marked
        // available (the picker's default) indefinitely, with nothing to
        // contradict it. That silent-forever-available state is what this
        // poll loop exists to break, by checking real connectivity state
        // repeatedly instead of waiting on a callback that will never come.
        //
        // Rather than declare a slot down after one fixed guessed delay, the
        // deadline is derived from how long the OTHER slot actually took to
        // register on this exact device/run (fastestObservedAvailableMs) -
        // a device/radio-state with slower real registration (cold boot,
        // older modem firmware) gets a correspondingly longer grace period
        // instead of the same blind number as a fast one, and a device that
        // has already proven it registers quickly won't wait needlessly long
        // for a genuinely absent network. MIN/MAX bound this at both ends:
        // MIN keeps a very fast observation from creating an unrealistically
        // short deadline for the other slot, MAX bounds the wait before any
        // observation exists yet (this session's very first slot to start).
        private const val UNAVAILABLE_POLL_INTERVAL_MS = 1500L
        private const val UNAVAILABLE_MIN_WAIT_MS = 3000L
        private const val UNAVAILABLE_MAX_WAIT_MS = 20000L
        private const val UNAVAILABLE_LATENCY_MULTIPLIER = 4

        // Shared across both slots (and across Load Balance sessions in this
        // process) since it reflects how fast network registration tends to
        // go on this device/radio right now, not anything specific to one
        // slot. Deliberately kept as "fastest seen" rather than an average:
        // a slot that is genuinely down should be judged against how fast a
        // real, working registration CAN go on this device, not dragged out
        // by also averaging in past slow-but-genuine registrations.
        @Volatile
        private var fastestObservedAvailableMs: Long? = null
    }

    private val connectivity get() = SagerNet.connectivity
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callbacks = arrayOfNulls<ConnectivityManager.NetworkCallback>(2)
    private val networks = arrayOfNulls<Network>(2)
    private val unavailablePollers = arrayOfNulls<Runnable>(2)

    fun networkFor(slot: Int): Network? = networks.getOrNull(slot)

    fun start(slot: Int, selector: NetworkSelector) {
        require(slot == 0 || slot == 1) { "slot must be 0 or 1" }
        stop(slot)

        val request = buildRequest(selector)
        val requestStartMs = System.currentTimeMillis()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val latency = System.currentTimeMillis() - requestStartMs
                fastestObservedAvailableMs = fastestObservedAvailableMs
                    ?.coerceAtMost(latency) ?: latency
                unavailablePollers[slot]?.let { mainHandler.removeCallbacks(it) }
                unavailablePollers[slot] = null
                networks[slot] = network
                mainHandler.post { onSlotChanged(slot, network) }
            }

            // A cell-tower handoff doesn't always hand out a new Network for
            // a narrowly-scoped per-transport/per-SIM request like this one
            // the way it reliably does for a broad default-network request -
            // the OS can preserve the same Network across it and only ever
            // signal the change via updated capabilities. Routing this
            // through onSlotChanged too, instead of only onAvailable, is the
            // difference between catching that class of handoff and missing
            // it outright; the caller is responsible for not treating every
            // capabilities refresh on an already-known network as a reason
            // to reset connections.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mainHandler.post { onSlotChanged(slot, network) }
            }

            override fun onLost(network: Network) {
                if (networks[slot] == network) {
                    networks[slot] = null
                    mainHandler.post { onSlotChanged(slot, null) }
                }
            }
        }
        callbacks[slot] = callback
        try {
            connectivity.requestNetwork(request, callback)
            schedulePollForUnavailable(slot, selector, requestStartMs)
        } catch (e: Exception) {
            Logs.w(e)
            callbacks[slot] = null
        }
    }

    /**
     * Polls real connectivity state at a short, fixed interval rather than
     * waiting on a single one-shot delay - see the class-level comment on
     * [fastestObservedAvailableMs] for why a poll loop exists at all instead
     * of just a delayed check. Each tick both extends the deadline (if the
     * other slot has since registered even faster than before) and, once
     * the deadline passes, re-confirms against [ConnectivityManager]'s own
     * current network list before declaring the slot down - guarding against
     * a callback dispatch that's merely running slightly behind the network
     * actually having appeared.
     */
    private fun schedulePollForUnavailable(
        slot: Int,
        selector: NetworkSelector,
        requestStartMs: Long,
    ) {
        val poller = object : Runnable {
            override fun run() {
                if (networks[slot] != null) return // resolved via onAvailable already
                val deadlineMs = (fastestObservedAvailableMs
                    ?.let { it * UNAVAILABLE_LATENCY_MULTIPLIER } ?: UNAVAILABLE_MAX_WAIT_MS)
                    .coerceIn(UNAVAILABLE_MIN_WAIT_MS, UNAVAILABLE_MAX_WAIT_MS)
                val elapsed = System.currentTimeMillis() - requestStartMs
                if (elapsed < deadlineMs || matchesSelector(selector)) {
                    mainHandler.postDelayed(this, UNAVAILABLE_POLL_INTERVAL_MS)
                    return
                }
                unavailablePollers[slot] = null
                if (networks[slot] == null) onSlotChanged(slot, null)
            }
        }
        unavailablePollers[slot] = poller
        mainHandler.postDelayed(poller, UNAVAILABLE_POLL_INTERVAL_MS)
    }

    /** Whether a network matching [selector] is already known to the OS right now. */
    private fun matchesSelector(selector: NetworkSelector): Boolean {
        return try {
            connectivity.allNetworks.any { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@any false
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@any false
                when (selector) {
                    is NetworkSelector.WiFi -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    is NetworkSelector.Sim -> caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
    }

    fun stop(slot: Int) {
        unavailablePollers[slot]?.let { mainHandler.removeCallbacks(it) }
        unavailablePollers[slot] = null
        callbacks[slot]?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        callbacks[slot] = null
        networks[slot] = null
    }

    fun stopAll() {
        stop(0)
        stop(1)
    }

    private fun buildRequest(selector: NetworkSelector): NetworkRequest {
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        when (selector) {
            is NetworkSelector.WiFi -> {
                builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            }

            is NetworkSelector.Sim -> {
                builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setNetworkSpecifier(
                        TelephonyNetworkSpecifier.Builder()
                            .setSubscriptionId(selector.subscriptionId)
                            .build()
                    )
                } else {
                    Logs.w(Exception("per-SIM network targeting requires Android 11+; requesting any cellular network instead"))
                }
            }
        }
        return builder.build()
    }
}
