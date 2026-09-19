package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.LoadBalanceBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.NetworkSelector
import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.utils.VloadNetworkController
import libcore.Libcore
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null
    override var upstreamNetwork: Network? = null

    // vload dual-network load balancing: non-null only while a
    // TYPE_LOAD_BALANCE profile is the running session.
    var vloadNetworkController: VloadNetworkController? = null
        private set

    // A slot stays unavailable until cleanup of its previous network finishes.
    // Generations prevent queued cleanup from re-enabling an obsolete network.
    private val slotGeneration = AtomicLongArray(2)
    private val slotResetLocks = arrayOf(Mutex(), Mutex())

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        setupVloadNetworksIfNeeded()
        super.startProcesses() // launch proxy instance
    }

    private fun setupVloadNetworksIfNeeded() {
        slotGeneration.incrementAndGet(0)
        slotGeneration.incrementAndGet(1)
        vloadNetworkController?.stopAll()
        vloadNetworkController = null

        val profile = data.proxy?.profile ?: return
        if (profile.type != ProxyEntity.TYPE_LOAD_BALANCE) return
        val lb = profile.loadBalanceBean ?: return

        fun selectorFor(networkKind: Int, subscriptionId: Int) = if (networkKind == LoadBalanceBean.NETWORK_SIM) {
            NetworkSelector.Sim(subscriptionId)
        } else {
            NetworkSelector.WiFi
        }

        val controller = VloadNetworkController { slot, network ->
            val proxy = data.proxy
            val delivered = proxy?.takeIf { it.isInitialized() }
            Logs.i("vload: slot $slot network=${network?.toString() ?: "lost"} deliveredToBox=${delivered != null}")
            val generation = slotGeneration.incrementAndGet(slot)
            // Invalidate dials immediately; never advertise the new network
            // before asynchronous cleanup has finished closing the old pool.
            delivered?.box?.updateNetworkAvailability(slot, false)
            updateUnderlyingNetwork()
            if (delivered != null) {
                runOnDefaultDispatcher {
                    slotResetLocks[slot].withLock {
                        if (data.proxy !== delivered || slotGeneration.get(slot) != generation) return@withLock
                        val closed = delivered.box.resetSlotConnections(slot)
                        withContext(Dispatchers.Main.immediate) {
                            if (data.proxy === delivered && slotGeneration.get(slot) == generation) {
                                if (closed < 0) {
                                    Logs.w("vload: slot $slot cleanup unavailable; keeping slot disabled")
                                } else {
                                    delivered.box.updateNetworkAvailability(slot, network != null)
                                    Logs.i("vload: slot $slot cleanup complete, available=${network != null}, closed=$closed")
                                }
                            }
                        }
                    }
                }
            }
        }
        vloadNetworkController = controller

        controller.start(0, selectorFor(lb.slotANetworkKind, lb.slotASubscriptionId))
        controller.start(1, selectorFor(lb.slotBNetworkKind, lb.slotBSubscriptionId))
    }

    /**
     * Binds fd to the network currently held for [slot] (0 or 1), for a vload
     * outbound member whose protect_path pointed at that slot. If that slot's
     * network is unavailable or binding fails, report failure to the dialer
     * so the weighted group can try the other member. A generic protect()
     * fallback would silently use the default radio under the wrong slot.
     */
    fun protectSlot(fd: Int, slot: Int): Boolean {
        // Binding an existing file descriptor is unavailable on Android 5.
        // Fail the member rather than crash or silently use another network.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val network = vloadNetworkController?.networkFor(slot) ?: return false
        if (!protect(fd)) return false
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        return try {
            network.bindSocket(pfd.fileDescriptor)
            // Do not acknowledge a socket bound to a network that was
            // replaced while Android performed the bind.
            vloadNetworkController?.networkFor(slot) == network
        } catch (e: Exception) {
            Logs.w(e)
            false
        } finally {
            // Go's protect_server owns fd's lifecycle and closes it right
            // after this call returns; detach (not close) so it isn't
            // double-closed here.
            pfd.detachFd()
        }
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun killProcesses() {
        conn?.close()
        conn = null
        slotGeneration.incrementAndGet(0)
        slotGeneration.incrementAndGet(1)
        vloadNetworkController?.stopAll()
        vloadNetworkController = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        val ipv6Mode = DataStore.ipv6Mode

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("2000::", 3)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass
        val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
        val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Allow Matsuri itself using VPN.
                remove(packageName)
                if (!bypass) add(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort))
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    // DefaultNetworkListener fires on every capabilities refresh of the
    // system default network, not just real changes - and under Load
    // Balance it fires far more often than usual, since holding two extra
    // concurrent per-transport NetworkRequests (VloadNetworkController)
    // makes Android's connectivity service churn capability callbacks more
    // aggressively system-wide. Every one of those calls updateUnderlyingNetwork,
    // and calling setUnderlyingNetworks unconditionally each time - even
    // with an identical network set - makes sing-box's core treat it as a
    // real interface change and reset every outbound's mux dialer
    // (InterfaceUpdated -> multiplexDialer.Reset()), killing every open
    // multiplexed stream at once. That's what was silently torching Load
    // Balance sessions every ~30s: not a real network change, just this
    // redundant re-apply. Only call through to setUnderlyingNetworks when
    // the target set has actually changed from what's already applied.
    private var lastUnderlyingNetworks: List<Network>? = null

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val vload = vloadNetworkController
            val vloadNetworks = vload?.let {
                listOfNotNull(it.networkFor(0), it.networkFor(1))
            } ?: emptyList()
            val targetNetworks = vloadNetworks.ifEmpty {
                listOfNotNull(SagerNet.underlyingNetwork)
            }
            if (targetNetworks.isEmpty()) return
            if (builder == null && targetNetworks == lastUnderlyingNetworks) return
            lastUnderlyingNetworks = targetNetworks
            val networks = targetNetworks.toTypedArray()
            builder?.setUnderlyingNetworks(networks) ?: setUnderlyingNetworks(networks)
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
