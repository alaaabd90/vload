package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.security.*
import io.nekohasekai.sagernet.utils.LockedProfileCrypto as Crypto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun emitRequest() {
        val result=android.os.Bundle().apply { putString("request",Activation.request(context)) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,result)
    }
    @Test fun acceptOwnerToken() {
        val token=InstrumentationRegistry.getArguments().getString("activationToken") ?: return
        assertTrue(Activation.accept(context,token)); assertTrue(Activation.isActive(context))
        assertFalse(Activation.accept(context,token.dropLast(8)+"AAAAAAAA"))
        assertFalse(Activation.accept(context,token.replace("VLA1.","VLA2.")))
        assertFalse(Activation.accept(context,"VLA1.invalid.0.invalid"))
        assertTrue(Activation.isActive(context))
    }
    @Test fun secureExportsRejectTamperingWrongRecipientAndLegacy() {
        val key=Crypto.recipientKey(); val text="synthetic-secret-never-a-real-profile"
        val encrypted=Crypto.encryptForHwid(text,key)
        assertFalse(String(encrypted).contains(text))
        assertEquals(text,(Crypto.tryDecrypt(encrypted,"") as Crypto.DecryptResult.Decrypted).plaintext)
        assertFalse(encrypted.contentEquals(Crypto.encryptForHwid(text,key)))
        for(index in listOf(37,292,293,304,305,encrypted.lastIndex)) {
            val corrupt=encrypted.clone();corrupt[index]=(corrupt[index].toInt() xor 1).toByte()
            assertTrue(Crypto.tryDecrypt(corrupt,"") is Crypto.DecryptResult.Error)
        }
        assertTrue(Crypto.tryDecrypt(encrypted.copyOf(25),"") is Crypto.DecryptResult.Error)
        assertTrue(Crypto.tryDecrypt(byteArrayOf(0x56,0x4c,0x44,0x50,2),"") is Crypto.DecryptResult.Error)
        assertFalse(Crypto.validRecipient("0123456789ABCDEF0123456789ABCDEF"))
        val other=java.security.KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val wrong=Crypto.encryptForHwid(text,"VLP3:"+SecurityIdentity.encode(other.public.encoded))
        assertTrue(Crypto.tryDecrypt(wrong,"") is Crypto.DecryptResult.WrongDevice)
    }
    @Test fun releaseUpdatesNeverOfferOlderVersions() {
        val version=io.nekohasekai.sagernet.utils.ReleaseVersion
        assertFalse(version.isNewer("v1.4.41","1.4.42-security-local"))
        assertFalse(version.isNewer("v1.4.42","1.4.42"))
        assertTrue(version.isNewer("v1.4.43","1.4.42-security-local"))
        assertTrue(version.isNewer("v1.4.100","1.4.99"))
        assertTrue(version.isNewer("pre-1.5.0","1.4.42"))
        assertFalse(version.isNewer("error","1.4.42"))
    }
    @Test fun legacyHwidCompatibility() {
        val hwid="0123456789ABCDEF0123456789ABCDEF"
        val text="synthetic-hwid-profile"
        val sealed=Crypto.encryptLegacyHwid(text,hwid.lowercase())
        assertEquals(2,sealed[4].toInt())
        assertEquals(text,(Crypto.tryDecrypt(sealed,hwid) as Crypto.DecryptResult.Decrypted).plaintext)
        assertTrue(Crypto.tryDecrypt(sealed,"F".repeat(32)) is Crypto.DecryptResult.WrongDevice)
        // Independent reconstruction of the prior v2 format verifies backward compatibility.
        val iv=ByteArray(12){it.toByte()}
        val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(hwid.toByteArray()),"AES"),javax.crypto.spec.GCMParameterSpec(128,iv))
        val old=byteArrayOf(0x56,0x4c,0x44,0x50,2)+hwid.toByteArray()+iv+cipher.doFinal(text.toByteArray())
        assertEquals(text,(Crypto.tryDecrypt(old,hwid) as Crypto.DecryptResult.Decrypted).plaintext)
        sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte()
        assertTrue(Crypto.tryDecrypt(sealed,hwid) is Crypto.DecryptResult.Error)
        assertFalse(Crypto.validHwid("invalid"))
        assertTrue(Crypto.tryDecrypt(old.copyOf(49),hwid) is Crypto.DecryptResult.Error)
    }
    @Test fun activationSurvivesUpdate() { assertTrue(Activation.isActive(context)) }
    @Test fun databaseMigrationAndExportPolicy() {
        val bean=io.nekohasekai.sagernet.fmt.v2ray.VMessBean().apply {
            initializeDefaultValues(); name="security-synthetic"; serverAddress="fixture.invalid"; serverPort=443
            uuid="11111111-1111-1111-1111-111111111111"; type="vless"
        }
        val dao=io.nekohasekai.sagernet.database.SagerDatabase.proxyDao
        val profile=io.nekohasekai.sagernet.database.ProxyEntity().putBean(bean).apply { lockedImport=true }
        val id=dao.addProxy(profile)
        try {
            val db=io.nekohasekai.sagernet.database.SagerDatabase.instance.openHelper.writableDatabase
            fun raw(): ByteArray = db.query("SELECT vmessBean FROM proxy_entities WHERE id=?",arrayOf(id)).use { it.moveToFirst(); it.getBlob(0) }
            assertFalse(String(raw()).contains("fixture.invalid"))
            assertEquals(bean.serverAddress,dao.getById(id)!!.requireBean().serverAddress)
            val legacy=io.nekohasekai.sagernet.fmt.KryoConverters.serialize(bean)
            db.execSQL("UPDATE proxy_entities SET vmessBean=? WHERE id=?",arrayOf(legacy,id))
            ProfileStorage.migrate(db)
            assertFalse(raw().contentEquals(legacy))
            assertEquals(bean.serverAddress,dao.getById(id)!!.requireBean().serverAddress)
            assertTrue(dao.getById(id)!!.lockedImport)
            assertFalse(ExportPolicy.allowed(dao.getById(id)!!))
            val chain=io.nekohasekai.sagernet.fmt.internal.ChainBean().apply { initializeDefaultValues(); proxies=listOf(id) }
            assertFalse(ExportPolicy.allowed(io.nekohasekai.sagernet.database.ProxyEntity().putBean(chain)))
            try { dao.getById(id)!!.toStdLink(); fail("Locked profile was exported") } catch(_:IllegalStateException) {}
        } finally { dao.deleteById(id) }
    }
    @Test fun databasePayloadIsEncryptedAndAuthenticated() {
        val bytes="synthetic-db-credential".toByteArray()
        val sealed=ProfileStorage.seal(bytes)!!
        assertFalse(String(sealed).contains("synthetic-db-credential"))
        assertArrayEquals(bytes,ProfileStorage.open(sealed))
        assertArrayEquals(bytes,ProfileStorage.open(bytes)) // legacy upgrade input
        sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte()
        try{ProfileStorage.open(sealed);fail("Accepted tampered ciphertext")}catch(_:java.security.GeneralSecurityException){}
    }
}
