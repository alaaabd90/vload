package io.nekohasekai.sagernet.bg

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import java.net.NetworkInterface

/**
 * "Share VPN over local network" no longer runs its own relay - the mixed
 * (SOCKS5) inbound sing-box already runs for local traffic is bound directly
 * to 0.0.0.0 when sharing is on (see ConfigBuilder.kt), same as how Hiddify
 * exposes its equivalent LAN-sharing toggle. This object now only provides
 * the LAN-address lookup used to show/QR-encode the right address in the UI.
 */
object LocalShareServer {

    /**
     * All LAN-reachable IPs for display in the UI (label to address),
     * excluding VPN/loopback/cellular ranges. Hotspot interface names
     * vary wildly by OEM (seen: wlan2 on this Honor device, ap0/softap0
     * elsewhere) so rather than maintain a growing name whitelist, every
     * non-excluded interface is a candidate; whichever one matches the
     * WiFi transport network's own address (queried via
     * ConnectivityManager, not by interface name) is labelled "Wi-Fi",
     * and any other candidate is the local hotspot AP by elimination.
     */
    fun getLocalAddresses(): List<Pair<String, String>> {
        val excludedNames = setOf("tun0", "tun1", "lo", "dummy0")
        val excludedPrefixes = listOf("tun", "vpn", "dummy", "rmnet", "v4-", "clat")

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return emptyList()

        val wifiIp = runCatching {
            val cm = SagerNet.connectivity
            cm.allNetworks.asSequence()
                .filter {
                    // Require actual internet capability, not just TRANSPORT_WIFI -
                    // some Android versions also expose a local-only/tethering
                    // network over the same transport, which would otherwise get
                    // misidentified as the Wi-Fi STA connection here.
                    val nc = cm.getNetworkCapabilities(it)
                    nc?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                .mapNotNull { cm.getLinkProperties(it) }
                .flatMap { it.linkAddresses.asSequence() }
                .map { it.address }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress
        }.getOrNull()

        fun candidateAddress(iface: NetworkInterface): String? {
            if (!iface.isUp) return null
            if (iface.name in excludedNames) return null
            if (excludedPrefixes.any { iface.name.startsWith(it) }) return null
            for (addr in iface.inetAddresses) {
                val host = addr.hostAddress ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                if (host.contains(':')) continue // IPv4 only for display simplicity
                // Exclude the VPN TUN's benchmark range (198.18.0.0/15) - would show
                // the wrong "IP" for LAN clients to connect to.
                if (host.startsWith("198.18.") || host.startsWith("198.19.")) continue
                return host
            }
            return null
        }

        val result = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for (iface in interfaces) {
            val host = candidateAddress(iface) ?: continue
            if (!seen.add(host)) continue
            val label = if (host == wifiIp) "Wi-Fi" else "Hotspot"
            result += label to host
        }
        return result
    }
}
