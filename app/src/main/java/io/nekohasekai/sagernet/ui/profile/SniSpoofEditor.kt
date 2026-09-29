package io.nekohasekai.sagernet.ui.profile

import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.snispoof.SniSpoofSettings
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import org.json.JSONObject

/** All controls live in the profile editor; nothing is written to global settings. */
class SniSpoofEditor(
    private val activity: StandardV2RaySettingsActivity,
    private val fragment: PreferenceFragmentCompat,
    private val snapshot: () -> StandardV2RayBean
) {
    private var running: Job? = null
    private var stopRequested = false
    private val results = fragment.findPreference<Preference>("snispoof.results")!!
    private val test = fragment.findPreference<Preference>("snispoof.test")!!
    private val fakeTest = fragment.findPreference<Preference>("snispoof.test_fake")!!
    private val scan = fragment.findPreference<Preference>("snispoof.scan")!!
    private val stop = fragment.findPreference<Preference>("snispoof.stop")!!

    init {
        SniSpoofSettings.defaults.forEach { (key, default) ->
            val pref = fragment.findPreference<Preference>("snispoof.$key")!!
            if (default is Int) {
                (pref as EditTextPreference).setOnBindEditTextListener {
                    it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                pref.setOnPreferenceChangeListener { _, value -> value.toString().toIntOrNull() != null }
            }
        }
        test.setOnPreferenceClickListener { runTests(false); true }
        fakeTest.setOnPreferenceClickListener { runTests(false, true); true }
        scan.setOnPreferenceClickListener { runTests(true); true }
        stop.setOnPreferenceClickListener {
            stopRequested = true
            results.summary = "Stopping after the current bounded request…"
            true
        }
        stop.isEnabled = false
    }

    fun visibility(enabled: Boolean) {
        fragment.findPreference<PreferenceCategory>("snispoof.options")!!.isVisible = enabled
        // Existing settings remain saved and resume unchanged when SNISpoof is off.
        fragment.findPreference<Preference>("sniFragment")!!.isEnabled = !enabled
        fragment.findPreference<Preference>("allowInsecure")!!.isEnabled = !enabled
        fragment.findPreference<Preference>("enableECH")!!.isEnabled = !enabled
        fragment.findPreference<Preference>("echConfig")!!.isEnabled = !enabled
    }

    private fun runTests(scanIPs: Boolean, fakeTTLTests: Boolean = false) {
        if (running?.isActive == true) return
        val bean: StandardV2RayBean
        val original: JSONObject
        try {
            bean = snapshot()
            original = SniSpoofSettings.parse(bean.snispoofSettings)
            SniSpoofSettings.validate(bean, original)
        } catch (e: Exception) {
            results.summary = e.message
            return
        }
        stopRequested = false
        test.isEnabled = false; scan.isEnabled = false; fakeTest.isEnabled = false; stop.isEnabled = true
        results.summary = "Testing this profile on the current network…"
        running = activity.lifecycleScope.launch {
            val lines = mutableListOf<String>()
            data class Result(val label: String, val millis: Int, val settings: String, val ip: String?)
            val successful = mutableListOf<Result>()
            try {
                val ips: List<String?> = if (scanIPs) withContext(Dispatchers.IO) {
                    Libcore.sniSpoofCandidates(bean.serverAddress,
                        JSONObject(SniSpoofSettings.nativeOptions(original)).toString())
                        .lines().filter { it.isNotBlank() }
                } else listOf(null)
                val requiredECH = original.getBoolean("require_ech")
                val methods = if (scanIPs || fakeTTLTests) listOf(original.getString("strategy") to requiredECH) else
                    listOf("none", "sni", "record", "chunks").map { it to requiredECH } +
                        if (requiredECH) emptyList() else listOf("none" to true, "sni" to true)
                val fakeTTLs = if (fakeTTLTests) listOf(original.getInt("fake_ttl"), original.getInt("fake_ttl")-2, original.getInt("fake_ttl")-4, 2)
                    .filter { it in 1..original.getInt("fake_ttl") }.distinct().sortedDescending() else listOf(original.getInt("fake_ttl"))
                for (ip in ips) for ((method, useECH) in methods) for (fakeTTL in fakeTTLs) {
                    if (stopRequested) break
                    val settings = JSONObject(original.toString()).put("strategy", method).put("require_ech", useECH)
                    // Compare methods with the SAME dial target. Scan tests exactly
                    // one candidate; neither test silently falls back to another IP.
                    settings.put("discover_cloudflare", false).put("candidate_ips", "").put("max_candidates", 1)
                    if (fakeTTLTests) settings.put("fake_enabled", true).put("fake_ttl", fakeTTL).put("fake_auto_ttl", false)
                    val candidate = bean.clone() as StandardV2RayBean
                    candidate.snispoofEnabled = true
                    candidate.snispoofSettings = settings.toString()
                    if (ip != null) {
                        candidate.sni = bean.sni.orEmpty().ifBlank { bean.serverAddress }
                        candidate.serverAddress = ip
                        candidate.finalAddress = ip
                    }
                    val label = (ip ?: "Profile address") + " · " + method +
                        (if (settings.getBoolean("require_ech")) " · ECH required" else "") +
                        (if (settings.getBoolean("fake_enabled")) " · fake " + settings.getString("fake_sni") + " TTL " + fakeTTL + (if (settings.getBoolean("fake_auto_ttl")) " auto" else "") else "")
                    results.summary = (lines + "Testing $label…").joinToString("\n")
                    try {
                        val elapsed = withContext(Dispatchers.IO) {
                            TestInstance(ProxyEntity().putBean(candidate), original.getString("test_url"),
                                original.getInt("total_timeout_ms") + 3000, physicalNetworkTest = true).doTest()
                        }
                        val ech = if (settings.getBoolean("require_ech")) "; ECH accepted" else ""
                        lines += "$label: $elapsed ms; VPN request passed$ech"
                        successful += Result(label, elapsed, settings.toString(), ip)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        // Do not echo profile credentials/configuration in the editor.
                        lines += "$label: failed — " + (e.message ?: "connection failed")
                            .replace(bean.uuid.orEmpty().ifEmpty { "\u0000" }, "[credential]").take(280)
                    }
                    results.summary = lines.joinToString("\n")
                }
                val best = if (fakeTTLTests) successful.sortedWith(compareByDescending<Result> { JSONObject(it.settings).getInt("fake_ttl") }.thenBy { it.millis }).firstOrNull()
                    else successful.minByOrNull { it.millis }
                if (!stopRequested && best != null && !activity.isFinishing) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("SNISpoof test complete")
                        .setMessage("${if (fakeTTLTests) "Highest successful tested TTL" else "Fastest successful sample"}: ${best.label} (${best.millis} ms). " +
                            "This tested an HTTPS request through your VPN profile. Repeat on Wi-Fi and mobile; a short test does not establish long-session reliability.")
                        .setPositiveButton("Use result") { _, _ ->
                            if (scanIPs) {
                                // Put successful candidates first; preserve the user's
                                // original entries and leave the server identity intact.
                                val value = (successful.sortedBy { it.millis }.mapNotNull { it.ip } +
                                    SniSpoofSettings.candidates(original)).distinct().take(32).joinToString("\n")
                                (fragment.findPreference<EditTextPreference>("snispoof.candidate_ips")!!).text = value
                            } else {
                                if (fakeTTLTests) (fragment.findPreference<EditTextPreference>("snispoof.fake_ttl")!!).text = JSONObject(best.settings).getInt("fake_ttl").toString()
                                val value = JSONObject(best.settings).getString("strategy")
                                (fragment.findPreference<androidx.preference.ListPreference>("snispoof.strategy")!!).value = value
                                (fragment.findPreference<androidx.preference.TwoStatePreference>("snispoof.require_ech")!!).isChecked =
                                    JSONObject(best.settings).getBoolean("require_ech")
                            }
                        }
                        .setNegativeButton("Keep settings", null).show()
                }
                if (stopRequested) results.summary = (lines + "Stopped.").joinToString("\n")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { results.summary = e.message
            } finally {
                test.isEnabled = true; scan.isEnabled = true; fakeTest.isEnabled = true; stop.isEnabled = false
            }
        }
    }

    companion object {
        fun cache(bean: StandardV2RayBean) {
            val settings = SniSpoofSettings.parse(bean.snispoofSettings)
            DataStore.profileCacheStore.putString("snispoof.raw", bean.snispoofSettings ?: "")
            SniSpoofSettings.defaults.forEach { (key, value) ->
                if (value is Boolean) DataStore.profileCacheStore.putBoolean("snispoof.$key", settings.getBoolean(key))
                else DataStore.profileCacheStore.putString("snispoof.$key", settings.get(key).toString())
            }
        }

        fun save(bean: StandardV2RayBean) {
            val settings = SniSpoofSettings.parse(DataStore.profileCacheStore.getString("snispoof.raw") ?: bean.snispoofSettings)
            SniSpoofSettings.defaults.forEach { (key, value) ->
                val cacheKey = "snispoof.$key"
                settings.put(key, when (value) {
                    is Boolean -> DataStore.profileCacheStore.getBoolean(cacheKey, value)
                    is Int -> DataStore.profileCacheStore.getString(cacheKey)?.toIntOrNull() ?: value
                    else -> DataStore.profileCacheStore.getString(cacheKey) ?: value
                })
            }
            bean.snispoofSettings = settings.toString()
        }
    }
}
