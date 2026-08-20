package io.nekohasekai.sagernet.fmt.http

import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.ktx.urlSafe
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseHttp(link: String): HttpBean {
    val httpUrl = link.toHttpUrlOrNull() ?: error("Invalid http(s) link: $link")

    if (httpUrl.encodedPath != "/") error("Not http proxy")

    return HttpBean().apply {
        serverAddress = httpUrl.host
        serverPort = httpUrl.port
        username = httpUrl.username
        password = httpUrl.password
        sni = httpUrl.queryParameter("sni")
        name = httpUrl.fragment
        setTLS(httpUrl.scheme == "https")

        if (isTLS()) {
            httpUrl.queryParameter("alpn")?.let { alpn = it }
            httpUrl.queryParameter("cert")?.let { certificates = it }
            httpUrl.queryParameter("allowInsecure")?.let { allowInsecure = it == "1" }
            httpUrl.queryParameter("fp")?.let { utlsFingerprint = it }
            httpUrl.queryParameter("pbk")?.let { realityPubKey = it }
            httpUrl.queryParameter("sid")?.let { realityShortId = it }
            httpUrl.queryParameter("enableECH")?.let {
                enableECH = it == "1"
                httpUrl.queryParameter("echConfig")?.let { config -> echConfig = config }
            }
        }
        httpUrl.queryParameter("tcpFastOpen")?.let { tcpFastOpen = it == "1" }
        httpUrl.queryParameter("sniFragment")?.let { sniFragment = it == "1" }
        httpUrl.queryParameter("enableMux")?.let {
            enableMux = it == "1"
            httpUrl.queryParameter("muxPadding")?.let { padding -> muxPadding = padding == "1" }
            httpUrl.queryParameter("muxType")?.toIntOrNull()?.let { muxType = it }
            httpUrl.queryParameter("muxConcurrency")?.toIntOrNull()?.let { muxConcurrency = it }
        }
    }
}

fun HttpBean.toUri(): String {
    val builder = HttpUrl.Builder().scheme(if (isTLS()) "https" else "http").host(serverAddress)

    if (serverPort in 1..65535) {
        builder.port(serverPort)
    }

    if (username.isNotBlank()) {
        builder.username(username)
    }
    if (password.isNotBlank()) {
        builder.password(password)
    }
    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }

    if (isTLS()) {
        if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn.replace("\n", ","))
        if (certificates.isNotBlank()) builder.addQueryParameter("cert", certificates)
        if (allowInsecure) builder.addQueryParameter("allowInsecure", "1")
        if (utlsFingerprint.isNotBlank()) builder.addQueryParameter("fp", utlsFingerprint)
        if (realityPubKey.isNotBlank()) builder.addQueryParameter("pbk", realityPubKey)
        if (realityShortId.isNotBlank()) builder.addQueryParameter("sid", realityShortId)
        if (enableECH) {
            builder.addQueryParameter("enableECH", "1")
            if (echConfig.isNotBlank()) builder.addQueryParameter("echConfig", echConfig)
        }
    }

    if (tcpFastOpen) builder.addQueryParameter("tcpFastOpen", "1")
    if (sniFragment) builder.addQueryParameter("sniFragment", "1")
    if (enableMux) {
        builder.addQueryParameter("enableMux", "1")
        if (muxPadding) builder.addQueryParameter("muxPadding", "1")
        builder.addQueryParameter("muxType", "$muxType")
        builder.addQueryParameter("muxConcurrency", "$muxConcurrency")
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toString()
}