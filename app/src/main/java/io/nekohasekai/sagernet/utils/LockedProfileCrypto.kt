package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.security.SecurityIdentity as Identity
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** V3: magic/version | recipient SHA256(32) | RSA-OAEP wrapped AES key(256) | nonce(12) | AES-GCM.
 * The complete header is authenticated. No HWID-derived or APK-embedded decryption secret.
 * This protects exported files, not plaintext in a compromised authorized runtime.
 */
object LockedProfileCrypto {
    const val HWID_BYTES = 32 // Legacy UI/device identifiers only, never cryptographic keys.
    private val magic = byteArrayOf(0x56,0x4c,0x44,0x50)
    private const val HEADER = 5 + 32 + 256 + 12
    fun recipientKey(): String = Identity.recipient(SagerNet.application)
    fun validRecipient(recipient: String): Boolean = runCatching { publicKey(recipient); true }.getOrDefault(false)
    private fun publicKey(recipient: String): RSAPublicKey {
        require(recipient.startsWith("VLP3:") && recipient.length < 1024) { "Paste the recipient's Profile recipient key from About" }
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Identity.decode(recipient.substring(5)))) as RSAPublicKey
        require(key.modulus.bitLength() == 2048 && key.publicExponent == java.math.BigInteger.valueOf(65537))
        return key
    }
    fun encryptForHwid(plaintext: String, recipientHwid: String): ByteArray {
        val recipient = publicKey(recipientHwid)
        require(plaintext.toByteArray().size <= 8 * 1024 * 1024)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
                init(Cipher.ENCRYPT_MODE, recipient, Identity.oaep()); doFinal(key)
            }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val header = magic + byteArrayOf(3) + Identity.digest(recipient.encoded) + wrapped + iv
            return header + Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,iv))
                updateAAD(header); doFinal(plaintext.toByteArray(Charsets.UTF_8))
            }
        } finally { key.fill(0) }
    }
    // Compatibility only: the header exposes the HWID, so this is not strong secrecy.
    fun validHwid(hwid: String) = hwid.matches(Regex("[0-9a-fA-F]{32}"))
    fun encryptLegacyHwid(plaintext: String, recipientHwid: String): ByteArray {
        val hwid = recipientHwid.trim().uppercase(java.util.Locale.ROOT)
        require(validHwid(hwid)) { "Enter the 32-character Device HWID" }
        require(plaintext.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = Identity.digest(hwid.toByteArray(Charsets.US_ASCII))
        return try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,iv))
                doFinal(plaintext.toByteArray(Charsets.UTF_8))
            }
            magic + byteArrayOf(2) + hwid.toByteArray(Charsets.US_ASCII) + iv + ciphertext
        } finally { key.fill(0) }
    }
    private fun decryptLegacyHwid(content: ByteArray, deviceHwid: String): DecryptResult {
        if (content.size < 65 || content.size > 8 * 1024 * 1024 + 65)
            return DecryptResult.Error("Invalid HWID-locked profile")
        val lockedTo = String(content,5,32,Charsets.US_ASCII).uppercase(java.util.Locale.ROOT)
        if (!validHwid(lockedTo)) return DecryptResult.Error("Invalid HWID")
        if (lockedTo != deviceHwid.uppercase(java.util.Locale.ROOT)) return DecryptResult.WrongDevice(lockedTo)
        val key = Identity.digest(lockedTo.toByteArray(Charsets.US_ASCII))
        return try {
            val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,content.copyOfRange(37,49)))
                doFinal(content.copyOfRange(49,content.size))
            }
            try { DecryptResult.Decrypted(String(clear,Charsets.UTF_8)) } finally { clear.fill(0) }
        } catch (_: Exception) { DecryptResult.Error("Damaged HWID-locked profile") }
        finally { key.fill(0) }
    }

    fun tryDecrypt(content: ByteArray, deviceHwid: String): DecryptResult {
        if (content.size < 4 || !content.copyOfRange(0,4).contentEquals(magic)) return DecryptResult.NotLocked
        if (content.size < 5) return DecryptResult.Error("Truncated locked profile")
        if (content[4].toInt() == 1) return DecryptResult.Error("This older HWID format requires re-export with the current Device HWID.")
        if (content[4].toInt() == 2) return decryptLegacyHwid(content, deviceHwid)
        if (content[4].toInt() != 3 || content.size < HEADER + 16 || content.size > 8*1024*1024 + HEADER + 16) return DecryptResult.Error("Invalid locked-profile format")
        return try {
            val context = SagerNet.application
            if (!Identity.trusted(context)) return DecryptResult.Error("Unrecognized application signature")
            val fingerprint = content.copyOfRange(5,37)
            if (!MessageDigest.isEqual(fingerprint, Identity.digest(Identity.identity(context).certificate.publicKey.encoded)))
                return DecryptResult.WrongDevice(Identity.encode(fingerprint))
            val key = Identity.unwrap(context, content.copyOfRange(37,293))
            try {
                require(key.size == 32)
                val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,content.copyOfRange(293,HEADER)))
                    updateAAD(content.copyOfRange(0,HEADER)); doFinal(content.copyOfRange(HEADER,content.size))
                }
                DecryptResult.Decrypted(String(clear,Charsets.UTF_8)).also { clear.fill(0) }
            } finally { key.fill(0) }
        } catch (_: Exception) { DecryptResult.Error("The locked profile is damaged or its device key is unavailable") }
    }
    sealed class DecryptResult {
        object NotLocked : DecryptResult()
        data class WrongDevice(val lockedToHwid: String) : DecryptResult()
        data class Decrypted(val plaintext: String) : DecryptResult()
        data class Error(val message: String) : DecryptResult()
    }
}
