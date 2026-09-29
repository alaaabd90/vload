package io.nekohasekai.sagernet.ui.profile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.fmt.internal.SimCardProfiles
import io.nekohasekai.sagernet.fmt.internal.SimCardProfile
import io.nekohasekai.sagernet.database.ProxyEntity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
        DataStore.lbSlotACardProfiles = slotACardProfiles
        DataStore.lbSlotBCardProfiles = slotBCardProfiles
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
        slotACardProfiles = DataStore.lbSlotACardProfiles
        slotBCardProfiles = DataStore.lbSlotBCardProfiles
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

    private inner class SlotViews(root: View, titleRes: Int, val index: Int) {
        val fixedProfileRow = root.findViewById<View>(R.id.fixed_profile_row)!!
        val cardsContainer = root.findViewById<LinearLayout>(R.id.card_profiles_container)!!
        val addCard = root.findViewById<Button>(R.id.add_card_profile)!!
        private var simEntries = emptyList<Pair<Int, String>>()
        val networkKindSpinner = root.findViewById<Spinner>(R.id.network_kind_spinner)!!
        val simSpinner = root.findViewById<Spinner>(R.id.sim_spinner)!!
        val profileName = root.findViewById<TextView>(R.id.profile_name)!!
        val chooseProfileButton = root.findViewById<View>(R.id.choose_profile_button)!!

        init {
            root.findViewById<TextView>(R.id.slot_title).setText(titleRes)
            networkKindSpinner.adapter = ArrayAdapter(
                this@LoadBalanceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.load_balance_wifi), getString(R.string.load_balance_sim))
            )
        }

        fun sims(): List<SimSlotInfo> = SimSlots.listActiveSims()

        fun refreshSimAdapter(currentSubscriptionId: Int) {
            simSpinner.onItemSelectedListener = null
            simEntries = buildList {
                if (Build.VERSION.SDK_INT >= 30 || currentSubscriptionId == SimCardProfiles.FOLLOW_DATA_SIM)
                    add(SimCardProfiles.FOLLOW_DATA_SIM to getString(R.string.lb_follow_data_sim))
                addAll(sims().map { it.subscriptionId to "${it.displayName} (SIM ${it.slotIndex + 1})" })
                if (none { it.first == currentSubscriptionId }) add(currentSubscriptionId to "${getString(R.string.load_balance_sim)} (${getString(R.string.lb_card_unavailable)})")
            }
            simSpinner.adapter = ArrayAdapter(this@LoadBalanceSettingsActivity, android.R.layout.simple_spinner_dropdown_item, simEntries.map { it.second })
            simSpinner.setSelection(simEntries.indexOfFirst { it.first == currentSubscriptionId }.coerceAtLeast(0))
            simSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val subId = simEntries.getOrNull(position)?.first ?: return
                    if (index == 0) DataStore.lbSlotASubscriptionId = subId else DataStore.lbSlotBSubscriptionId = subId
                    updateNetworkKindVisibility()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        fun updateNetworkKindVisibility() {
            val sim = networkKindSpinner.selectedItemPosition == LoadBalanceBean.NETWORK_SIM
            val auto = sim && (if (index == 0) DataStore.lbSlotASubscriptionId else DataStore.lbSlotBSubscriptionId) == SimCardProfiles.FOLLOW_DATA_SIM
            simSpinner.visibility = if (sim) View.VISIBLE else View.GONE
            fixedProfileRow.visibility = if (auto) View.GONE else View.VISIBLE
            cardsContainer.visibility = if (auto) View.VISIBLE else View.GONE
            addCard.visibility = if (auto) View.VISIBLE else View.GONE
            if (auto) renderCards()
        }

        fun renderCards() {
            cardsContainer.removeAllViews()
            cardsContainer.addView(TextView(this@LoadBalanceSettingsActivity).apply { setText(R.string.lb_card_help) })
            val active = sims().map { it.subscriptionId }.toSet()
            cards(index).forEach { card ->
                val row = LinearLayout(this@LoadBalanceSettingsActivity).apply { orientation = LinearLayout.VERTICAL }
                row.addView(TextView(this@LoadBalanceSettingsActivity).apply {
                    text = "${getString(R.string.lb_card_name)}: ${card.cardName}" + if (card.subscriptionId !in active) " (${getString(R.string.lb_card_unavailable)})" else ""
                    setPadding(0, 24, 0, 0)
                })
                val choose = Button(this@LoadBalanceSettingsActivity).apply {
                    isAllCaps = false
                    text = getString(R.string.select_profile)
                    setOnClickListener { chooseCardProfile(index, card.subscriptionId, card.cardName) }
                }
                row.addView(choose)
                runOnDefaultDispatcher {
                    val name = ProfileManager.getProfile(card.profileId)?.displayName() ?: getString(R.string.lb_card_profile_missing)
                    onMainDispatcher { choose.text = "${getString(R.string.load_balance_profile)}: $name" }
                }
                row.addView(Button(this@LoadBalanceSettingsActivity).apply {
                    isAllCaps = false
                    setText(R.string.lb_card_remove)
                    setOnClickListener { saveCards(index, cards(index).filterNot { it.subscriptionId == card.subscriptionId }); renderCards() }
                })
                cardsContainer.addView(row)
            }
            addCard.setOnClickListener {
                if (ContextCompatPermission.notGranted(this@LoadBalanceSettingsActivity)) {
                    requestPhoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
                    return@setOnClickListener
                }
                val used = cards(index).map { it.subscriptionId }.toSet()
                val available = sims().filter { it.subscriptionId !in used }
                if (available.isEmpty()) {
                    MaterialAlertDialogBuilder(this@LoadBalanceSettingsActivity).setMessage(R.string.lb_no_cards).setPositiveButton(android.R.string.ok, null).show()
                } else MaterialAlertDialogBuilder(this@LoadBalanceSettingsActivity).setTitle(R.string.lb_card_name)
                    .setItems(available.map { "${it.displayName} (SIM ${it.slotIndex + 1})" }.toTypedArray()) { _, pos ->
                        val card = available[pos]; chooseCardProfile(index, card.subscriptionId, card.displayName)
                    }.show()
            }
        }
    }

    private fun cards(slot: Int) = SimCardProfiles.parse(if (slot == 0) DataStore.lbSlotACardProfiles else DataStore.lbSlotBCardProfiles)
    private fun saveCards(slot: Int, cards: List<SimCardProfile>) {
        if (slot == 0) DataStore.lbSlotACardProfiles = SimCardProfiles.encode(cards) else DataStore.lbSlotBCardProfiles = SimCardProfiles.encode(cards)
        DataStore.dirty = true
    }
    private var pendingCardId = -1
    private var pendingCardName = ""
    private fun chooseCardProfile(slot: Int, subscriptionId: Int, name: String) {
        chosenSlot = slot; pendingCardId = subscriptionId; pendingCardName = name
        selectProfileForSlot.launch(Intent(this, ProfileSelectActivity::class.java))
    }

    private lateinit var slotA: SlotViews
    private lateinit var slotB: SlotViews
    private var chosenSlot = 0

    private val requestPhoneStatePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (::slotA.isInitialized) slotA.refreshSimAdapter(DataStore.lbSlotASubscriptionId)
            if (::slotB.isInitialized) slotB.refreshSimAdapter(DataStore.lbSlotBSubscriptionId)
        }

    private val selectProfileForSlot =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                val profileId = data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
                val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
                onMainDispatcher {
                    if (pendingCardId >= 0) {
                        if (profile.type == ProxyEntity.TYPE_LOAD_BALANCE) {
                            Toast.makeText(this@LoadBalanceSettingsActivity, R.string.lb_card_profile_invalid, Toast.LENGTH_LONG).show()
                        } else {
                            val entries = cards(chosenSlot).filterNot { it.subscriptionId == pendingCardId } + SimCardProfile(pendingCardId, pendingCardName, profile.id)
                            saveCards(chosenSlot, entries)
                            (if (chosenSlot == 0) slotA else slotB).renderCards()
                        }
                        pendingCardId = -1
                    } else if (chosenSlot == 0) {
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
        chosenSlot = savedInstanceState?.getInt("cardSlot") ?: 0
        pendingCardId = savedInstanceState?.getInt("cardId", -1) ?: -1
        pendingCardName = savedInstanceState?.getString("cardName") ?: ""
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.action_load_balance)

        if (ContextCompatPermission.notGranted(this)) {
            requestPhoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        val root = requireActivity()
        slotA = SlotViews(root.findViewById(R.id.slot_a), R.string.load_balance_slot_a, 0)
        slotB = SlotViews(root.findViewById(R.id.slot_b), R.string.load_balance_slot_b, 1)

        bindSlot(
            slotA, DataStore.lbSlotANetworkKind, DataStore.lbSlotASubscriptionId,
            DataStore.lbSlotAProxyId, slotIndex = 0
        )
        bindSlot(
            slotB, DataStore.lbSlotBNetworkKind, DataStore.lbSlotBSubscriptionId,
            DataStore.lbSlotBProxyId, slotIndex = 1
        )
    }

    private fun bindSlot(
        slot: SlotViews,
        networkKind: Int,
        subscriptionId: Int,
        proxyId: Long,
        slotIndex: Int,
    ) {
        slot.networkKindSpinner.setSelection(networkKind)
        slot.refreshSimAdapter(subscriptionId)
        slot.updateNetworkKindVisibility()

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

        slot.chooseProfileButton.setOnClickListener {
            pendingCardId = -1
            chosenSlot = slotIndex
            selectProfileForSlot.launch(Intent(this, ProfileSelectActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("cardSlot", chosenSlot); outState.putInt("cardId", pendingCardId); outState.putString("cardName", pendingCardName)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::slotA.isInitialized) { slotA.refreshSimAdapter(DataStore.lbSlotASubscriptionId); slotA.updateNetworkKindVisibility() }
        if (::slotB.isInitialized) { slotB.refreshSimAdapter(DataStore.lbSlotBSubscriptionId); slotB.updateNetworkKindVisibility() }
    }

    override suspend fun saveAndExit() {
        val bean = LoadBalanceBean().apply { serialize() }
        var error: Int? = null
        for (slot in 0..1) if (SimCardProfiles.followsDataSim(bean, slot)) {
            if (Build.VERSION.SDK_INT < 30 || ContextCompatPermission.notGranted(this)) error = R.string.lb_card_android_required
            else if (cards(slot).isEmpty()) error = R.string.lb_card_profile_required
            else if (cards(slot).any { ProfileManager.getProfile(it.profileId)?.let { profile -> profile.type == ProxyEntity.TYPE_LOAD_BALANCE } != false }) error = R.string.lb_card_profile_invalid
        }
        if (error != null) {
            onMainDispatcher { Toast.makeText(this@LoadBalanceSettingsActivity, error, Toast.LENGTH_LONG).show() }
            return
        }
        super.saveAndExit()
    }

    private object ContextCompatPermission {
        fun notGranted(activity: Activity): Boolean {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        }
    }

}
