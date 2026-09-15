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
        // How long to wait for a slot's requested network to show up before
        // treating it as genuinely unavailable rather than just slow to
        // register - see the comment in start() for why this exists at all.
        // Long enough that a normal boot/VPN-start registration delay never
        // trips it, short enough that a slot with no real signal (no SIM
        // service, airplane mode on one radio, etc.) stops being hedged to
        // well before a user has started actively browsing.
        private const val SLOT_UNAVAILABLE_TIMEOUT_MS = 8000L
    }

    private val connectivity get() = SagerNet.connectivity
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callbacks = arrayOfNulls<ConnectivityManager.NetworkCallback>(2)
    private val networks = arrayOfNulls<Network>(2)
    private val unavailableTimeouts = arrayOfNulls<Runnable>(2)

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
        } catch (e: Exception) {
            Logs.w(e)
            callbacks[slot] = null
            return
        }

        // requestNetwork(request, callback) - the no-timeout overload - never
        // calls onUnavailable(): that callback only exists on the separate
        // timeout overload, and using it would have the OS auto-unregister
        // this request the moment it fires, so a SIM that later regains
        // signal would never be seen again. If the requested network simply
        // never existed to begin with (no signal, not registered on this
        // subscription right now) neither onAvailable, onLost, nor anything
        // else ever fires on this callback - onLost only fires for a network
        // that WAS delivered and then went away. Weighted.UpdateAvailability
        // is only ever called from onSlotChanged, so a slot whose network
        // was never available even once stayed marked available (the
        // picker's default) forever, with no signal to the contrary.
        //
        // Confirmed live: with only Wi-Fi actually reachable, the SIM slot's
        // callback never fired at all, so the weighted group kept picking
        // and hedging to it as if it were a real second path - both slots
        // ended up resolving to the same Wi-Fi route underneath, so hedging
        // sent duplicate near-simultaneous connections (confirmed for
        // accounts.google.com) to origins that read that pattern as
        // suspicious/automated traffic and started demanding a CAPTCHA.
        //
        // This local timeout runs independently of the real registration
        // above: if onAvailable hasn't fired within it, mark the slot down
        // for now. It does not touch or invalidate the underlying request,
        // so a genuine onAvailable arriving later - the SIM regaining signal
        // - still fires normally and immediately restores the slot, exactly
        // like any other network-recovery case this controller handles.
        val timeoutRunnable = Runnable {
            if (networks[slot] == null) {
                onSlotChanged(slot, null)
            }
        }
        unavailableTimeouts[slot] = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, SLOT_UNAVAILABLE_TIMEOUT_MS)
    }

    fun stop(slot: Int) {
        unavailableTimeouts[slot]?.let { mainHandler.removeCallbacks(it) }
        unavailableTimeouts[slot] = null
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
