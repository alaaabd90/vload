package io.nekohasekai.sagernet.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.nekohasekai.sagernet.BuildConfig
import java.security.*
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object SecurityIdentity {
    const val ALIAS = "vload.recipient.rsa.v3"
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // A cross-process file lock prevents first-launch UI/service key-generation races.
    fun identity(context: Context): KeyStore.PrivateKeyEntry {
        require(Build.VERSION.SDK_INT >= 23) { "Secure vload requires Android 6 or newer" }
        val file = java.io.File(context.noBackupFilesDir, "identity.lock")
        synchronized(this) {
            java.io.RandomAccessFile(file, "rw").use { handle ->
                handle.channel.lock().use {
                    var ks = store()
                    if (!ks.containsAlias(ALIAS)) {
                        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
                        }.generateKeyPair()
                        ks = store()
                    }
                    return ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
                }
            }
        }
    }
    fun recipient(context: Context) = "VLP3:" + encode(identity(context).certificate.publicKey.encoded)
    fun fingerprint(context: Context) = encode(digest(identity(context).certificate.publicKey.encoded))
    fun oaep() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    fun unwrap(context: Context, wrapped: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
        init(Cipher.DECRYPT_MODE, identity(context).privateKey, oaep()); doFinal(wrapped)
    }
    fun trusted(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
            else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!
        context.packageName == BuildConfig.APPLICATION_ID && signatures.size == 1 &&
            digest(signatures[0].toByteArray()).joinToString("") { "%02x".format(it) } ==
            "7caf45ce32d63140c54e76cc9ee3629dbdbef9af6cabdf07aea5f9f89bb723ea"
    }.getOrDefault(false)
}
