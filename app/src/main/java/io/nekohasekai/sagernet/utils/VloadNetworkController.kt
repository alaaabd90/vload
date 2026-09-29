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
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicIntegerArray

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
    private val connectivity get() = SagerNet.connectivity
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callbacks = arrayOfNulls<ConnectivityManager.NetworkCallback>(2)
    private val networks = AtomicReferenceArray<Network?>(2)
    private val subscriptions = AtomicIntegerArray(intArrayOf(-1, -1))
    private val following = mutableMapOf<Int, Set<Int>>()
    private var activeSimMonitor: ActiveDataSimMonitor? = null

    fun subscriptionFor(slot: Int): Int = subscriptions.get(slot)

    @Synchronized
    fun followDataSim(slot: Int, configuredCards: Set<Int>) {
        following[slot] = configuredCards
        stop(slot)
        onSlotChanged(slot, null)
        if (activeSimMonitor == null) {
            activeSimMonitor = ActiveDataSimMonitor { activeId ->
                synchronized(this) {
                    following.forEach { (index, cards) ->
                        val desired = activeId.takeIf { it in cards } ?: -1
                        if (subscriptions.get(index) != desired || (desired >= 0 && callbacks[index] == null)) {
                            if (desired >= 0) start(index, NetworkSelector.Sim(desired))
                            else { stop(index); onSlotChanged(index, null) }
                        }
                    }
                }
            }.also { it.start() }
        }
    }

    fun networkFor(slot: Int): Network? = if (slot in 0..1) networks.get(slot) else null

    @Synchronized
    fun start(slot: Int, selector: NetworkSelector) {
        require(slot == 0 || slot == 1) { "slot must be 0 or 1" }
        stop(slot)
        subscriptions.set(slot, (selector as? NetworkSelector.Sim)?.subscriptionId ?: -1)
        // The box already exists at this point. Do not allow a slot to be
        // picked until Android actually supplies its requested network.
        onSlotChanged(slot, null)

        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun deliver(network: Network, available: Boolean) {
                mainHandler.post {
                    synchronized(this@VloadNetworkController) {
                        // A callback queued before stop/restart belongs to
                        // the old request and must not revive its network.
                        if (callbacks[slot] !== this) return@synchronized
                        val previous = networks.get(slot)
                        if (available) {
                            if (previous == network) return@synchronized
                            networks.set(slot, network)
                            onSlotChanged(slot, network)
                        } else if (previous == network) {
                            networks.set(slot, null)
                            onSlotChanged(slot, null)
                        }
                    }
                }
            }

            override fun onAvailable(network: Network) = deliver(network, true)

            override fun onLost(network: Network) = deliver(network, false)

            // Capability refreshes are not network recovery. Forwarding each
            // signal-strength update clears the core's circuit breaker even
            // when this path is still failing to carry traffic.
        }
        callbacks[slot] = callback
        try {
            connectivity.requestNetwork(buildRequest(selector), callback)
        } catch (e: Exception) {
            callbacks[slot] = null
            Logs.w(e)
        }
    }

    @Synchronized
    fun stop(slot: Int) {
        val callback = callbacks[slot]
        callbacks[slot] = null
        networks.set(slot, null)
        subscriptions.set(slot, -1)
        callback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    @Synchronized
    fun stopAll() {
        following.clear()
        activeSimMonitor?.stop()
        activeSimMonitor = null
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
