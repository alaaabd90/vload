package io.nekohasekai.sagernet.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Deterministic per-device hardware fingerprint, recomputed from device
 * properties rather than a randomly generated and persisted value - so it
 * stays stable across reinstalls of the app on the same physical device (as
 * long as reinstalls keep using the same release signing key: ANDROID_ID is
 * scoped per app-signing-key + user + device since Android 8, which is
 * exactly the stability this needs and why it's the primary input here, not
 * an incidental one). Not tamper-proof (ANDROID_ID is spoofable on rooted
 * devices), just a casual deterrent against using a profile on the wrong
 * phone.
 *
 * Deliberately excludes two things a previous version of this included:
 *
 *  - Build.FINGERPRINT: changes on every OS/security-patch OTA update, not
 *    just on reinstall - directly defeats "stays the same for this phone".
 *  - Build.getSerial(): requires READ_PRIVILEGED_PHONE_STATE on Android 10+,
 *    a signature|privileged permission no third-party app can hold, so it
 *    silently resolves to the literal string "unknown" for every device on
 *    Android 10+ (the large majority of real devices) - dead weight that
 *    also collapses the effective input space for anyone still on Android
 *    8/9 with the permission denied, since "unknown" becomes shared across
 *    every such device instead of contributing real per-device entropy.
 *
 * The Build.* fields folded in below are static per device *model*, not per
 * unit - stable across both reinstalls and OS updates, so they only add a
 * little extra binding specificity without ever being able to change the
 * fingerprint by themselves. ANDROID_ID remains what actually makes it
 * unique per physical device.
 */
object HwidManager {
    fun compute(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: ""
        val input = listOf(
            androidId,
            Build.BOARD,
            Build.BRAND,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(HWID_LENGTH_BYTES).joinToString("") { "%02X".format(it) }
    }

    // 16 bytes -> 32 hex chars, matching LockedProfileCrypto's HWID_BYTES.
    // Keep these two in sync if either changes.
    const val HWID_LENGTH_BYTES = 16
}
