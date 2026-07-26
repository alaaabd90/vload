package io.nekohasekai.sagernet.ui.profile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.internal.LoadBalanceBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.utils.SimSlotInfo
import io.nekohasekai.sagernet.utils.SimSlots

class LoadBalanceSettingsActivity :
    ProfileSettingsActivity<LoadBalanceBean>(R.layout.layout_load_balance_settings) {

    override fun createEntity() = LoadBalanceBean()

    override fun LoadBalanceBean.init() {
        DataStore.profileName = name
        DataStore.lbSlotANetworkKind = slotANetworkKind
        DataStore.lbSlotASubscriptionId = slotASubscriptionId
        DataStore.lbSlotAProxyId = slotAProxyId
        DataStore.lbSlotAWeight = slotAWeight
        DataStore.lbSlotBNetworkKind = slotBNetworkKind
        DataStore.lbSlotBSubscriptionId = slotBSubscriptionId
        DataStore.lbSlotBProxyId = slotBProxyId
        DataStore.lbSlotBWeight = slotBWeight
    }

    override fun LoadBalanceBean.serialize() {
        name = DataStore.profileName
        slotANetworkKind = DataStore.lbSlotANetworkKind
        slotASubscriptionId = DataStore.lbSlotASubscriptionId
        slotAProxyId = DataStore.lbSlotAProxyId
        slotAWeight = DataStore.lbSlotAWeight
        slotBNetworkKind = DataStore.lbSlotBNetworkKind
        slotBSubscriptionId = DataStore.lbSlotBSubscriptionId
        slotBProxyId = DataStore.lbSlotBProxyId
        slotBWeight = DataStore.lbSlotBWeight
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.name_preferences)
    }

    private inner class SlotViews(root: View, titleRes: Int) {
        val networkKindSpinner = root.findViewById<Spinner>(R.id.network_kind_spinner)!!
        val simSpinner = root.findViewById<Spinner>(R.id.sim_spinner)!!
        val profileName = root.findViewById<TextView>(R.id.profile_name)!!
        val chooseProfileButton = root.findViewById<View>(R.id.choose_profile_button)!!
        val weightLabel = root.findViewById<TextView>(R.id.weight_label)!!
        val weightSeekBar = root.findViewById<SeekBar>(R.id.weight_seekbar)!!

        init {
            root.findViewById<TextView>(R.id.slot_title).setText(titleRes)
            networkKindSpinner.adapter = ArrayAdapter(
                this@LoadBalanceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.load_balance_wifi), getString(R.string.load_balance_sim))
            )
            weightSeekBar.max = 1000
        }

        fun sims(): List<SimSlotInfo> = SimSlots.listActiveSims()

        fun refreshSimAdapter(currentSubscriptionId: Int) {
            val sims = sims()
            simSpinner.adapter = ArrayAdapter(
                this@LoadBalanceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                if (sims.isEmpty()) listOf(getString(R.string.load_balance_sim)) else sims.map { it.displayName }
            )
            val index = sims.indexOfFirst { it.subscriptionId == currentSubscriptionId }
            simSpinner.setSelection(index.coerceAtLeast(0))
        }

        fun subscriptionIdForSelection(): Int = sims().getOrNull(simSpinner.selectedItemPosition)?.subscriptionId ?: -1

        fun updateNetworkKindVisibility() {
            simSpinner.visibility =
                if (networkKindSpinner.selectedItemPosition == LoadBalanceBean.NETWORK_SIM) View.VISIBLE else View.GONE
        }
    }

    private lateinit var slotA: SlotViews
    private lateinit var slotB: SlotViews
    private var chosenSlot = 0

    private val requestPhoneStatePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            slotA.refreshSimAdapter(DataStore.lbSlotASubscriptionId)
            slotB.refreshSimAdapter(DataStore.lbSlotBSubscriptionId)
        }

    private val selectProfileForSlot =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                val profileId = data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
                val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
                onMainDispatcher {
                    if (chosenSlot == 0) {
                        DataStore.lbSlotAProxyId = profile.id
                        slotA.profileName.text = profile.displayName()
                    } else {
                        DataStore.lbSlotBProxyId = profile.id
                        slotB.profileName.text = profile.displayName()
                    }
                    DataStore.dirty = true
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.action_load_balance)

        if (ContextCompatPermission.notGranted(this)) {
            requestPhoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        val root = requireActivity()
        slotA = SlotViews(root.findViewById(R.id.slot_a), R.string.load_balance_slot_a)
        slotB = SlotViews(root.findViewById(R.id.slot_b), R.string.load_balance_slot_b)

        bindSlot(
            slotA, DataStore.lbSlotANetworkKind, DataStore.lbSlotASubscriptionId,
            DataStore.lbSlotAProxyId, DataStore.lbSlotAWeight, slotIndex = 0
        )
        bindSlot(
            slotB, DataStore.lbSlotBNetworkKind, DataStore.lbSlotBSubscriptionId,
            DataStore.lbSlotBProxyId, DataStore.lbSlotBWeight, slotIndex = 1
        )
    }

    private fun bindSlot(
        slot: SlotViews,
        networkKind: Int,
        subscriptionId: Int,
        proxyId: Long,
        weight: Int,
        slotIndex: Int,
    ) {
        slot.networkKindSpinner.setSelection(networkKind)
        slot.refreshSimAdapter(subscriptionId)
        slot.updateNetworkKindVisibility()
        slot.weightSeekBar.progress = weight
        slot.weightLabel.text = "${getString(R.string.load_balance_weight)}: $weight"

        if (proxyId > 0) {
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(proxyId)
                onMainDispatcher {
                    slot.profileName.text =
                        profile?.displayName() ?: getString(R.string.load_balance_profile)
                }
            }
        }

        slot.networkKindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (slotIndex == 0) DataStore.lbSlotANetworkKind = position
                else DataStore.lbSlotBNetworkKind = position
                slot.updateNetworkKindVisibility()
                if (position == LoadBalanceBean.NETWORK_SIM && ContextCompatPermission.notGranted(this@LoadBalanceSettingsActivity)) {
                    requestPhoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
                }
                DataStore.dirty = true
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        slot.simSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val subId = slot.subscriptionIdForSelection()
                if (slotIndex == 0) DataStore.lbSlotASubscriptionId = subId
                else DataStore.lbSlotBSubscriptionId = subId
                DataStore.dirty = true
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        slot.chooseProfileButton.setOnClickListener {
            chosenSlot = slotIndex
            selectProfileForSlot.launch(Intent(this, ProfileSelectActivity::class.java))
        }

        slot.weightSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                slot.weightLabel.text = "${getString(R.string.load_balance_weight)}: $value"
                if (fromUser) {
                    if (slotIndex == 0) DataStore.lbSlotAWeight = value else DataStore.lbSlotBWeight =
                        value
                    DataStore.dirty = true
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private object ContextCompatPermission {
        fun notGranted(activity: Activity): Boolean {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        }
    }

}
