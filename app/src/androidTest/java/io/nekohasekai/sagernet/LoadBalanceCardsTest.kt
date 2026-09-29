package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.internal.*
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadBalanceCardsTest {
    @Test fun editorShowsSavedCardsAndPreservesThemWhenChangingNetworkKind() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.READ_PHONE_STATE").close()
        val bean = LoadBalanceBean().apply {
            initializeDefaultValues(); name = "Card editor test"
            slotBNetworkKind = 1; slotBSubscriptionId = SimCardProfiles.FOLLOW_DATA_SIM
            slotBCardProfiles = SimCardProfiles.encode(listOf(SimCardProfile(10,"Zain",31),SimCardProfile(20,"Asia",32)))
        }
        val entity = ProfileManager.createProfile(DataStore.selectedGroupForImport(),bean)
        val activity = instrumentation.startActivitySync(android.content.Intent(context,io.nekohasekai.sagernet.ui.profile.LoadBalanceSettingsActivity::class.java)
            .putExtra("id",entity.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as io.nekohasekai.sagernet.ui.profile.LoadBalanceSettingsActivity
        try {
            val until = android.os.SystemClock.elapsedRealtime()+10000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime()<until) {
                instrumentation.runOnMainSync { ready = activity.findViewById<android.view.View>(R.id.slot_b)?.findViewById<android.widget.Spinner>(R.id.sim_spinner)?.adapter != null }
                if (!ready) android.os.SystemClock.sleep(50)
            }
            assertTrue(ready)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val slot = activity.findViewById<android.view.View>(R.id.slot_b)
                val container = slot.findViewById<android.widget.LinearLayout>(R.id.card_profiles_container)
                assertEquals(android.view.View.VISIBLE,container.visibility)
                assertEquals(3,container.childCount) // help + two saved cards, even while offline
                assertEquals(android.view.View.GONE,slot.findViewById<android.view.View>(R.id.fixed_profile_row).visibility)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"load-balance-cards.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoadBalanceAudit")
            })!!
            resolver.openOutputStream(uri)!!.use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            instrumentation.runOnMainSync {
                val slot = activity.findViewById<android.view.View>(R.id.slot_b)
                slot.findViewById<android.widget.Spinner>(R.id.network_kind_spinner).setSelection(0)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(bean.slotBCardProfiles,DataStore.lbSlotBCardProfiles)
                val slot = activity.findViewById<android.view.View>(R.id.slot_b)
                assertEquals(android.view.View.GONE,slot.findViewById<android.view.View>(R.id.card_profiles_container).visibility)
                assertEquals(android.view.View.VISIBLE,slot.findViewById<android.view.View>(R.id.fixed_profile_row).visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            ProfileManager.deleteProfile2(entity.groupId,entity.id)
        }
    }

    @Test fun cardIdentityAndStoragePreserveInactiveEsimAndLegacyFields() {
        val entries = listOf(SimCardProfile(10, "Zain", 31), SimCardProfile(20, "Asia", 32), SimCardProfile(30, "Zain", 33))
        assertEquals(33L, SimCardProfiles.resolve(entries, 30)!!.profileId)
        assertNull(SimCardProfiles.resolve(entries, 99))
        assertNull(SimCardProfiles.resolve(entries, -1))
        val bean = LoadBalanceBean().apply {
            initializeDefaultValues(); slotANetworkKind = 0; slotAProxyId = 40; slotAWeight = 35
            slotBNetworkKind = 1; slotBSubscriptionId = SimCardProfiles.FOLLOW_DATA_SIM; slotBWeight = 65
            slotBCardProfiles = SimCardProfiles.encode(entries)
        }
        val clone = bean.clone()
        assertEquals(entries, SimCardProfiles.cards(clone, 1))
        assertEquals(35, clone.slotAWeight); assertEquals(65, clone.slotBWeight)
        assertEquals(40L, clone.slotAProxyId)
        assertTrue(SimCardProfiles.followsDataSim(clone, 1)); assertFalse(SimCardProfiles.followsDataSim(clone, 0))
        clone.slotBSubscriptionId = 20
        assertFalse(SimCardProfiles.followsDataSim(clone, 1))
        assertEquals(entries, SimCardProfiles.cards(clone.clone(), 1))
        val bytes = ByteBufferOutput(128).apply {
            writeInt(0); writeInt(0); writeInt(-1); writeLong(11); writeInt(45)
            writeInt(1); writeInt(22); writeLong(12); writeInt(55)
        }.toBytes()
        val legacy = LoadBalanceBean().apply { deserialize(ByteBufferInput(bytes)); initializeDefaultValues() }
        assertEquals(22, legacy.slotBSubscriptionId); assertEquals(12L, legacy.slotBProxyId)
        assertEquals(45, legacy.slotAWeight); assertEquals(55, legacy.slotBWeight)
        assertEquals("[]", legacy.slotBCardProfiles); assertFalse(SimCardProfiles.followsDataSim(legacy, 1))
    }

    @Test fun generatedGroupsKeepWeightsAndDnsQuicModesWithMultipleCards() = runBlocking {
        val group = DataStore.selectedGroupForImport()
        val created = mutableListOf<Long>()
        try {
            suspend fun proxy(): ProxyEntity = ProfileManager.createProfile(group, VMessBean().apply {
                initializeDefaultValues(); alterId = -1; serverAddress = "127.0.0.1"; serverPort = 443
                uuid = "11111111-1111-4111-8111-111111111111"; security = "tls"; sni = "example.com"
            }).also { created.add(it.id) }
            val a = proxy(); val b = proxy()
            val bean = LoadBalanceBean().apply {
                initializeDefaultValues(); slotAProxyId = a.id; slotBProxyId = b.id
                slotANetworkKind = 0; slotBNetworkKind = 1; slotBSubscriptionId = 10
                slotAWeight = 35; slotBWeight = 65
            }
            val entity = ProfileManager.createProfile(group, bean).also { created.add(it.id) }
            val before = JSONObject(buildConfig(entity, forTest = true).config)
            bean.slotBSubscriptionId = SimCardProfiles.FOLLOW_DATA_SIM
            bean.slotBCardProfiles = SimCardProfiles.encode(listOf(SimCardProfile(10,"Zain",a.id), SimCardProfile(20,"Asia",b.id), SimCardProfile(30,"eSIM",b.id)))
            entity.putBean(bean)
            val result = buildConfig(entity, forTest = true)
            val after = JSONObject(result.config)
            fun find(config: JSONObject, tag: String): JSONObject {
                val list = config.getJSONArray("outbounds")
                return (0 until list.length()).map { list.getJSONObject(it) }.single { it.optString("tag") == tag }
            }
            for (tag in listOf("proxy", "dns-proxy", "quic-proxy")) {
                val old = find(before,tag); val current = find(after,tag)
                assertEquals(old.getString("type"),current.getString("type"))
                assertEquals(old.optString("mode"),current.optString("mode"))
                val members = current.getJSONArray("outbounds")
                assertEquals(2,members.length())
                for (slot in 0..1) assertEquals(old.getJSONArray("outbounds").getJSONObject(slot).optLong("weight"), members.getJSONObject(slot).optLong("weight"))
                assertEquals("vload-slot-1",members.getJSONObject(1).getString("outbound"))
            }
            assertEquals(3, result.loadBalanceCardTags[1]!!.size)
            assertEquals(result.loadBalanceCardTags[1]!![20], result.loadBalanceCardTags[1]!![30])
            val outbounds = after.getJSONArray("outbounds")
            val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }
            assertEquals(tags.size,tags.distinct().size)
            assertEquals(before.getJSONObject("route").getString("final"),after.getJSONObject("route").getString("final"))
        } finally { created.reversed().forEach { ProfileManager.deleteProfile2(group,it) } }
    }
}
