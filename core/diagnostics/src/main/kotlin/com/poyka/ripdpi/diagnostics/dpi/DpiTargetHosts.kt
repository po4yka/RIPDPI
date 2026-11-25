package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.diagnostics.IpProbeMaxPort
import com.poyka.ripdpi.diagnostics.isAsciiDnsHost

private const val MaxHostLength = 253
private val HostLabel = Regex("[A-Za-z0-9_-]{1,63}")
private val SchemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

fun dpiTargetHostOrNull(entry: String): String? {
    val authority = SchemePrefix.replaceFirst(entry.trim(), "").targetAuthority()
    if (authority.count { it == ':' } > 1) return null
    val host = authority.substringBefore(':').trimEnd('.').lowercase()
    val valid = host.length in 1..MaxHostLength && host.split('.').all(HostLabel::matches)
    return host.takeIf { valid }
}

internal fun quicTargetHostOrNull(entry: String): String? {
    val input = entry.trim()
    val addressAndPath = SchemePrefix.replaceFirst(input, "")
    val authority = addressAndPath.targetAuthority()
    val literal = if (addressAndPath == input) NumericIp.parseAddress(authority) else null
    return when {
        literal != null -> literal.toCanonicalString()
        authority.startsWith('[') -> bracketedIpv6(authority)
        else -> dnsNameOrIpv4(authority)
    }
}

private fun bracketedIpv6(authority: String): String? {
    val end = authority.indexOf(']')
    if (end < 0 || !authority.substring(end + 1).validPortSuffix()) return null
    return NumericIp
        .parseAddress(authority.substring(1, end))
        ?.takeIf { it.isIPv6 }
        ?.toCanonicalString()
}

private fun dnsNameOrIpv4(authority: String): String? {
    val host = authority.substringBefore(':')
    if (!authority.substring(host.length).validPortSuffix()) return null
    val name = host.removeSuffix(".").lowercase()
    return NumericIp.parseAddress(name)?.toCanonicalString()
        ?: name.takeIf { isAsciiDnsHost(it) && it.any { char -> char !in '0'..'9' && char != '.' } }
}

private fun String.targetAuthority(): String = substringBefore('/').substringBefore('?').substringBefore('#')

private fun String.validPortSuffix(): Boolean {
    if (isEmpty()) return true
    val port = drop(1)
    return startsWith(':') && port.all { it in '0'..'9' } && (port.toIntOrNull() ?: 0) in 1..IpProbeMaxPort
}
