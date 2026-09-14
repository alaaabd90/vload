package moe.matsuri.nb4a.proxy.openconnect

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxEndpointOpenConnectBean(bean: OpenConnectBean): SingBoxOptions.Endpoint_OpenConnectOptions {
    return SingBoxOptions.Endpoint_OpenConnectOptions().apply {
        type = "openconnect"
        server = if (bean.serverPort != null && bean.serverPort != 0) {
            "${bean.serverAddress}:${bean.serverPort}"
        } else {
            bean.serverAddress
        }
        flavor = bean.flavor
        if (bean.username.isNotBlank()) username = bean.username
        if (bean.password.isNotBlank()) password = bean.password
        if (bean.authGroup.isNotBlank()) auth_group = bean.authGroup
        if (bean.cookie.isNotBlank()) cookie = bean.cookie
        if (bean.userAgent.isNotBlank()) user_agent = bean.userAgent
        if (bean.noUdp) no_udp = true
        if (bean.mtu != 0) mtu = bean.mtu

        if (bean.insecure || bean.tlsServerName.isNotBlank() || bean.caCertificate.isNotBlank() ||
            bean.clientCertificate.isNotBlank() || bean.clientKey.isNotBlank()
        ) {
            tls = SingBoxOptions.OpenConnectTLSOptions().apply {
                if (bean.insecure) insecure = true
                if (bean.tlsServerName.isNotBlank()) server_name = bean.tlsServerName
                if (bean.caCertificate.isNotBlank()) certificate_authority = bean.caCertificate
                if (bean.clientCertificate.isNotBlank()) client_certificate = bean.clientCertificate
                if (bean.clientKey.isNotBlank()) client_key = bean.clientKey
            }
        }
    }
}
