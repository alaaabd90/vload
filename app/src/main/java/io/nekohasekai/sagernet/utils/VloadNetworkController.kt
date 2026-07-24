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
    private val connectivity get() = SagerNet.connectivity
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callbacks = arrayOfNulls<ConnectivityManager.NetworkCallback>(2)
    private val networks = arrayOfNulls<Network>(2)

    fun networkFor(slot: Int): Network? = networks.getOrNull(slot)

    fun start(slot: Int, selector: NetworkSelector) {
        require(slot == 0 || slot == 1) { "slot must be 0 or 1" }
        stop(slot)

        val request = buildRequest(selector)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks[slot] = network
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
        } catch (e: Exception) {
            Logs.w(e)
            callbacks[slot] = null
        }
    }

    fun stop(slot: Int) {
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
