package io.nekohasekai.sagernet.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.localtether.DevOptionsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.service.quicksettings.TileService as BaseTileService

/**
 * TogglDev-equivalent "Dev" tile: snapshots Developer Options + a handful of
 * related Settings.Global/Secure/System keys, flips Developer Options on,
 * and on a second tap restores every captured value exactly as it was -
 * implemented clean-room against the Settings provider (see
 * DevOptionsSettings), not by copying TogglDev itself (its GitHub repo ships
 * only a compiled APK, no source, no license) - though the *mechanism* is
 * the same one TogglDev's own README documents: WRITE_SECURE_SETTINGS
 * granted once via adb, then plain Settings provider calls from then on.
 *
 * The tile's icon follows DevOptionsSettings.isOnFlow - a live subscription,
 * not a one-shot read - so it updates immediately if Developer Options gets
 * turned on/off by anything else (the Tether tile's prerequisite step, or
 * the real Android Settings screen) while this tile is already visible in
 * the same Quick Settings panel, not just in response to its own taps.
 */
@RequiresApi(24)
class DevOptionsTileService : BaseTileService() {
    private val iconOff by lazy { Icon.createWithResource(this, R.drawable.ic_device_developer_mode) }
    private val iconOn by lazy { Icon.createWithResource(this, R.drawable.ic_device_developer_mode) }

    private var watchJob: Job? = null
    private var actionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        watchJob = DevOptionsSettings.isOnFlow.onEach { renderTile(it) }.launchIn(scope)
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
        watchJob?.cancel()
        super.onDestroy()
    }

    private fun renderTile(active: Boolean) {
        qsTile?.apply {
            icon = if (active) iconOn else iconOff
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.dev_options_tile_title)
            updateTile()
        }
    }

    private fun toggle() {
        if (actionJob?.isActive == true) return

        if (!DevOptionsSettings.hasPermission()) {
            Toast.makeText(
                applicationContext,
                getString(R.string.dev_options_tile_needs_permission, SagerNet.application.packageName),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        actionJob = scope.launch {
            // No transient STATE_UNAVAILABLE flash before this: the actual
            // write is a handful of Settings-provider calls, typically done
            // in well under 100ms - flashing grey first just made the tile
            // visibly flicker (grey, then blue) for an operation too fast to
            // need an "in progress" indicator at all.
            val active = DevOptionsSettings.isOn()
            val ok = withContext(Dispatchers.IO) {
                if (active) {
                    DevOptionsSettings.disable()
                } else {
                    DevOptionsSettings.enable(enableWirelessDebugging = true)
                }
            }

            if (!ok) {
                Toast.makeText(applicationContext, R.string.dev_options_tile_failed, Toast.LENGTH_LONG).show()
            }

            // isOnFlow's own watcher above already re-renders on a genuine
            // change; this covers the "write failed, nothing changed" case,
            // where the flow wouldn't otherwise emit and the tile would be
            // stuck showing the transient STATE_UNAVAILABLE set above.
            renderTile(DevOptionsSettings.isOn())
        }
    }
}
