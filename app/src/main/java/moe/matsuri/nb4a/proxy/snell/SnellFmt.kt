package moe.matsuri.nb4a.proxy.snell

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound_SnellOptions {
    return SingBoxOptions.Outbound_SnellOptions().apply {
        type = "snell"
        server = bean.serverAddress
        server_port = bean.serverPort
        version = bean.version
        psk = bean.psk
        if (bean.userkey.isNotBlank()) userkey = bean.userkey
        if (bean.reuse) reuse = true
        if (version == 6) {
            if (bean.v6Mode.isNotBlank() && bean.v6Mode != "default") mode = bean.v6Mode
        } else {
            if (bean.obfsMode.isNotBlank() && bean.obfsMode != "none") {
                obfs_mode = bean.obfsMode
                if (bean.obfsHost.isNotBlank()) obfs_host = bean.obfsHost
            }
        }
    }
}
