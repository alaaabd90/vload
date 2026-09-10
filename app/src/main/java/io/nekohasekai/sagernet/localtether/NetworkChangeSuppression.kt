package io.nekohasekai.sagernet.localtether

/**
 * Restarting the hotspot as part of starting a tethering session (see
 * TetherSession.restartDownstream) can, on some devices, briefly disrupt the
 * same Wi-Fi radio's regular client (STA) connection too - confirmed live on
 * the Honor test device: Android hands out a "new" default Network object a
 * moment later, even though nothing about the actual network (SSID,
 * upstream) changed. vload's own existing "reset outbound connections when
 * network changes" safety net (BaseService.kt, VpnService.kt) can't tell
 * that apart from a genuine handover and reacts exactly as designed: a
 * connection reset - which is real but pointless disruption to vload's main
 * VPN traffic when it happens for this reason instead of an actual network
 * change.
 *
 * This gives those checks a short, explicit, self-expiring window to skip
 * during precisely the moment this feature knows it's about to cause that
 * itself - it does not touch or weaken the reset for any genuine network
 * change, which is everything outside this window.
 */
object NetworkChangeSuppression {

    @Volatile
    private var suppressUntilMs: Long = 0

    fun suppressBriefly(durationMs: Long) {
        suppressUntilMs = System.currentTimeMillis() + durationMs
    }

    val isActive: Boolean get() = System.currentTimeMillis() < suppressUntilMs
}
