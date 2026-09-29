package io.nekohasekai.sagernet.utils

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.Executor

/** Session-scoped; observe the actual data SIM, including opportunistic switches. */
class ActiveDataSimMonitor(private val changed: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var subscriptions: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var phone: PhoneStateListener? = null
    private var callback: Any? = null
    private val app get() = SagerNet.application

    fun start() { handler.post {
        if (stopped || Build.VERSION.SDK_INT < 30) return@post
        val manager = app.getSystemService(SubscriptionManager::class.java)
        val telephony = app.getSystemService(TelephonyManager::class.java)
        fun notifyCurrent() {
            if (!stopped) changed(if (SimSlots.hasReadPhoneStatePermission()) SubscriptionManager.getActiveDataSubscriptionId() else -1)
        }
        try {
            val executor = Executor { handler.post(it) }
            subscriptions = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() = notifyCurrent()
            }.also { manager?.addOnSubscriptionsChangedListener(executor, it) }
            if (Build.VERSION.SDK_INT >= 31) {
                callback = DataCallback { if (!stopped) changed(it) }.also {
                    telephony?.registerTelephonyCallback(executor, it)
                }
            } else {
                @Suppress("DEPRECATION")
                phone = object : PhoneStateListener() {
                    override fun onActiveDataSubscriptionIdChanged(subId: Int) { if (!stopped) changed(subId) }
                }.also { telephony?.listen(it, PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE) }
            }
            notifyCurrent()
        } catch (e: Exception) { Logs.w(e); if (!stopped) changed(-1) }
    } }

    fun stop() { handler.post {
        stopped = true
        try { subscriptions?.let { app.getSystemService(SubscriptionManager::class.java)?.removeOnSubscriptionsChangedListener(it) } } catch (e: Exception) { Logs.w(e) }
        val telephony = app.getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) (callback as? TelephonyCallback)?.let { telephony?.unregisterTelephonyCallback(it) }
            @Suppress("DEPRECATION")
            phone?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
        } catch (e: Exception) { Logs.w(e) }
        subscriptions = null; callback = null; phone = null
    } }

    @androidx.annotation.RequiresApi(31)
    private class DataCallback(val changed: (Int) -> Unit) : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
        override fun onActiveDataSubscriptionIdChanged(subId: Int) = changed(subId)
    }
}
