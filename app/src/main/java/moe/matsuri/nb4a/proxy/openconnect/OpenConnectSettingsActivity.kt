package moe.matsuri.nb4a.proxy.openconnect

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

class OpenConnectSettingsActivity : ProfileSettingsActivity<OpenConnectBean>() {
    override fun createEntity() = OpenConnectBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val flavor = pbm.add(PreferenceBinding(Type.Text, "flavor"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val authGroup = pbm.add(PreferenceBinding(Type.Text, "authGroup"))
    private val cookie = pbm.add(PreferenceBinding(Type.Text, "cookie"))
    private val userAgent = pbm.add(PreferenceBinding(Type.Text, "userAgent"))
    private val insecure = pbm.add(PreferenceBinding(Type.Bool, "insecure"))
    private val tlsServerName = pbm.add(PreferenceBinding(Type.Text, "tlsServerName"))
    private val caCertificate = pbm.add(PreferenceBinding(Type.Text, "caCertificate"))
    private val clientCertificate = pbm.add(PreferenceBinding(Type.Text, "clientCertificate"))
    private val clientKey = pbm.add(PreferenceBinding(Type.Text, "clientKey"))
    private val clientKeyPassword = pbm.add(PreferenceBinding(Type.Text, "clientKeyPassword"))
    private val noUdp = pbm.add(PreferenceBinding(Type.Bool, "noUdp"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))

    override fun OpenConnectBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun OpenConnectBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.openconnect_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>("password")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>("clientKeyPassword")!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>("mtu")!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
    }
}
