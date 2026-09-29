package io.nekohasekai.sagernet

import android.content.Intent
import android.os.SystemClock
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SniSpoofEditorTest {
    @Test fun profileSwitchShowsControlsAndRestoresLegacyControlsWhenDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, VMessSettingsActivity::class.java)
            .putExtra("vless", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as VMessSettingsActivity
        try {
            var fragment: PreferenceFragmentCompat? = null
            val until = SystemClock.elapsedRealtime() + 10000
            while (fragment == null && SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync {
                    fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat
                    if (fragment?.findPreference<Preference>("snispoofEnabled") == null) fragment = null
                }
                if (fragment == null) SystemClock.sleep(50)
            }
            assertNotNull("Profile editor failed to load", fragment)
            fun screenshot(name: String) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250)
                val resolver = instrumentation.targetContext.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SniSpoofAudit")
                }
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                resolver.openOutputStream(uri)!!.use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            instrumentation.runOnMainSync { fragment!!.scrollToPreference("snispoofEnabled") }
            screenshot("snispoof-disabled")
            instrumentation.runOnMainSync {
                val screen = fragment!!
                val toggle = screen.findPreference<TwoStatePreference>("snispoofEnabled")!!
                val controls = screen.findPreference<PreferenceCategory>("snispoof.options")!!
                assertFalse(toggle.isChecked); assertFalse(controls.isVisible)
                screen.findPreference<TwoStatePreference>("sniFragment")!!.isChecked = true
                screen.findPreference<TwoStatePreference>("enableECH")!!.isChecked = true
                screen.findPreference<androidx.preference.EditTextPreference>("echConfig")!!.text = "saved-legacy-ech"
                assertTrue(toggle.callChangeListener(true)); toggle.isChecked = true
                assertTrue(controls.isVisible)
                for (key in listOf("fake_enabled", "fake_sni", "fake_ttl", "fake_auto_ttl", "fake_delay_ms", "test_fake", "strategy", "fragment_size", "delay_ms", "require_ech", "ech_config",
                    "candidate_ips", "discover_cloudflare", "max_candidates", "attempt_timeout_ms",
                    "total_timeout_ms", "retry_after_seconds", "diagnostics", "test_url", "test", "scan", "results")) {
                    assertNotNull("Missing profile control $key", screen.findPreference<Preference>("snispoof.$key"))
                }
                assertFalse(screen.findPreference<Preference>("sniFragment")!!.isEnabled)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val screen = fragment!!
                val adapter = screen.listView.adapter as androidx.preference.PreferenceGroupAdapter
                val position = adapter.getPreferenceAdapterPosition("snispoof.strategy")
                assertTrue("Enabled options are absent from the visible preference list", position >= 0)
                (screen.listView.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(position, 0)
            }
            screenshot("snispoof-enabled")
            instrumentation.runOnMainSync {
                val screen = fragment!!
                val toggle = screen.findPreference<TwoStatePreference>("snispoofEnabled")!!
                val controls = screen.findPreference<PreferenceCategory>("snispoof.options")!!
                assertTrue(toggle.callChangeListener(false)); toggle.isChecked = false
                assertFalse(controls.isVisible)
                assertTrue(screen.findPreference<Preference>("sniFragment")!!.isEnabled)
                assertTrue(screen.findPreference<Preference>("enableECH")!!.isEnabled)
                assertTrue(screen.findPreference<Preference>("echConfig")!!.isEnabled)
                assertTrue(screen.findPreference<TwoStatePreference>("sniFragment")!!.isChecked)
                assertTrue(screen.findPreference<TwoStatePreference>("enableECH")!!.isChecked)
                assertEquals("saved-legacy-ech", screen.findPreference<androidx.preference.EditTextPreference>("echConfig")!!.text)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
