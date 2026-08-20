package moe.matsuri.nb4a

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions.RuleSet

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        fun auto2(key: String, newS: String): String {
            return (DataStore.configurationStore.getString(key) ?: "").replace("auto", newS)
        }
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                // prefer_ipv4 still lets an IPv6 destination through when a
                // domain has both A/AAAA records - and sing's address
                // serializer (common/metadata/serializer.go) throws
                // "unsupported address" for IPv6 on some outbound/relay
                // paths (confirmed live: intermittent ERR_CONNECTION_REFUSED
                // on dual-stack domains like clients4.google.com under Load
                // Balance's weighted outbound). ipv4_only removes IPv6 from
                // consideration entirely instead of merely preferring IPv4.
                auto2("domain_strategy_for_server", "ipv4_only")
            }
        }
    }

}

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    rule_set = mutableListOf<String>()
    domain = mutableListOf<String>()
    domain_suffix = mutableListOf<String>()
    domain_regex = mutableListOf<String>()
    domain_keyword = mutableListOf<String>()
    list.forEach {
        if (it.startsWith("geosite:")) {
            rule_set.plusAssign(it)
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    ruleSetString.forEach {
        when {
            it.startsWith("geoip:") -> {
                ruleSet.add(RuleSet().apply {
                    type = "local"
                    tag = it
                    format = "binary"
                    path = it
                })
            }

            it.startsWith("geosite:") -> {
                ruleSet.add(RuleSet().apply {
                    type = "local"
                    tag = it
                    format = "binary"
                    path = it
                })
            }
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    if (isIP) {
        ip_cidr = mutableListOf<String>()
        rule_set = mutableListOf<String>()
    } else {
        rule_set = mutableListOf<String>()
        domain = mutableListOf<String>()
        domain_suffix = mutableListOf<String>()
        domain_regex = mutableListOf<String>()
        domain_keyword = mutableListOf<String>()
    }
    list.forEach {
        if (isIP) {
            if (it.startsWith("geoip:")) {
                if (it == "geoip:private") {
                    ip_is_private = true
                } else {
                    rule_set.plusAssign(it)
                }
            } else {
                ip_cidr.plusAssign(it)
            }
            return@forEach
        }
        if (it.startsWith("geosite:")) {
            rule_set.plusAssign(it)
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    ip_cidr?.removeIf { it.isNullOrBlank() }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_cidr?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}
