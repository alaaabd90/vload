package io.nekohasekai.sagernet.utils

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs

data class SimSlotInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
)

object SimSlots {
    fun hasReadPhoneStatePermission(): Boolean = ContextCompat.checkSelfPermission(
        SagerNet.application, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Active SIM subscriptions usable as a vload network slot. Empty if the
     * permission isn't granted yet or the device has no active SIMs.
     */
    fun listActiveSims(): List<SimSlotInfo> {
        if (!hasReadPhoneStatePermission()) return emptyList()
        return try {
            val subscriptionManager =
                SagerNet.application.getSystemService(SubscriptionManager::class.java)
                    ?: return emptyList()
            val infos = subscriptionManager.activeSubscriptionInfoList ?: return emptyList()
            infos.map {
                SimSlotInfo(
                    subscriptionId = it.subscriptionId,
                    slotIndex = it.simSlotIndex,
                    displayName = (it.displayName?.toString()?.takeIf { name -> name.isNotBlank() }
                        ?: it.carrierName?.toString()
                        ?: "SIM ${it.simSlotIndex + 1}"),
                )
            }
        } catch (e: SecurityException) {
            Logs.w(e)
            emptyList()
        }
    }
}
