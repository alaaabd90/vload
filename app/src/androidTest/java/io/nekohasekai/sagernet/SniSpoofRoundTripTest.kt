package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.snispoof.SniSpoofSettings
import io.nekohasekai.sagernet.ui.profile.SniSpoofEditor
import io.nekohasekai.sagernet.utils.LockedProfileCrypto
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SniSpoofRoundTripTest {
    private val extension = SniSpoofSettings.parse("").apply {
        put("fake_enabled", true); put("fake_sni", "api.twitter.com"); put("fake_ttl", 7); put("fake_auto_ttl", false); put("fake_delay_ms", 12)
        put("strategy", "record"); put("fragment_size", 96); put("delay_ms", 7)
        put("require_ech", true); put("ech_config", "AAE=")
        put("candidate_ips", "104.16.1.2\n2606:4700::1\n104.24.0.0/24")
        put("discover_cloudflare", false); put("max_candidates", 13)
        put("attempt_timeout_ms", 1700); put("total_timeout_ms", 11000)
        put("retry_after_seconds", 97); put("diagnostics", false)
        put("test_url", "https://example.com/test?q=a&b=c")
        put("future_option", JSONObject().put("nested", "preserve + / = ? &"))
    }.toString()

    private fun sample(kind: String, enabled: Boolean): StandardV2RayBean {
        val bean = when (kind) {
            "trojan" -> TrojanBean()
            "http" -> HttpBean()
            else -> VMessBean().apply { alterId = if (kind == "vless") -1 else 0 }
        }
        bean.initializeDefaultValues()
        bean.serverAddress = "104.16.1.2"; bean.serverPort = 443
        bean.sni = "real.example.com"; bean.name = "SNISpoof $kind round trip"
        bean.security = "tls"; bean.type = "ws"; bean.host = "real.example.com"; bean.path = "/vpn?ed=2048"
        bean.uuid = "11111111-1111-4111-8111-111111111111"
        bean.snispoofEnabled = enabled; bean.snispoofSettings = extension
        return bean
    }

    private fun parseLink(link: String): StandardV2RayBean = when {
        link.startsWith("sn://") -> parseUniversal(link) as StandardV2RayBean
        link.startsWith("http") -> parseHttp(link).apply { initializeDefaultValues() }
        link.startsWith("trojan://") -> parseTrojan(link).apply { initializeDefaultValues() }
        else -> parseV2Ray(link).apply { initializeDefaultValues() }
    }

    private fun same(expected: StandardV2RayBean, actual: StandardV2RayBean) {
        assertEquals(expected.snispoofEnabled, actual.snispoofEnabled)
        assertEquals(expected.snispoofSettings, actual.snispoofSettings)
        assertEquals(expected.serverAddress, actual.serverAddress)
        assertEquals(expected.sni, actual.sni)
    }

    @Test fun newProfilesUseLTEPresetWithoutOverwritingImportedSettings() {
        val preset = SniSpoofSettings.parse("")
        assertTrue(preset.getBoolean("fake_enabled"))
        assertEquals("api.twitter.com", preset.getString("fake_sni"))
        assertEquals(6, preset.getInt("fake_ttl"))
        assertTrue(preset.getBoolean("fake_auto_ttl"))
        assertFalse(preset.getBoolean("require_ech"))
        val existing = SniSpoofSettings.parse("{\"version\":1,\"fake_enabled\":false,\"fake_sni\":\"saved.example.com\",\"fake_ttl\":11}")
        assertFalse(existing.getBoolean("fake_enabled"))
        assertEquals("saved.example.com", existing.getString("fake_sni"))
        assertEquals(11, existing.getInt("fake_ttl"))
        val bean=sample("vless",true)
        bean.allowInsecure=true
        assertEquals(false,buildSingBoxOutboundTLS(bean)!!.insecure)
        assertTrue(bean.allowInsecure)
        bean.snispoofEnabled=false
        assertEquals(true,buildSingBoxOutboundTLS(bean)!!.insecure)
    }

    @Test fun allProfileStorageAndExportRoutesPreserveEnabledAndDisabledSettings() {
        for (kind in listOf("vless", "vmess", "trojan", "http")) for (enabled in listOf(false, true)) {
            val bean = sample(kind, enabled)
            same(bean, bean.clone() as StandardV2RayBean)
            same(bean, parseUniversal(bean.toUniversalLink()) as StandardV2RayBean)
            val link = ProxyEntity().putBean(bean).toStdLink()
            same(bean, parseLink(link))
            // Exactly the plaintext and crypto paths used by .vload export/import.
            val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "snispoof-roundtrip.vload")
            file.writeText(link); same(bean, parseLink(file.readText())); file.delete()
            val hwid = "0123456789ABCDEF0123456789ABCDEF"
            val locked = LockedProfileCrypto.encryptForHwid(link, hwid)
            val clear = LockedProfileCrypto.tryDecrypt(locked, hwid) as LockedProfileCrypto.DecryptResult.Decrypted
            same(bean, parseLink(clear.plaintext))
            assertTrue(LockedProfileCrypto.tryDecrypt(locked, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF") is LockedProfileCrypto.DecryptResult.WrongDevice)
        }
    }

    @Test fun editorPreservesUnknownFutureFields() {
        val bean = sample("vless", true)
        SniSpoofEditor.cache(bean)
        DataStore.profileCacheStore.putString("snispoof.delay_ms", "15")
        SniSpoofEditor.save(bean)
        val value = JSONObject(bean.snispoofSettings)
        assertEquals(15, value.getInt("delay_ms"))
        assertEquals("preserve + / = ? &", value.getJSONObject("future_option").getString("nested"))
    }

    @Test fun disabledModeLeavesLegacyTLSOptionsUntouched() {
        val bean = sample("vless", false)
        bean.enableECH = false; bean.sniFragment = true
        val before = Gson().toJson(buildSingBoxOutboundTLS(bean))
        bean.snispoofSettings = "not valid JSON; disabled settings must not be read"
        assertEquals(before, Gson().toJson(buildSingBoxOutboundTLS(bean)))
        assertNull(buildSingBoxOutboundTLS(bean)!!.snispoof)
        assertEquals(true, buildSingBoxOutboundTLS(bean)!!.fragment)
    }

    @Test fun disablingSNISpoofRestoresEveryLegacyFragmentationAndECHCombination() {
        for (fragment in listOf(false, true)) for (ech in listOf(false, true)) {
            val bean = sample("vless", false)
            bean.sniFragment = fragment; bean.enableECH = ech
            bean.echConfig = "legacy-config-line-1\nlegacy-config-line-2"
            val legacy = buildSingBoxOutboundTLS(bean)!!
            val before = Gson().toJson(legacy)
            assertNull(legacy.snispoof)
            assertEquals(if (fragment) true else null, legacy.fragment)
            assertEquals(if (fragment) true else null, legacy.record_fragment)
            if (ech) {
                assertEquals(true, legacy.ech.enabled)
                assertEquals(bean.echConfig.lines(), legacy.ech.config)
            } else assertNull(legacy.ech)
            bean.snispoofEnabled = true
            buildSingBoxOutboundTLS(bean)
            assertEquals(fragment, bean.sniFragment)
            assertEquals(ech, bean.enableECH)
            assertEquals("legacy-config-line-1\nlegacy-config-line-2", bean.echConfig)
            bean.snispoofEnabled = false
            assertEquals(before, Gson().toJson(buildSingBoxOutboundTLS(bean)))
            val imported = parseLink(ProxyEntity().putBean(bean).toStdLink())
            assertEquals(fragment, imported.sniFragment)
            assertEquals(ech, imported.enableECH)
            if (ech) assertEquals(bean.echConfig, imported.echConfig)
            assertEquals(before, Gson().toJson(buildSingBoxOutboundTLS(imported)))
        }
    }

    @Test fun enabledModeOwnsTLSOptionsWithoutChangingStoredLegacyPreferences() {
        val bean = sample("vless", true)
        bean.sniFragment = true
        val options = buildSingBoxOutboundTLS(bean)!!
        assertNotNull(options.snispoof); assertNull(options.fragment)
        assertEquals(false, options.insecure); assertEquals(true, options.ech.enabled)
        assertEquals("real.example.com", options.server_name)
        assertEquals(true, bean.sniFragment)
    }

    @Test fun legacyProfilesDefaultOffAndUnsupportedTransportIsRejected() {
        val legacy = parseV2Ray("vless://11111111-1111-4111-8111-111111111111@104.16.1.2:443?security=tls&sni=real.example.com&type=ws").apply { initializeDefaultValues() }
        assertEquals(false, legacy.snispoofEnabled)
        val bean = sample("vless", true); bean.type = "quic"
        try { buildSingBoxOutboundTLS(bean); fail("QUIC accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun releasedVersionSixBinaryLoadsWithSNISpoofOff() {
        val bean = sample("vless", true)
        bean.sniFragment = true
        val body = com.esotericsoftware.kryo.io.ByteBufferOutput(4096, -1)
        bean.serialize(body)
        val extensionBytes = com.esotericsoftware.kryo.io.ByteBufferOutput(4096, -1).apply {
            writeBoolean(true); writeString(bean.snispoofSettings)
        }.toBytes()
        val oldBody = body.toBytes().copyOf(body.position() - extensionBytes.size)
        val version = com.esotericsoftware.kryo.io.ByteBufferOutput(4).apply { writeInt(6) }.toBytes()
        version.copyInto(oldBody, 0)
        val legacy = com.esotericsoftware.kryo.io.ByteBufferOutput(4096, -1).apply {
            writeBytes(oldBody); writeInt(1); writeString(bean.name)
            writeString(bean.customOutboundJson); writeString(bean.customConfigJson)
        }.toBytes()
        val loaded = io.nekohasekai.sagernet.fmt.KryoConverters.deserialize(VMessBean(), legacy)
        assertEquals(false, loaded.snispoofEnabled)
        assertEquals("", loaded.snispoofSettings)
        assertEquals(true, loaded.sniFragment)
        assertEquals(bean.uuid, loaded.uuid)
        assertEquals(bean.path, loaded.path)
        assertEquals(bean.name, loaded.name)
    }
}
