package io.nekohasekai.sagernet.fmt.snispoof

import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundECHOptions
import org.json.JSONObject

/** Keep a versioned envelope, including unknown keys, for forward-compatible sharing. */
object SniSpoofSettings {
    val defaults: Map<String, Any> = linkedMapOf(
        "fake_enabled" to true, "fake_sni" to "api.twitter.com", "fake_ttl" to 6,
        "fake_auto_ttl" to true, "fake_delay_ms" to 10,
        "strategy" to "auto", "fragment_size" to 128, "delay_ms" to 10,
        "require_ech" to false, "ech_config" to "", "candidate_ips" to "",
        "discover_cloudflare" to true, "max_candidates" to 8,
        "attempt_timeout_ms" to 3000, "total_timeout_ms" to 15000,
        "retry_after_seconds" to 60, "diagnostics" to true,
        "test_url" to "https://www.gstatic.com/generate_204"
    )

    fun parse(raw: String?): JSONObject {
        val result = if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        require(result.optInt("version", 1) == 1) { "Unsupported SNISpoof settings version" }
        defaults.forEach { (key, value) -> if (!result.has(key)) result.put(key, value) }
        result.put("version", 1)
        return result
    }

    fun candidates(settings: JSONObject): List<String> = settings.getString("candidate_ips")
        .split(Regex("[\\s,;]+" )).filter { it.isNotBlank() }

    fun validate(bean: StandardV2RayBean, settings: JSONObject) {
        require(bean.security == "tls" && bean.realityPubKey.isNullOrBlank() && bean.type != "quic") {
            "SNISpoof requires ordinary TLS over TCP (TCP, WebSocket, HTTP, HTTPUpgrade or gRPC), without Reality."
        }
        require(bean.customOutboundJson.isNullOrBlank() && bean.customConfigJson.isNullOrBlank()) {
            "SNISpoof cannot be combined with custom JSON overrides; they could bypass the selected TLS policy."
        }
        val realName = bean.sni.orEmpty().ifBlank { bean.serverAddress.orEmpty() }
        require(realName.isNotBlank() && !realName.contains(':') && !realName.matches(Regex("[0-9.]+"))) {
            "Enter your real domain in the profile TLS SNI field."
        }
        require(settings.getString("strategy") in listOf("auto", "sni", "record", "chunks", "none")) { "Unknown fragmentation strategy" }
        if (settings.getBoolean("fake_enabled")) {
            val name = settings.getString("fake_sni")
            require(name.length <= 253 && name.contains('.') && name.split('.').all {
                it.length in 1..63 && it.first() != '-' && it.last() != '-' && it.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' }
            }) { "Enter a valid fake hostname, for example api.twitter.com" }
        }
        val ranges = mapOf("fake_ttl" to 1..32, "fake_delay_ms" to 1..50,
            "fragment_size" to 16..4096, "delay_ms" to 0..200,
            "max_candidates" to 1..32, "attempt_timeout_ms" to 500..15000,
            "total_timeout_ms" to 500..60000, "retry_after_seconds" to 10..3600)
        ranges.forEach { (key, range) -> require(settings.getInt(key) in range) { "$key must be ${range.first}–${range.last}" } }
        require(settings.getInt("total_timeout_ms") >= settings.getInt("attempt_timeout_ms")) { "Total timeout must cover one attempt." }
        require(candidates(settings).size <= 32) { "Use at most 32 candidate IPs or CIDRs." }
        candidates(settings).forEach { entry ->
            val parts = entry.split('/')
            require(parts.size <= 2) { "Invalid IP or CIDR: $entry" }
            val ip = parts[0]
            require(if (ip.contains(':')) ip.matches(Regex("[0-9a-fA-F:.]+")) else ip.matches(Regex("[0-9.]+"))) {
                "Use numeric IP addresses or CIDRs, not hostnames: $entry"
            }
            val parsed = java.net.InetAddress.getByName(ip)
            if (parts.size == 2) require(parts[1].toIntOrNull() in 0..(parsed.address.size * 8)) { "Invalid CIDR prefix: $entry" }
        }
        require(settings.getString("test_url").startsWith("https://")) { "The test URL must use HTTPS." }
    }

    fun nativeOptions(settings: JSONObject): MutableMap<String, Any> = linkedMapOf<String, Any>().apply {
        defaults.keys.filter { it !in listOf("ech_config", "test_url", "candidate_ips") }.forEach { put(it, settings.get(it)) }
        put("candidate_ips", candidates(settings))
    }

    fun configure(bean: StandardV2RayBean, tls: OutboundTLSOptions) {
        val settings = parse(bean.snispoofSettings)
        validate(bean, settings)
        tls.insecure = false // Opted-in mode always verifies the real server certificate.
        tls.fragment = null
        tls.record_fragment = null
        tls.snispoof = nativeOptions(settings)
        tls.ech = if (settings.getBoolean("require_ech")) OutboundECHOptions().apply {
            enabled = true
            val raw = settings.getString("ech_config").trim()
            if (raw.isNotEmpty()) {
                // Preserve legacy PEM support and accept a directly supplied ECHConfigList.
                config = if (raw.startsWith("-----BEGIN")) raw.lines() else
                    listOf("-----BEGIN ECH CONFIGS-----", raw.filterNot { it.isWhitespace() }, "-----END ECH CONFIGS-----")
            }
        } else null
    }
}
