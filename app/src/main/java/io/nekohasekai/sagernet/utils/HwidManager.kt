package io.nekohasekai.sagernet.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Deterministic per-device hardware fingerprint, recomputed from device
 * properties rather than a randomly generated and persisted value - so it
 * stays stable across reinstalls of the app on the same physical device.
 * Not tamper-proof (ANDROID_ID is spoofable on rooted devices), just a
 * casual deterrent against using a profile on the wrong phone.
 */
object HwidManager {
    fun compute(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: ""
        val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { Build.getSerial() }.getOrDefault("unknown")
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
        val input = "$androidId|${Build.FINGERPRINT}|$serial"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02X".format(it) }
    }
}
