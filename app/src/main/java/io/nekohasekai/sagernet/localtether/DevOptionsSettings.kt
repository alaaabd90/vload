package io.nekohasekai.sagernet.localtether

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Direct Settings.Global/Secure/System snapshot, restore, and one-shot writes
 * for the "Dev" tile and the "VLoad Tether" tile's dev-options/wireless-
 * debugging prerequisite step - same mechanism TogglDev itself documents
 * (https://github.com/dpkay-io/toggldev's README: grant WRITE_SECURE_SETTINGS
 * once via adb, then the app just calls the Settings provider directly).
 *
 * This intentionally does NOT go through Shizuku: an earlier version bound a
 * privileged helper via Shizuku.bindUserService for every call, mirroring how
 * local tethering (see LocalShizukuTether) works - but on-device testing
 * showed that path leaving orphaned helper processes behind without ever
 * completing (Honor's Magic OS also redacts logcat for third-party
 * processes, so the exact failure inside the helper couldn't be pinned down).
 * Reading/writing Settings.Global/Secure/System from vload's own process with
 * WRITE_SECURE_SETTINGS granted directly is simpler, has one less moving
 * part (no separate app that must already be running), and is exactly the
 * documented, proven mechanism TogglDev itself uses.
 *
 * One-time setup, same shape as TogglDev's own README:
 *   adb shell pm grant <applicationId> android.permission.WRITE_SECURE_SETTINGS
 *
 * TRACKED_SETTINGS was built by walking every entry on a real device's
 * Developer Options screen (Honor/Magic OS) and cross-checking each against
 * `adb shell settings list global|secure|system` plus the public
 * android.provider.Settings SDK surface - not guessed from memory. Toggles
 * left out are, deliberately:
 *  - action buttons / non-persisted state (bug report, revoke USB auth,
 *    reset shortcut manager counters)
 *  - pickers whose value isn't a simple key (mock location app via AppOps,
 *    logger buffer size, GPU renderer, simulate color space, smallest width,
 *    USB configuration, background process limit, Bluetooth audio
 *    codec/profile settings) - restoring these wrong risks breaking
 *    connectivity/rendering rather than just "not covering" them
 *  - OEM-only toggles (Charging temperature limit, Power saving mode,
 *    Automatic/Local updates, Increase readability under sunlight,
 *    Full-brightness DC-like dimming, Adaptive dimming in gaming) - checked
 *    against all three Settings tables on-device and none of them resolve to
 *    a plain row (Honor stores these elsewhere), so a guessed key would
 *    silently no-op
 *  - GPU/drawing debug flags with no confirmed public Settings key on this
 *    Android version (Force 4x MSAA, Disable HW overlays, Show GPU view
 *    updates, Show hardware layer updates, Debug GPU overdraw, Show surface
 *    updates, Show layout bounds, Strict mode, Disable child process
 *    restrictions) - none appear as a row even though several are visibly
 *    off-by-default like others that DO appear, so their backing mechanism
 *    on this OS isn't the plain Settings provider either
 */
object DevOptionsSettings {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        SagerNet.application,
        Manifest.permission.WRITE_SECURE_SETTINGS,
    ) == PackageManager.PERMISSION_GRANTED

    private val resolver: ContentResolver get() = SagerNet.application.contentResolver

    /**
     * Ground truth, always - never a separately-tracked flag. Earlier the
     * Dev tile kept its own "am I on" boolean (DataStore.devOptionsTileActive)
     * that only it ever wrote, so turning Developer Options on via the
     * Tether tile's prerequisite step (or any other path) left the Dev tile
     * showing "off" even though Developer Options genuinely was on. Every
     * caller - both tiles - now asks this instead of keeping their own copy.
     */
    fun isOn(): Boolean = DEVELOPMENT_SETTINGS_ENABLED.read(resolver) == "1"
    fun isWirelessDebuggingOn(): Boolean = ADB_WIFI_ENABLED.read(resolver) == "1"

    /**
     * Idempotent, and the single entry point both tiles use to turn
     * Developer Options on: if it's already on (e.g. the other tile turned
     * it on, or the user did from the real Settings screen), this only tops
     * up Wireless debugging if asked and leaves any existing snapshot alone
     * - re-snapshotting an already-on state would capture "on" as if it were
     * the original value, so a later disable() would restore to "on"
     * instead of whatever it truly was before anything touched it. Only a
     * genuine off-to-on transition takes a fresh snapshot.
     */
    fun enable(enableWirelessDebugging: Boolean): Boolean {
        if (isOn()) {
            return !enableWirelessDebugging || safeWrite(ADB_WIFI_ENABLED, "1")
        }

        val snapshot = JSONObject()
        for (setting in TRACKED_SETTINGS) {
            runCatching { setting.read(resolver) }
                .getOrNull()
                ?.let { snapshot.put(setting.snapshotKey, it) }
        }
        DataStore.devOptionsSnapshot = snapshot.toString()

        val devOk = safeWrite(DEVELOPMENT_SETTINGS_ENABLED, "1")
        safeWrite(ADB_ENABLED, "1")
        if (enableWirelessDebugging) safeWrite(ADB_WIFI_ENABLED, "1")
        _isOnFlow.value = isOn()
        return devOk
    }

    /**
     * The single entry point both tiles use to turn Developer Options back
     * off: restores every tracked setting independently from whatever
     * snapshot enable() last recorded - one key throwing (e.g. a
     * Settings.System row, which needs the separate WRITE_SETTINGS/"Modify
     * system settings" grant rather than WRITE_SECURE_SETTINGS, when called
     * from a normal app process instead of a shell-UID one) must never abort
     * the rest. It previously did: restore() wrapped the whole loop in one
     * runCatching, so the first System-namespace write threw, the function
     * returned false, and the tile's "active" flag never flipped back -
     * every later tap still showed "on" forever, with
     * development_settings_enabled genuinely never restored either.
     *
     * If there's no snapshot at all (Developer Options was turned on by
     * something other than enable() - e.g. the user flipped it on directly
     * in the real Settings screen before ever using either tile), there's
     * nothing sensible to restore other than the master switch itself, so
     * just that gets turned off.
     *
     * Returns true unless a setting outside BEST_EFFORT_SETTINGS failed -
     * those are best-effort since most devices won't have that separate
     * permission granted, and failing to flip Show touches/Pointer location
     * back shouldn't block turning Developer Options itself off.
     */
    fun disable(): Boolean {
        if (!isOn()) return true

        val snapshot = runCatching { JSONObject(DataStore.devOptionsSnapshot) }.getOrNull()

        // The master switch is always forced off directly here, never taken
        // from the snapshot - "disable" calling this tile's whole purpose is
        // unambiguous regardless of what the snapshot says. A stale/bad
        // snapshot recorded from an earlier build (before enable() guarded
        // against re-snapshotting an already-on state) could otherwise
        // "restore" this back to on forever: every disable() call would
        // faithfully put it right back the way a corrupted snapshot said it
        // "originally" was, which is exactly what happened before this
        // safeguard existed.
        var criticalFailure = !safeWrite(DEVELOPMENT_SETTINGS_ENABLED, null)

        if (snapshot != null) {
            for (setting in TRACKED_SETTINGS) {
                if (setting === DEVELOPMENT_SETTINGS_ENABLED) continue
                val value = if (snapshot.has(setting.snapshotKey)) {
                    snapshot.getString(setting.snapshotKey)
                } else {
                    null
                }
                if (!safeWrite(setting, value) && setting !in BEST_EFFORT_SETTINGS) {
                    criticalFailure = true
                }
            }
        }
        _isOnFlow.value = isOn()
        return !criticalFailure
    }

    /** Never throws; returns whether the write actually went through. */
    private fun safeWrite(setting: TrackedSetting, value: String?): Boolean =
        runCatching { setting.write(resolver, value) }.isSuccess

    private enum class Namespace { GLOBAL, SECURE, SYSTEM }

    private class TrackedSetting(private val namespace: Namespace, private val key: String) {
        // Namespaced so e.g. a future Secure and Global key of the same
        // literal name can't collide inside one snapshot JSON object.
        val snapshotKey get() = "${namespace.name.lowercase()}:$key"

        fun read(resolver: ContentResolver): String? = when (namespace) {
            Namespace.GLOBAL -> Settings.Global.getString(resolver, key)
            Namespace.SECURE -> Settings.Secure.getString(resolver, key)
            Namespace.SYSTEM -> Settings.System.getString(resolver, key)
        }

        fun write(resolver: ContentResolver, value: String?) {
            when (namespace) {
                Namespace.GLOBAL -> Settings.Global.putString(resolver, key, value)
                Namespace.SECURE -> Settings.Secure.putString(resolver, key, value)
                Namespace.SYSTEM -> Settings.System.putString(resolver, key, value)
            }
        }
    }

    private fun global(key: String) = TrackedSetting(Namespace.GLOBAL, key)
    private fun secure(key: String) = TrackedSetting(Namespace.SECURE, key)
    private fun system(key: String) = TrackedSetting(Namespace.SYSTEM, key)

    private val DEVELOPMENT_SETTINGS_ENABLED = global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
    private val ADB_ENABLED = global(Settings.Global.ADB_ENABLED)

    // Settings.Global.ADB_WIFI_ENABLED is only present as an SDK constant
    // from API 30; the key name itself is stable since it shipped (confirmed
    // present as "adb_wifi_enabled" via `settings list global` on-device),
    // so it's used as a literal to keep this class usable at any compileSdk
    // without an SDK-version guard on every access.
    private val ADB_WIFI_ENABLED = global("adb_wifi_enabled")

    private val SHOW_TOUCHES = system("show_touches") // confirmed present, =0
    private val POINTER_LOCATION = system("pointer_location") // confirmed present, =0

    private val TRACKED_SETTINGS = listOf(
        DEVELOPMENT_SETTINGS_ENABLED,
        ADB_ENABLED,
        ADB_WIFI_ENABLED,

        // DRAWING - all confirmed non-default (0.5x) on-device.
        global(Settings.Global.WINDOW_ANIMATION_SCALE),
        global(Settings.Global.TRANSITION_ANIMATION_SCALE),
        global(Settings.Global.ANIMATOR_DURATION_SCALE),
        // Real row name has a literal dot; confirmed via `settings list global`.
        global("debug.force_rtl"),

        // APPS
        // Below this point, the SDK's Settings.Global/System classes don't
        // expose these as public constants (compiling against them fails
        // with "Unresolved reference" even though the rows are real) - every
        // one of these literals is either confirmed present via `adb shell
        // settings list global|secure|system` on the test device, or is a
        // long-standing, stable AOSP key name (noted below) for a row that's
        // simply unset (device is at its default, so no row exists yet to
        // confirm against).
        global(Settings.Global.ALWAYS_FINISH_ACTIVITIES),
        global("force_resizable_activities"), // confirmed present, =1 (on)
        global("force_allow_on_external"), // stable AOSP name, currently unset
        secure("anr_show_background"),

        // DEBUGGING
        global("debug_view_attributes"), // confirmed present, =0
        global("verifier_verify_adb_installs"), // confirmed present, =0
        global(Settings.Global.WAIT_FOR_DEBUGGER),
        global(Settings.Global.DEBUG_APP),
        // Confirmed via device dump: matches "Always prompt when
        // connecting to USB" (name and value both line up).
        secure("usb_conn_prompt"),
        global("webview_multiprocess"), // stable AOSP name, currently unset

        // NETWORKING - both directly relevant to local Shizuku tethering.
        global("mobile_data_always_on"), // confirmed present, =1 (on)
        // 0 = enabled, matching "Hardware accelerated tethering" = on.
        global("tether_offload_disabled"),

        // INPUT
        SHOW_TOUCHES,
        POINTER_LOCATION,

        // Misc toggles with a confirmed public Settings.Secure key.
        secure("bluetooth_hci_log"),
        secure("notification_bubbles"),
        global("disable_screen_share_protections_for_apps_and_notifications"),
    )

    // Settings.System writes need android.permission.WRITE_SETTINGS (a
    // separate, AppOps-gated "Modify system settings" grant) when called
    // from a normal app process - WRITE_SECURE_SETTINGS only covers
    // Global/Secure. Most devices won't have that second grant, so these two
    // are best-effort: a failure here must never block the rest of a
    // restore (that was the "Dev tile gets stuck showing on forever" bug -
    // one of these throwing used to abort the whole restore() call).
    private val BEST_EFFORT_SETTINGS = setOf(SHOW_TOUCHES, POINTER_LOCATION)

    /**
     * Live view of isOn() - the Dev tile subscribes to this (same pattern
     * TetherTileService already uses for LocalShizukuTether.state) instead
     * of only reading isOn() once when its own onStartListening()/toggle()
     * runs. Without this, the Dev tile's icon only ever updated in response
     * to its OWN taps: if the Tether tile (or the real Android Settings
     * screen) turned Developer Options on while the Dev tile was already
     * bound and visible in the same Quick Settings panel, nothing told it to
     * re-render, so it kept showing stale "off" until the panel was closed
     * and reopened. enable()/disable() push into this directly for
     * instant feedback; the ContentObserver is the catch-all for any other
     * source of change (including the real Settings app).
     *
     * Declared at the end of the object body deliberately: its initializer
     * calls isOn(), which reads DEVELOPMENT_SETTINGS_ENABLED - Kotlin
     * initializes object properties in declaration order, so this must come
     * after that property (and everything else isOn() might ever depend on)
     * is already assigned.
     */
    private val _isOnFlow = MutableStateFlow(isOn())
    val isOnFlow: StateFlow<Boolean> = _isOnFlow.asStateFlow()

    init {
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        _isOnFlow.value = isOn()
                    }
                },
            )
        }
    }
}
