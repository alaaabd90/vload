package moe.matsuri.nb4a.proxy.openvpn

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxEndpointOpenVPNBean(bean: OpenVPNBean): SingBoxOptions.Endpoint_OpenVPNOptions {
    return SingBoxOptions.Endpoint_OpenVPNOptions().apply {
        type = "openvpn-client"
        server = bean.serverAddress
        server_port = bean.serverPort
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
            val hasControlWrap = bean.controlWrapType.isNotBlank() && bean.controlWrapKey.isNotBlank()
            if (bean.caCertificate.isNotBlank() || bean.clientCertificate.isNotBlank() ||
                bean.clientKey.isNotBlank() || bean.tlsServerName.isNotBlank() || hasControlWrap
            ) {
                tls = SingBoxOptions.OpenVPNTLSOptions().apply {
                    if (bean.tlsServerName.isNotBlank()) server_name = bean.tlsServerName
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
}
