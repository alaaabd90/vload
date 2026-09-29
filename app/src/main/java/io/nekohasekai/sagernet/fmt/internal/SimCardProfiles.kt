package io.nekohasekai.sagernet.fmt.internal

import org.json.JSONArray
import org.json.JSONObject

data class SimCardProfile(val subscriptionId: Int, val cardName: String, val profileId: Long)

object SimCardProfiles {
    const val FOLLOW_DATA_SIM = -2

    fun parse(json: String?): List<SimCardProfile> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            SimCardProfile(item.getInt("subscriptionId"), item.getString("cardName"), item.getLong("profileId"))
        }.also { cards ->
            require(cards.all { it.subscriptionId >= 0 && it.cardName.isNotBlank() && it.profileId > 0 }) { "Choose a card and VPN profile for every entry" }
            require(cards.map { it.subscriptionId }.distinct().size == cards.size) { "Each SIM card can only be assigned once per network" }
        }
    }

    fun encode(cards: List<SimCardProfile>): String = JSONArray().apply {
        cards.forEach { put(JSONObject().put("subscriptionId", it.subscriptionId).put("cardName", it.cardName).put("profileId", it.profileId)) }
    }.toString()

    fun followsDataSim(bean: LoadBalanceBean, slot: Int): Boolean =
        if (slot == 0) bean.slotANetworkKind == LoadBalanceBean.NETWORK_SIM && bean.slotASubscriptionId == FOLLOW_DATA_SIM
        else bean.slotBNetworkKind == LoadBalanceBean.NETWORK_SIM && bean.slotBSubscriptionId == FOLLOW_DATA_SIM

    fun cards(bean: LoadBalanceBean, slot: Int) = parse(if (slot == 0) bean.slotACardProfiles else bean.slotBCardProfiles)

    // Subscription identity, not carrier/name text: two cards can have the same name.
    fun resolve(cards: List<SimCardProfile>, activeSubscriptionId: Int): SimCardProfile? =
        cards.singleOrNull { it.subscriptionId == activeSubscriptionId }
}
