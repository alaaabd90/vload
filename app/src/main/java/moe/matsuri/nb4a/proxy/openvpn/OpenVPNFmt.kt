package moe.matsuri.nb4a.proxy.openvpn

import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxEndpointOpenVPNBean(bean: OpenVPNBean): SingBoxOptions.Endpoint_OpenVPNOptions {
    return SingBoxOptions.Endpoint_OpenVPNOptions().apply {
        type = "openvpn-client"
        server = bean.serverAddress
        server_port = bean.serverPort
        // vload: endpoints resolve their own server address at creation
        // time and fail without this if it's a domain - route it via
        // dns-direct so resolving this profile's own server doesn't depend
        // on the proxy (which this profile itself provides) already being up.
        if (!bean.serverAddress.isIpAddress()) {
            domain_resolver = SingBoxOptions.DNSDomainResolverOptions().apply {
                server = "dns-direct"
            }
        }
        mode = bean.mode
        if (bean.network.isNotBlank()) network = bean.network
        if (bean.username.isNotBlank()) username = bean.username
        if (bean.password.isNotBlank()) password = bean.password
        if (bean.cipher.isNotBlank()) cipher = bean.cipher
        if (bean.auth.isNotBlank()) auth = bean.auth
        if (bean.mssFix != 0) mss_fix = bean.mssFix

        if (bean.mode == "static_key") {
            if (bean.staticKey.isNotBlank()) static_key = bean.staticKey
            if (bean.keyDirection.isNotBlank()) key_direction = bean.keyDirection
        } else {
            // vload: sing-box requires a `tls` object to exist in TLS mode
            // even with every field left at its default ("missing `tls`
            // options") - always construct it here, not just when a
            // certificate/server-name/control-wrap is actually set.
            val hasControlWrap = bean.controlWrapType.isNotBlank() && bean.controlWrapKey.isNotBlank()
            tls = SingBoxOptions.OpenVPNTLSOptions().apply {
                // vload: sing-box rejects a `tls` object with nothing in it
                // ("missing `tls` options") even when present, so default
                // the SNI to the server hostname when unset - correct
                // behavior on its own (matches what a real TLS client does),
                // and guarantees `tls` is never a meaningless empty object.
                server_name = bean.tlsServerName.ifBlank { bean.serverAddress }
                if (bean.caCertificate.isNotBlank()) certificate = bean.caCertificate
                if (bean.clientCertificate.isNotBlank()) client_certificate = bean.clientCertificate
                if (bean.clientKey.isNotBlank()) client_key = bean.clientKey
                if (hasControlWrap) {
                    control_wrap = SingBoxOptions.OpenVPNControlWrapOptions().apply {
                        type = bean.controlWrapType
                        key = bean.controlWrapKey
                        if (bean.keyDirection.isNotBlank()) direction = bean.keyDirection
                    }
                }
            }
        }
    }
}
