package com.poyka.ripdpi.diagnostics.dpi

private const val MaxHostLength = 253
private val HostLabel = Regex("[A-Za-z0-9_-]{1,63}")

/**
 * Reduces a probe target entry (`host`, `host/path`, `https://host:443/path`) to the DNS host
 * name that the probes resolve, or null when the entry holds no valid host name.
 */
fun dpiTargetHostOrNull(entry: String): String? {
    val host =
        entry
            .trim()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trimEnd('.')
            .lowercase()
    val valid = host.length in 1..MaxHostLength && host.split('.').all(HostLabel::matches)
    return host.takeIf { valid }
}
