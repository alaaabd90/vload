package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.utils.HwidManager
import io.nekohasekai.sagernet.utils.LockedProfileCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneProfileRoundTripTest {
    @Test fun savedPhoneProfileSurvivesNormalAndLockedFiles() = runBlocking {
        // Keep the desugared API used by Room in the release target process.
        assertTrue(java.util.Collections.synchronizedMap(mutableMapOf<String, String>()).isEmpty())
        val name = InstrumentationRegistry.getArguments().getString("profileName")
        assumeTrue("Explicit phone profile argument required", name != null)
        val source = SagerDatabase.proxyDao.getAll().single { it.requireBean().name == name }
        val original = source.requireBean() as StandardV2RayBean
        val selected = DataStore.selectedProxy
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hwid = LockedProfileCrypto.recipientKey()
        var checked = 0
        for (enabled in listOf(true, false)) for (locked in listOf(false, true)) {
            val bean = original.clone() as StandardV2RayBean
            bean.snispoofEnabled = enabled
            val link = ProxyEntity().putBean(bean).toStdLink()
            val file = java.io.File(context.cacheDir, "phone-roundtrip-check.vload")
            var temporaryId = 0L
            try {
                file.writeBytes(if (locked) LockedProfileCrypto.encryptForHwid(link, hwid) else link.toByteArray(Charsets.UTF_8))
                val raw = file.readBytes()
                val plaintext = if (locked) (LockedProfileCrypto.tryDecrypt(raw, hwid) as LockedProfileCrypto.DecryptResult.Decrypted).plaintext else String(raw, Charsets.UTF_8)
                val imported = RawUpdater.parseRaw(plaintext, file.name)!!.single()
                val entity = ProfileManager.createProfile(source.groupId, imported)
                temporaryId = entity.id
                entity.lockedImport = locked
                ProfileManager.updateProfile(entity)
                val restored = SagerDatabase.proxyDao.getById(entity.id)!!
                val actual = restored.requireBean() as StandardV2RayBean
                assertEquals(bean.snispoofSettings, actual.snispoofSettings)
                assertEquals(enabled, actual.snispoofEnabled)
                assertEquals(locked, restored.lockedImport)
                assertEquals(link, restored.toStdLink())
                checked++
            } finally {
                if (temporaryId != 0L) ProfileManager.deleteProfile2(source.groupId, temporaryId)
                file.delete()
            }
        }
        assertEquals(4, checked)
        assertEquals(selected, DataStore.selectedProxy)
        assertEquals(original.snispoofSettings, (SagerDatabase.proxyDao.getById(source.id)!!.requireBean() as StandardV2RayBean).snispoofSettings)
    }
}
