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
 * Binary format: MAGIC(4) | VERSION(1) | HWID(32 ASCII) | IV(12) | CIPHERTEXT
 *
 * VERSION 2: HwidManager moved from an 8-byte (16 hex char) digest built
 * from ANDROID_ID + FINGERPRINT + serial to a 16-byte (32 hex char) one
 * built from ANDROID_ID + static device fields only (FINGERPRINT changes on
 * every OS update, which broke "stays the same for this phone" on its own).
 * Both the length and the underlying HWID value changed, so a file locked
 * under VERSION 1 can never match any device's newly-computed HWID anyway -
 * tryDecrypt below still recognizes VERSION 1 explicitly rather than trying
 * to parse it against the new fixed field width, so it fails with a clear
 * "needs to be re-exported" message instead of misreading the header.
 */
object LockedProfileCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    // Public so UI input validation (the "lock for another device" HWID
    // entry field) can check against the same length instead of a
    // hand-copied magic number that could drift out of sync with this.
    const val HWID_BYTES = 32
    private val MAGIC = byteArrayOf(0x56, 0x4C, 0x44, 0x50) // "VLDP"
    private const val VERSION: Byte = 0x02
    private const val VERSION_1_LEGACY: Byte = 0x01
    private const val HEADER_SIZE = 4 + 1 + HWID_BYTES + GCM_IV_BYTES // 49

    fun encryptForHwid(plaintext: String, recipientHwid: String): ByteArray {
        val hwid = recipientHwid.uppercase()
        require(hwid.length == HWID_BYTES) { "HWID must be $HWID_BYTES characters, got ${hwid.length}" }
        val key = deriveKey(hwid)
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val hwidBytes = hwid.toByteArray(Charsets.US_ASCII)
        return MAGIC + byteArrayOf(VERSION) + hwidBytes + iv + cipherBytes
    }

    fun tryDecrypt(content: ByteArray, deviceHwid: String): DecryptResult {
        if (content.size < 5 || !content.startsWith(MAGIC)) {
            return DecryptResult.NotLocked
        }
        val version = content[4]
        if (version == VERSION_1_LEGACY) {
            return DecryptResult.Error(
                "This profile was locked with an older version of the app and can't be " +
                    "matched against this device's current HWID. Ask the sender to " +
                    "re-export and re-lock it."
            )
        }
        if (version != VERSION || content.size < HEADER_SIZE) {
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
