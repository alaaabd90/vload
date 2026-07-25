package io.nekohasekai.sagernet.bg

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Exposes the local mixed (SOCKS5) inbound vload already runs for its own
 * traffic on all network interfaces, so devices on the same hotspot/WiFi can
 * use this phone as a proxy. Pure TCP relay - no SOCKS5 parsing needed
 * because the upstream already speaks SOCKS5.
 */
class LocalShareServer(private val upstreamPort: Int) {

    companion object {
        const val LISTEN_PORT = 12334
        private const val MAX_CONNECTIONS = 20

        @Volatile
        private var shared: LocalShareServer? = null

        @Synchronized
        fun startShared(upstreamPort: Int) {
            if (shared != null) return
            shared = LocalShareServer(upstreamPort).also { it.start() }
        }

        @Synchronized
        fun stopShared() {
            shared?.stop()
            shared = null
        }

        fun isSharedRunning() = shared != null

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

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val activeConnections = AtomicInteger(0)

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", LISTEN_PORT), 50)
                }
                serverSocket = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) Logs.w(e)
                        break
                    }
                    Logs.i("vload-share: accepted ${client.remoteSocketAddress} (active=${activeConnections.get()})")
                    if (activeConnections.get() >= MAX_CONNECTIONS) {
                        Logs.w("vload-share: rejecting ${client.remoteSocketAddress}, at MAX_CONNECTIONS")
                        runCatching { client.close() }
                        continue
                    }
                    handleClient(client)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }, "LocalShareServer").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        val remote = client.remoteSocketAddress
        val startedAt = System.currentTimeMillis()
        Thread({
            val c2u = AtomicLong(0)
            val u2c = AtomicLong(0)
            try {
                client.tcpNoDelay = true
                client.soTimeout = 30_000
                val upstream = Socket()
                upstream.tcpNoDelay = true
                upstream.soTimeout = 30_000
                upstream.connect(InetSocketAddress("127.0.0.1", upstreamPort), 5_000)

                val t1 = Thread({ relay(client, upstream, c2u) }, "LocalShareServer-c2u").apply { isDaemon = true }
                val t2 = Thread({ relay(upstream, client, u2c) }, "LocalShareServer-u2c").apply { isDaemon = true }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
            } catch (e: Exception) {
                Logs.w("vload-share: session $remote failed: ${e.readableMessage}")
            } finally {
                val elapsed = System.currentTimeMillis() - startedAt
                Logs.i("vload-share: closed $remote after ${elapsed}ms, sent=${c2u.get()}B received=${u2c.get()}B")
                runCatching { client.close() }
                activeConnections.decrementAndGet()
            }
        }, "LocalShareServer-client").apply { isDaemon = true }.start()
    }

    private fun relay(from: Socket, to: Socket, counter: AtomicLong) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                counter.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
            // normal on connection close/timeout
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.close() }
        }
    }
}
