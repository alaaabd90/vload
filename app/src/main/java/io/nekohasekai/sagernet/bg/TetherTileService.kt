package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.localtether.DevOptionsSettings
import io.nekohasekai.sagernet.localtether.LocalShizukuTether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.service.quicksettings.TileService as BaseTileService

/**
 * One-tap version of the "Local Shizuku tethering" switch in Settings: makes
 * sure Developer Options + Wireless debugging are on first (needed for
 * Shizuku's own daemon - via DevOptionsSettings's direct WRITE_SECURE_SETTINGS
 * path, not Shizuku itself, since that's the one thing that can't depend on
 * Shizuku already being up), then starts LocalShizukuTether - same singleton
 * the Settings toggle drives, so both stay in sync. Only ever enables those
 * prerequisites; it never turns Developer Options back off (that's the
 * separate "Dev" tile's job) since tethering needs them to stay on.
 */
@RequiresApi(24)
class TetherTileService : BaseTileService() {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_hardware_router) }
    private val iconActive by lazy { Icon.createWithResource(this, R.drawable.ic_hardware_router) }

    private var watchJob: Job? = null
    private var actionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var lastErrorShown: String? = null

    override fun onStartListening() {
        super.onStartListening()
        watchJob = LocalShizukuTether.state.onEach { renderTile(it) }.launchIn(scope)
        // Deliberately NOT calling LocalShizukuTether.reconcile() here
        // anymore: Shizuku.pingBinder() (inside it) resolves Shizuku's own
        // ContentProvider, and Android starts whatever process hosts that
        // provider to answer - on this app that's the DEFAULT process,
        // meaning every reconcile() call here forced a full cold start of
        // the heavy default process (native VPN core included) just from
        // opening Quick Settings, even to tap a completely unrelated tile -
        // Android binds every currently-added tile's onStartListening()
        // when the panel opens, not just the one that gets tapped. A
        // possibly-stale icon here is a fair trade against paying that cost
        // on every panel view: toggle() below still reconnects through
        // Shizuku correctly on an actual tap (a deliberate action worth the
        // cost), and TetherSession.start() already cleans up any stale
        // downstream itself, so a wrong "start" decision self-corrects.
    }

    override fun onStopListening() {
        watchJob?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    override fun onDestroy() {
        actionJob?.cancel()
        super.onDestroy()
    }

    private fun renderTile(state: LocalShizukuTether.State) {
        qsTile?.apply {
            when (state) {
                LocalShizukuTether.State.Running -> {
                    icon = iconActive
                    this.state = Tile.STATE_ACTIVE
                }

                LocalShizukuTether.State.Idle -> {
                    icon = iconIdle
                    this.state = Tile.STATE_INACTIVE
                }

                is LocalShizukuTether.State.Error -> {
                    // Most commonly means the hotspot itself didn't actually
                    // release (see LocalShizukuTether.doStop) - shown as
                    // unavailable rather than inactive so it's visibly not
                    // just "off", since the underlying radio may still be on.
                    icon = iconIdle
                    this.state = Tile.STATE_UNAVAILABLE
                    if (lastErrorShown != state.message) {
                        lastErrorShown = state.message
                        Toast.makeText(applicationContext, state.message, Toast.LENGTH_LONG).show()
                    }
                }

                else -> {
                    icon = iconIdle
                    this.state = Tile.STATE_UNAVAILABLE
                }
            }
            label = getString(R.string.tether_tile_title)
            updateTile()
        }
    }

    private fun toggle() {
        if (actionJob?.isActive == true) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(applicationContext, R.string.tether_tile_unsupported_sdk, Toast.LENGTH_LONG).show()
            return
        }

        if (LocalShizukuTether.isRunning()) {
            DataStore.localShizukuTetherEnabled = false
            LocalShizukuTether.stopShared()
            return
        }

        if (!DevOptionsSettings.hasPermission()) {
            Toast.makeText(
                applicationContext,
                getString(R.string.dev_options_tile_needs_permission, packageName),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        actionJob = scope.launch {
            val ready = withContext(Dispatchers.IO) { DevOptionsSettings.enable(enableWirelessDebugging = true) }
            if (!ready) {
                Toast.makeText(applicationContext, R.string.dev_options_tile_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!LocalShizukuTether.isShizukuAvailable()) {
                // Most common cause: the phone was just rebooted. Shizuku's
                // privileged daemon does not survive a reboot on a
                // non-rooted device and nothing - not this app, not
                // Android itself - can restart it automatically; only
                // Shizuku's own app can do that (tapping "Start" there,
                // which reconnects via the wireless debugging pairing that
                // already happened, no re-pairing needed). Opening Shizuku
                // directly here saves a trip to the launcher for that one
                // required tap, rather than just telling the user to go find
                // it themselves.
                openShizukuOrExplain()
                return@launch
            }

            DataStore.localShizukuTetherEnabled = true
            LocalShizukuTether.startShared()
        }
    }

    private fun openShizukuOrExplain() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent == null) {
            Toast.makeText(applicationContext, R.string.local_shizuku_tether_not_installed, Toast.LENGTH_LONG).show()
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(launchIntent)
            }
        }.onFailure {
            Toast.makeText(applicationContext, R.string.local_shizuku_tether_not_installed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
