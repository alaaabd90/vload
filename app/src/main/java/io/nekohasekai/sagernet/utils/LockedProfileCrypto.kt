package io.nekohasekai.sagernet.utils

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts an exported profile config so it only decrypts on the device
 * whose HWID (see [HwidManager]) it was locked to. The HWID doubles as the
 * AES-256 key (SHA-256 of the uppercase hex string), so even bypassing the
 * app's own HWID-match check wouldn't be enough to decrypt the payload -
 * you'd still need the recipient device's own HWID string.
 *
 * Binary format: MAGIC(4) | VERSION(1) | HWID(16 ASCII) | IV(12) | CIPHERTEXT
 */
object LockedProfileCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val HWID_BYTES = 16
    private val MAGIC = byteArrayOf(0x56, 0x4C, 0x44, 0x50) // "VLDP"
    private const val VERSION: Byte = 0x01
    private const val HEADER_SIZE = 4 + 1 + HWID_BYTES + GCM_IV_BYTES // 33

    fun encryptForHwid(plaintext: String, recipientHwid: String): ByteArray {
        val hwid = recipientHwid.uppercase()
        val key = deriveKey(hwid)
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val hwidBytes = hwid.toByteArray(Charsets.US_ASCII)
        return MAGIC + byteArrayOf(VERSION) + hwidBytes + iv + cipherBytes
    }

    fun tryDecrypt(content: ByteArray, deviceHwid: String): DecryptResult {
        if (content.size < HEADER_SIZE || !content.startsWith(MAGIC)) {
            return DecryptResult.NotLocked
        }
        val version = content[4]
        if (version != VERSION) {
            return DecryptResult.Error("Unsupported locked-profile format version $version")
        }
        val lockedTo = String(content, 5, HWID_BYTES, Charsets.US_ASCII).uppercase()
        if (lockedTo != deviceHwid.uppercase()) {
            return DecryptResult.WrongDevice(lockedTo)
        }
        val iv = content.copyOfRange(5 + HWID_BYTES, HEADER_SIZE)
        val cipherBytes = content.copyOfRange(HEADER_SIZE, content.size)
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(deviceHwid.uppercase()), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            DecryptResult.Decrypted(String(cipher.doFinal(cipherBytes), Charsets.UTF_8))
        } catch (e: Exception) {
            DecryptResult.Error(e.message ?: "Decryption failed")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun deriveKey(hwid: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(hwid.toByteArray(Charsets.UTF_8))

    sealed class DecryptResult {
        object NotLocked : DecryptResult()
        data class WrongDevice(val lockedToHwid: String) : DecryptResult()
        data class Decrypted(val plaintext: String) : DecryptResult()
        data class Error(val message: String) : DecryptResult()
    }
}
