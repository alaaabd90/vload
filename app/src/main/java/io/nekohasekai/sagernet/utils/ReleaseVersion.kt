package io.nekohasekai.sagernet.utils

/** Compare release versions numerically; display titles and substrings are not ordering. */
object ReleaseVersion {
    fun isNewer(remote: String, installed: String): Boolean {
        fun parse(value: String): List<Long>? = Regex("(?:^|[^0-9])([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[^0-9]|$)")
            .find(value)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
        val next = parse(remote) ?: return false
        val current = parse(installed) ?: return false
        for (i in 0..2) if (next[i] != current[i]) return next[i] > current[i]
        return false
    }
}
