package moe.matsuri.nb4a.proxy.openvpn

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class OpenVPNSettingsActivity : ProfileSettingsActivity<OpenVPNBean>() {
    override fun createEntity() = OpenVPNBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val mode = pbm.add(PreferenceBinding(Type.Text, "mode"))
    private val network = pbm.add(PreferenceBinding(Type.Text, "network"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val caCertificate = pbm.add(PreferenceBinding(Type.Text, "caCertificate"))
    private val clientCertificate = pbm.add(PreferenceBinding(Type.Text, "clientCertificate"))
    private val clientKey = pbm.add(PreferenceBinding(Type.Text, "clientKey"))
    private val tlsServerName = pbm.add(PreferenceBinding(Type.Text, "tlsServerName"))
    private val staticKey = pbm.add(PreferenceBinding(Type.Text, "staticKey"))
    private val keyDirection = pbm.add(PreferenceBinding(Type.Text, "keyDirection"))
    private val controlWrapType = pbm.add(PreferenceBinding(Type.Text, "controlWrapType"))
    private val controlWrapKey = pbm.add(PreferenceBinding(Type.Text, "controlWrapKey"))
    private val cipher = pbm.add(PreferenceBinding(Type.Text, "cipher"))
    private val auth = pbm.add(PreferenceBinding(Type.Text, "auth"))
    private val compression = pbm.add(PreferenceBinding(Type.Text, "compression"))
    private val mssFix = pbm.add(PreferenceBinding(Type.TextToInt, "mssFix"))

    override fun OpenVPNBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun OpenVPNBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.openvpn_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("password")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>("mssFix")!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }
}
