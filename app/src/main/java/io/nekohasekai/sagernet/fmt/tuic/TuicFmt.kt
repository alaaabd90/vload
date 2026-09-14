package io.nekohasekai.sagernet.fmt.tuic

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTuic(url: String): TuicBean {
    // https://github.com/daeuniverse/dae/discussions/182
    val link = url.replace("tuic://", "https://").toHttpUrlOrNull() ?: error(
        "invalid tuic link $url"
    )
    return TuicBean().apply {
        protocolVersion = 5

        name = link.fragment
        serverAddress = link.host
        serverPort = link.port

        val rawUser = link.username
        val rawPass = link.password

        if (rawUser.contains(":")) {
            val parts = rawUser.split(":", limit = 2)
            uuid = parts[0]
            token = parts.getOrElse(1) { "" }
        } else {
            uuid = rawUser
            token = rawPass
        }

        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("congestion_control")?.let {
            congestionController = it
        }
        link.queryParameter("udp_relay_mode")?.let {
            udpRelayMode = it
        }
        link.queryParameter("alpn")?.let {
            alpn = it
        }
        link.queryParameter("allow_insecure")?.let {
            if (it == "1") allowInsecure = true
        }
        link.queryParameter("disable_sni")?.let {
            if (it == "1") disableSNI = true
        }
        link.queryParameter("reduce_rtt")?.let {
            if (it == "1") reduceRTT = true
        }
        link.queryParameter("mtu")?.toIntOrNull()?.let {
            mtu = it
        }
        link.queryParameter("fast_connect")?.let {
            if (it == "1") fastConnect = true
        }
        link.queryParameter("ca")?.let {
            caText = it
        }
        link.queryParameter("custom_json")?.let {
            customJSON = it
        }
    }
}

fun TuicBean.toUri(): String {
    val builder = linkBuilder().username(uuid).password(token).host(serverAddress).port(serverPort)

    builder.addQueryParameter("congestion_control", congestionController)
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn)
    if (allowInsecure) builder.addQueryParameter("allow_insecure", "1")
    if (disableSNI) builder.addQueryParameter("disable_sni", "1")
    if (reduceRTT) builder.addQueryParameter("reduce_rtt", "1")
    if (mtu != 1400) builder.addQueryParameter("mtu", "$mtu")
    if (fastConnect) builder.addQueryParameter("fast_connect", "1")
    if (caText.isNotBlank()) builder.addQueryParameter("ca", caText)
    if (customJSON.isNotBlank()) builder.addQueryParameter("custom_json", customJSON)
    if (name.isNotBlank()) builder.encodedFragment(name.urlSafe())

    return builder.toLink("tuic")
}

fun buildSingBoxOutboundTuicBean(bean: TuicBean): SingBoxOptions.Outbound_TUICOptions {
    if (bean.protocolVersion == 4) throw Exception("TUIC v4 is no longer supported")
    return SingBoxOptions.Outbound_TUICOptions().apply {
        type = "tuic"
        server = bean.serverAddress
        server_port = bean.serverPort
        uuid = bean.uuid
        password = bean.token
        congestion_control = bean.congestionController
        when (bean.udpRelayMode) {
            "quic" -> udp_relay_mode = "quic"
        }
        zero_rtt_handshake = bean.reduceRTT
        if (bean.quicIdleTimeout > 0) idle_timeout = "${bean.quicIdleTimeout}s"
        if (bean.quicKeepAlivePeriod > 0) keep_alive_period = "${bean.quicKeepAlivePeriod}s"
        if (bean.quicStreamReceiveWindow > 0) stream_receive_window = bean.quicStreamReceiveWindow.toLong()
        if (bean.quicConnectionReceiveWindow > 0) connection_receive_window = bean.quicConnectionReceiveWindow.toLong()
        if (bean.quicMaxConcurrentStreams > 0) max_concurrent_streams = bean.quicMaxConcurrentStreams
        if (bean.quicInitialPacketSize > 0) initial_packet_size = bean.quicInitialPacketSize
        if (bean.quicDisablePathMtuDiscovery) disable_path_mtu_discovery = true
        tls = SingBoxOptions.OutboundTLSOptions().apply {
            if (bean.sni.isNotBlank()) {
                server_name = bean.sni
            }
            if (bean.alpn.isNotBlank()) {
                alpn = bean.alpn.listByLineOrComma()
            }
            if (bean.caText.isNotBlank()) {
                certificate = bean.caText
            }
            disable_sni = bean.disableSNI
            insecure = bean.allowInsecure || DataStore.globalAllowInsecure
            enabled = true
        }
    }
}
