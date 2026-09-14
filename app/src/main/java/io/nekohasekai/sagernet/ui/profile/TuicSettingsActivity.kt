package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class TuicSettingsActivity : ProfileSettingsActivity<TuicBean>() {

    override fun createEntity() = TuicBean().applyDefaultValues()

    override fun TuicBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = uuid
        DataStore.serverPassword = token
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverUDPRelayMode = udpRelayMode
        DataStore.serverCongestionController = congestionController
        DataStore.serverDisableSNI = disableSNI
        DataStore.serverSNI = sni
        DataStore.serverReduceRTT = reduceRTT
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverQuicIdleTimeout = quicIdleTimeout
        DataStore.serverQuicKeepAlivePeriod = quicKeepAlivePeriod
        DataStore.serverQuicStreamReceiveWindow = quicStreamReceiveWindow
        DataStore.serverQuicConnectionReceiveWindow = quicConnectionReceiveWindow
        DataStore.serverQuicMaxConcurrentStreams = quicMaxConcurrentStreams
        DataStore.serverQuicInitialPacketSize = quicInitialPacketSize
        DataStore.serverQuicDisablePathMtuDiscovery = quicDisablePathMtuDiscovery
    }

    override fun TuicBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        uuid = DataStore.serverUsername
        token = DataStore.serverPassword
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        udpRelayMode = DataStore.serverUDPRelayMode
        congestionController = DataStore.serverCongestionController
        disableSNI = DataStore.serverDisableSNI
        sni = DataStore.serverSNI
        reduceRTT = DataStore.serverReduceRTT
        allowInsecure = DataStore.serverAllowInsecure
        quicIdleTimeout = DataStore.serverQuicIdleTimeout
        quicKeepAlivePeriod = DataStore.serverQuicKeepAlivePeriod
        quicStreamReceiveWindow = DataStore.serverQuicStreamReceiveWindow
        quicConnectionReceiveWindow = DataStore.serverQuicConnectionReceiveWindow
        quicMaxConcurrentStreams = DataStore.serverQuicMaxConcurrentStreams
        quicInitialPacketSize = DataStore.serverQuicInitialPacketSize
        quicDisablePathMtuDiscovery = DataStore.serverQuicDisablePathMtuDiscovery
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.tuic_preferences)

        val disableSNI = findPreference<SwitchPreference>(Key.SERVER_DISABLE_SNI)!!
        val sni = findPreference<EditTextPreference>(Key.SERVER_SNI)!!
        sni.isEnabled = !disableSNI.isChecked
        disableSNI.setOnPreferenceChangeListener { _, newValue ->
            sni.isEnabled = !(newValue as Boolean)
            true
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        for (key in listOf(
            Key.SERVER_QUIC_IDLE_TIMEOUT,
            Key.SERVER_QUIC_KEEP_ALIVE_PERIOD,
            Key.SERVER_QUIC_STREAM_RECEIVE_WINDOW,
            Key.SERVER_QUIC_CONNECTION_RECEIVE_WINDOW,
            Key.SERVER_QUIC_MAX_CONCURRENT_STREAMS,
            Key.SERVER_QUIC_INITIAL_PACKET_SIZE,
        )) {
            findPreference<EditTextPreference>(key)!!.apply {
                setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
            }
        }
    }

}