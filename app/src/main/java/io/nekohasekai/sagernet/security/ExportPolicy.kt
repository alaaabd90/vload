package io.nekohasekai.sagernet.security

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.internal.*

object ExportPolicy {
    fun allowed(profile: ProxyEntity, seen: MutableSet<Long> = mutableSetOf()): Boolean {
        if (profile.lockedImport) return false
        if (profile.id > 0 && !seen.add(profile.id)) return true
        val ids = mutableListOf<Long>()
        profile.chainBean?.let { ids.addAll(it.proxies) }
        profile.loadBalanceBean?.let {
            ids.add(it.slotAProxyId); ids.add(it.slotBProxyId)
            ids.addAll(SimCardProfiles.parse(it.slotACardProfiles).map { card -> card.profileId })
            ids.addAll(SimCardProfiles.parse(it.slotBCardProfiles).map { card -> card.profileId })
        }
        if (profile.groupId > 0) SagerDatabase.groupDao.getById(profile.groupId)?.let {
            ids.add(it.frontProxy); ids.add(it.landingProxy)
        }
        return ids.filter { it > 0 }.all { id ->
            SagerDatabase.proxyDao.getById(id)?.let { allowed(it,seen) } ?: false
        }
    }
    fun requireAllowed(profile: ProxyEntity) = check(allowed(profile)) { "Locked profiles cannot be exported, including through groups or chains" }
}
