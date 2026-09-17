package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.openconnect.OpenConnectBean
import moe.matsuri.nb4a.proxy.openconnect.buildSingBoxEndpointOpenConnectBean
import moe.matsuri.nb4a.proxy.openvpn.OpenVPNBean
import moe.matsuri.nb4a.proxy.openvpn.buildSingBoxEndpointOpenVPNBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.proxy.snell.buildSingBoxOutboundSnellBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

// vload: Load Balance's DNS-only outbound - same two slots as TAG_PROXY, but
// mode="priority" so DNS answers come from one consistent network (failing
// over, not hedging/splitting) while bulk traffic through TAG_PROXY still
// combines both. See buildLoadBalance and the dns-remote server below.
const val TAG_DNS_PROXY = "dns-proxy"

// vload: same reasoning as TAG_DNS_PROXY, applied to QUIC (HTTP/3) traffic.
// A QUIC connection is bound to one path for its whole life - the server
// only ever expects packets from the source IP (behind Connection ID) it
// negotiated the handshake with. TAG_PROXY's adaptive weighted picker can
// legitimately hand consecutive flows (or a NAT-expired same flow) to
// different slots, i.e. different exit IPs/networks; a QUIC server or a
// middlebox on the path can read that as a protocol violation rather than
// a migration, surfacing as ERR_QUIC_PROTOCOL_ERROR client-side. Route
// sniffed QUIC through the same single-path-with-failover group as DNS
// instead of the dual-path one. See buildLoadBalance.
const val TAG_QUIC_PROXY = "quic-proxy"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    // Keyed by "$proxyId:$protectPath" rather than just proxyId, so the same
    // underlying profile used for two different vload network slots (each
    // with its own protect_path) gets two independently-built outbounds
    // instead of one slot silently reusing the other's cached tag.
    val globalOutbounds = HashMap<String, String>()
    val selectorNames = ArrayList<String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    // vload: "Static QUIC Port Match" mode (trafficSniffing==3) deliberately
    // runs no general sniffing when fake-ip is on, since fake-ip already
    // gives the router a flow's domain via reverse lookup - the router
    // never needed sniffing for that in the first place. But without
    // fake-ip, an IP-literal connection has no domain at all unless
    // something sniffs the TLS SNI/HTTP Host for it, so a custom
    // domain-based routing rule would silently stop matching in that
    // specific combination. Falling back to sniffing here when fake-ip is
    // off preserves that correctness - safe to do now that sing-box-vload's
    // sniff retry loop is capped (see singleSniffMaxAttempts in
    // route/route.go), so this fallback can't reintroduce the unbounded
    // hang risk this mode exists to avoid in the first place.
    val needSniff = DataStore.needSniff || (DataStore.quicPortMatch && !useFakeDns)
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        if (!forTest && DataStore.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                external_ui = "../files/yacd"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            // vload: independent_cache deprecated in sing-box 1.14.0 - "the
            // DNS cache now always keys by transport name, making
            // independent_cache unnecessary" (migration guide says to just
            // remove it). Its own docs say it "will slightly degrade
            // performance" when on, so leaving it set was actively paying
            // for a benefit the new DNS server model already gives for
            // free - not just a leftover no-op.
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        // vload: sing-box 1.12 replaced the old flat "address"-URL DNS
        // server scheme (address = "https://host/path", "tls://host",
        // bare "host", "local", ...) with a type-discriminated object; the
        // legacy scheme was removed outright in 1.14.0. This rebuilds a
        // DNSServerOptions from the same URL-shaped strings the app's
        // settings UI already stores, so existing user DNS settings keep
        // working unchanged.
        fun buildDnsServer(
            addressOrKeyword: String,
            dnsTag: String,
            detourTag: String? = null,
            resolverTag: String? = null,
            resolverStrategy: String? = null,
        ): DNSServerOptions = DNSServerOptions().apply {
            tag = dnsTag
            if (detourTag != null) detour = detourTag
            if (addressOrKeyword == "local") {
                type = "local"
                return@apply
            }
            val uri = java.net.URI(
                if ("://" in addressOrKeyword) addressOrKeyword else "udp://$addressOrKeyword"
            )
            type = when (uri.scheme) {
                "tcp", "tls", "https", "quic", "h3" -> uri.scheme
                else -> "udp"
            }
            server = uri.host ?: addressOrKeyword
            server_port = when {
                uri.port > 0 -> uri.port
                type == "tls" || type == "quic" -> 853
                type == "https" || type == "h3" -> 443
                else -> 53
            }
            if (type == "https" || type == "h3") {
                path = uri.rawPath.takeIf { !it.isNullOrBlank() } ?: "/dns-query"
            }
            if (resolverTag != null) {
                domain_resolver = DNSDomainResolverOptions().apply {
                    server = resolverTag
                    if (resolverStrategy != null) strategy = resolverStrategy
                }
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                // "inet4_address"/"inet6_address" are legacy tun address
                // fields, deprecated in sing-box 1.10.0 and removed in
                // sing-box 1.12.0 - replaced by a single unified "address"
                // list mixing v4/v6 prefixes.
                _hack_config_map["address"] = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126",
                    )
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
        }

        outbounds = mutableListOf()
        endpoints = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity, protectPath: String? = null
        ): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    // Suffix by protectPath too: the same profile used for
                    // both vload slots must still get two distinct tags,
                    // not just two distinct globalOutbounds cache entries -
                    // otherwise both end up named "g-<id>" and sing-box
                    // rejects the config as a duplicate outbound tag.
                    tagOut = "g-" + proxyEntity.id + (protectPath?.let { "-$it" } ?: "")
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(bean.displayName())
                }


                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    val globalKey = "${proxyEntity.id}:${protectPath ?: ""}"
                    globalOutbounds[globalKey]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[globalKey] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config)

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        // vload: WireGuard/OpenVPN/OpenConnect are endpoints
                        // (persistent tunnel interfaces), not outbounds -
                        // see the endpoints.add branch below.
                        is WireGuardBean ->
                            buildSingBoxEndpointWireguardBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        is OpenVPNBean ->
                            buildSingBoxEndpointOpenVPNBean(bean)

                        is OpenConnectBean ->
                            buildSingBoxEndpointOpenConnectBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    // Only the outermost hop of a chain actually dials the
                    // physical network - earlier hops tunnel logically over
                    // it via "detour" - so protect_path (which pins vload's
                    // two network slots) only applies here.
                    if (protectPath != null && index == profileList.lastIndex) {
                        _hack_config_map["protect_path"] = protectPath
                    }

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                // vload: endpoints (persistent tunnel interfaces) live in
                // their own top-level config array, not outbounds - see the
                // WireGuardBean/OpenVPNBean/OpenConnectBean dispatch above.
                if (bean is WireGuardBean || bean is OpenVPNBean || bean is OpenConnectBean) {
                    endpoints.add(currentOutbound)
                } else {
                    outbounds.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // vload: wrap the two slot profiles' chains in a weighted outbound,
        // each slot's outermost hop pinned to its own network via protect_path.
        fun buildLoadBalance(lbProxy: ProxyEntity) {
            val lb = lbProxy.loadBalanceBean!!
            val slotAEntity = SagerDatabase.proxyDao.getById(lb.slotAProxyId)
                ?: error("vload: Network A profile not found")
            val slotBEntity = SagerDatabase.proxyDao.getById(lb.slotBProxyId)
                ?: error("vload: Network B profile not found")

            val slotATag = buildChain(lb.slotAProxyId, slotAEntity, protectPath = "protect_path_a")
            val slotBTag = buildChain(lb.slotBProxyId, slotBEntity, protectPath = "protect_path_b")

            outbounds.add(0, Outbound_WeightedOptions().apply {
                type = "weighted"
                tag = TAG_PROXY
                outbounds = listOf(
                    WeightedOutboundMember().apply {
                        outbound = slotATag
                        weight = lb.slotAWeight.toLong()
                    },
                    WeightedOutboundMember().apply {
                        outbound = slotBTag
                        weight = lb.slotBWeight.toLong()
                    },
                )
            })

            // Same two slots, but DNS wants a single consistent source with
            // failover, not both networks' answers mixed per query - see
            // TAG_DNS_PROXY.
            outbounds.add(0, Outbound_WeightedOptions().apply {
                type = "weighted"
                tag = TAG_DNS_PROXY
                mode = "priority"
                outbounds = listOf(
                    WeightedOutboundMember().apply { outbound = slotATag },
                    WeightedOutboundMember().apply { outbound = slotBTag },
                )
            })

            // Same two slots again, for QUIC - see TAG_QUIC_PROXY.
            outbounds.add(0, Outbound_WeightedOptions().apply {
                type = "weighted"
                tag = TAG_QUIC_PROXY
                mode = "priority"
                outbounds = listOf(
                    WeightedOutboundMember().apply { outbound = slotATag },
                    WeightedOutboundMember().apply { outbound = slotBTag },
                )
            })

            // route.final was never set, so sing-box defaulted it to the
            // first outbound in the list - and since TAG_DNS_PROXY gets
            // inserted at index 0 *after* TAG_PROXY (both use add(0, ...)),
            // TAG_DNS_PROXY ended up as the accidental default for ALL
            // unmatched traffic, not just DNS. Its priority mode always
            // prefers slot A and only touches slot B on failure, which is
            // why virtually all real traffic was pinned to slot A and the
            // real adaptive TAG_PROXY group's picker was never even invoked.
            route.final_ = TAG_PROXY
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else if (proxy.type == ProxyEntity.TYPE_LOAD_BALANCE) {
            buildLoadBalance(proxy)
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList = rule.packages.map {
                if (!isVPN) {
                    Toast.makeText(
                        SagerNet.application,
                        SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                if (uidList.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList
                }
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                }

                if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.listByLineOrComma()
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) user_id = uidList
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                when (rule.outbound) {
                    -1L -> {
                        userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                    }

                    0L -> {
                        if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-fake"
                            inbound = listOf("tun-in")
                        }
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-remote"
                        }
                    }

                    -2L -> {
                        userDNSRuleList += makeDnsRuleObj().apply {
                            // vload: replaces the removed rcode://success
                            // pseudo-server (see buildDnsServer) with the
                            // equivalent inline rule action.
                            action = "predefined"
                            rcode = "success"
                            disable_cache = true
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route.rules.add(ruleObj)
                    route.rule_set.addAll(ruleSets)
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
            // vload: sing-box 1.14.0 rejects a DNS server's `detour` field
            // pointing at a completely unconfigured direct outbound
            // ("detour to an empty direct outbound makes no sense") - dns-
            // local/dns-direct legitimately need to detour here (bypassing
            // the proxy to avoid a DNS resolution loop), so mark this
            // outbound non-empty with a harmless, genuinely useful option.
            _hack_config_map["tcp_fast_open"] = true
        })

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(buildDnsServer("local", "dns-local", detourTag = TAG_DIRECT))

        directDNS.firstOrNull().let {
            dns.servers.add(
                buildDnsServer(
                    it ?: throw Exception("No direct DNS, check your settings!"),
                    "dns-direct",
                    detourTag = TAG_DIRECT,
                    resolverTag = "dns-local",
                    resolverStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct")),
                )
            )
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(buildDnsServer(
                it ?: throw Exception("No remote DNS, check your settings!"),
                "dns-remote",
                resolverTag = "dns-direct",
                resolverStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote")),
            ).apply {
                // Without this, dns-remote falls to the router's default
                // outbound (TAG_PROXY), which combines both networks per
                // query - fine for bulk throughput, but two networks can
                // give different answers (split-horizon DNS, different
                // upstream results), so bouncing between them per-lookup
                // undermines stable browsing. Pin DNS to the priority-mode
                // outbound instead: one consistent network, failing over
                // rather than splitting.
                if (proxy.type == ProxyEntity.TYPE_LOAD_BALANCE) {
                    detour = TAG_DNS_PROXY
                }
            })
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // migrated from legacy per-inbound sniff/domain_strategy fields
            // (removed in sing-box 1.13.0) to rule actions - see
            // https://sing-box.sagernet.org/migration/#migrate-legacy-inbound-fields-to-rule-actions
            val sniffInboundTags = buildList {
                if (isVPN) add("tun-in")
                add(TAG_MIXED)
            }
            val domainStrategy = genDomainStrategy(DataStore.resolveDestination)
            if (domainStrategy.isNotEmpty()) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    inbound = sniffInboundTags
                    action = "resolve"
                    _hack_config_map["strategy"] = domainStrategy
                })
            }
            if (needSniff) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    inbound = sniffInboundTags
                    action = "sniff"
                })
            }
            // vload: "Static QUIC Port Match" (Settings > Route Settings >
            // Enable Traffic Sniffing > "Static QUIC Port Match", the 4th
            // value of that same dropdown) - identifies QUIC/HTTP3 traffic
            // by network=udp + port=443 instead of sniffing for it. This
            // replaces an earlier protocol="quic" approach that required the
            // sniff rule above to run first and positively classify the flow
            // before either rule below could match.
            //
            // That sniff-based approach turned out to be the actual source
            // of the reels/fast-scrolling hang, not a fix for it: sing-box's
            // UDP sniff path (route/route.go) waits up to 300ms per packet
            // for a QUIC client hello to complete, and - confirmed by
            // reading the loop directly - retried that wait indefinitely on
            // timeout with no cap of its own, bounded only by the whole VPN
            // session's lifetime. Fast-scrolling through short video clips
            // abandons a QUIC flow mid-handshake on every single clip
            // scrolled past, so every one of those was leaking a retrying
            // read loop for the rest of the session. Scoping the sniff rule
            // more narrowly (an earlier attempt) only reduced how much
            // *other* traffic entered that same broken path - it didn't fix
            // the path itself, which is why it measured worse, not better.
            //
            // Matching on port+network instead needs no classification step
            // at all: the rule below is evaluated the instant a flow's
            // destination is known, with zero wait and nothing to retry.
            // Virtually all real-world QUIC/HTTP3 traffic - the traffic
            // these two rules exist for - runs on UDP/443, so this is just
            // as accurate in practice while removing the entire hang
            // mechanism. The tradeoff: a small amount of non-QUIC UDP
            // traffic that happens to use port 443 would also match: for
            // the Load Balance rule that just means it takes the
            // single-path QUIC group instead of the dual-path one, and for
            // the timeout rule it just gets 90s instead of the default 5m -
            // both harmless. Both rules are gated on trafficSniffing==3
            // (DataStore.quicPortMatch) rather than needSniff, and moved
            // out of that block entirely - selecting "Sniff result for
            // routing" or "destination" instead does NOT also apply these,
            // by explicit design: those two modes go back to sing-box's
            // original sniff-based QUIC handling (hang risk included),
            // since the whole point of this being its own dropdown value
            // is to let it be picked deliberately instead of silently
            // layered under every other mode.
            if (DataStore.quicPortMatch) {
                if (proxy.type == ProxyEntity.TYPE_LOAD_BALANCE) {
                    route.rules.add(Rule_DefaultOptions().apply {
                        network = listOf("udp")
                        port = listOf(443)
                        outbound = TAG_QUIC_PROXY
                    })
                }
                // sing-box hardcodes a 30s idle timeout for any UDP flow it
                // classifies as QUIC (constant.ProtocolTimeouts), separate
                // from the 5-minute default every other UDP flow gets
                // (constant.UDPTimeout). Fake-ip routes virtually all modern
                // UDP HTTPS traffic - video included - through this exact
                // path, and 30s is short enough that an ordinary pause
                // (reading a page, buffering ahead) outlives it: the flow
                // gets torn down and has to fully re-establish, which is
                // where a real stall comes from, not the teardown itself.
                // 90s comfortably covers a normal pause while staying well
                // short of the full 5-minute default, so an abandoned flow
                // (fast scrolling) still clears out reasonably quickly
                // rather than accumulating for the rest of the session.
                route.rules.add(Rule_DefaultOptions().apply {
                    network = listOf("udp")
                    port = listOf(443)
                    action = "route-options"
                    _hack_config_map["udp_timeout"] = "90s"
                })
            }
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    // vload: only hand out fake IPv6 addresses when the TUN
                    // interface actually carries IPv6 (see the "address"
                    // field below, gated the same way). Regression from the
                    // 1.14.0 port: the old fakeip config had a
                    // strategy="ipv4_only" field constraining this, but that
                    // field doesn't exist on the new inline fakeip DNS
                    // server schema - dropping it during the port silently
                    // made fakeip hand out unconditionally-unreachable fake
                    // IPv6 addresses (fc00::/18) whenever ipv6Mode was
                    // DISABLE, which every AAAA-preferring/Happy-Eyeballs
                    // connection then had to time out on before falling
                    // back to the working IPv4 fake address - exactly the
                    // kind of intermittent slow/unstable behavior a user
                    // would see across ordinary browsing.
                    if (ipv6Mode != IPv6Mode.DISABLE) {
                        inet6_range = "fc00::/18"
                    }
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L
        )
    }

}
