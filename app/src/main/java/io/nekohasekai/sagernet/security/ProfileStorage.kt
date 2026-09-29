package io.nekohasekai.sagernet.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.nekohasekai.sagernet.SagerNet
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** Only Room persistence uses this wrapper. Portable links and native configuration stay unchanged. */
object ProfileStorage {
    private val magic = byteArrayOf(0x56,0x4c,0x44,0x53,1)
    private const val ALIAS = "vload.profile.storage.v1"
    private fun key(): SecretKey {
        val context=SagerNet.application
        synchronized(this) {
            java.io.RandomAccessFile(java.io.File(context.noBackupFilesDir,"profile-storage.lock"),"rw").use { file ->
                file.channel.lock().use {
                    var ks=SecurityIdentity.store()
                    if (!ks.containsAlias(ALIAS)) {
                        KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
                            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                        }.generateKey(); ks=SecurityIdentity.store()
                    }
                    return ks.getKey(ALIAS,null) as SecretKey
                }
            }
        }
    }
    private fun sealed(bytes: ByteArray) = bytes.size>=magic.size && bytes.copyOfRange(0,magic.size).contentEquals(magic)
    @JvmStatic fun seal(bytes: ByteArray?): ByteArray? {
        if(bytes==null || bytes.isEmpty()) return bytes
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE,key()); updateAAD(magic); magic+iv+doFinal(bytes)
        }
    }
    @JvmStatic fun open(bytes: ByteArray?): ByteArray? {
        if(bytes==null || bytes.isEmpty()) return bytes
        // Plaintext is accepted only to migrate pre-security installations.
        if(!sealed(bytes)) return bytes
        require(bytes.size>=33) { "Damaged encrypted profile" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(5,17)))
            updateAAD(magic); doFinal(bytes.copyOfRange(17,bytes.size))
        }
    }
    fun migrate(db: SupportSQLiteDatabase) {
        db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
        db.beginTransaction()
        try {
            for(table in listOf("proxy_entities","proxy_groups")) {
                val columns=mutableListOf<String>()
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while(cursor.moveToNext()) {
                        val name=cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if(name.endsWith("Bean") || (table=="proxy_groups" && name=="subscription")) columns.add(name)
                    }
                }
                for(column in columns) {
                    db.query("SELECT id, `$column` FROM `$table` WHERE `$column` IS NOT NULL").use { cursor ->
                        while(cursor.moveToNext()) {
                            val raw=cursor.getBlob(1)
                            if(raw.isNotEmpty() && !sealed(raw)) db.execSQL("UPDATE `$table` SET `$column`=? WHERE id=?",arrayOf(seal(raw),cursor.getLong(0)))
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
