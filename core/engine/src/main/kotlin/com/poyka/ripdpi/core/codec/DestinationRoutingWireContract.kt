package com.poyka.ripdpi.core.codec

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale

/** Fail-closed validation for the compiler-owned destination-routing wire snapshot. */
internal object DestinationRoutingWireContract {
    private const val MaxRules = 256
    private const val MaxEntriesPerField = 256
    private const val MaxTotalMatchers = 1_024
    private const val MaxTokenLength = 253
    private const val MaxRawEntryLength = 512
    private const val MaxCanonicalLength = 65_536
    private const val MaxHostLabelLength = 63
    private const val DigestLength = 64
    private val ValidPortRange = 1..65_535

    fun validate(policy: NativeDestinationRoutingConfig) {
        require(policy.defaultAction == NativeDestinationRoutingAction.TUNNELED) {
            "destinationRouting.defaultAction must be tunneled"
        }
        require(policy.rules.size <= MaxRules) {
            "destinationRouting.rules exceeds $MaxRules entries"
        }

        var totalMatchers = 0
        var canonicalLength = 0
        policy.rules.forEachIndexed { index, rule ->
            validateRule(index, rule)
            val matcherCount = rule.domains.size + rule.ipRanges.size + rule.destinationPorts.size
            totalMatchers += matcherCount
            require(totalMatchers <= MaxTotalMatchers) {
                "destinationRouting matcher count exceeds $MaxTotalMatchers"
            }
            canonicalLength +=
                rule.domains.sumOf { it.value.length } +
                rule.ipRanges.sumOf { it.value.length } +
                rule.destinationPorts.sumOf { it.start.toString().length + it.endInclusive.toString().length } +
                matcherCount
            require(canonicalLength <= MaxCanonicalLength) {
                "destinationRouting canonical matcher data exceeds $MaxCanonicalLength characters"
            }
        }

        if (policy.rules.isNotEmpty()) {
            require(policy.canonicalDigest.isLowercaseSha256()) {
                "destinationRouting.canonicalDigest must be 64 lowercase hexadecimal characters"
            }
            require(policy.canonicalDigest == computeCanonicalDigest(policy.rules)) {
                "destinationRouting.canonicalDigest does not match the canonical rules"
            }
        }
    }

    internal fun computeCanonicalDigest(rules: List<NativeDestinationRoutingRule>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.put("destination-routing-policy-v1")
        digest.put(NativeDestinationRoutingAction.TUNNELED.name)
        rules.forEach { rule ->
            digest.put(rule.action.name)
            digest.put(rule.network.name)
            rule.domains
                .sortedWith(compareBy(NativeDestinationDomainMatcher::kind, NativeDestinationDomainMatcher::value))
                .forEach { digest.put("d:${it.kind.name}:${it.value}") }
            rule.ipRanges
                .sortedWith(compareBy(NativeDestinationIpMatcher::kind, NativeDestinationIpMatcher::value))
                .forEach { digest.put("i:${it.kind.name}:${it.value}") }
            rule.destinationPorts
                .sortedWith(compareBy(NativeDestinationPortRange::start, NativeDestinationPortRange::endInclusive))
                .forEach { digest.put("p:${it.start}:${it.endInclusive}") }
            digest.update(EntrySeparator)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toUByte().toInt()) }
    }

    private fun validateRule(
        index: Int,
        rule: NativeDestinationRoutingRule,
    ) {
        require(rule.domains.size <= MaxEntriesPerField) {
            "destinationRouting.rules[$index].domains exceeds $MaxEntriesPerField entries"
        }
        require(rule.ipRanges.size <= MaxEntriesPerField) {
            "destinationRouting.rules[$index].ipRanges exceeds $MaxEntriesPerField entries"
        }
        require(rule.destinationPorts.size <= MaxEntriesPerField) {
            "destinationRouting.rules[$index].destinationPorts exceeds $MaxEntriesPerField entries"
        }
        require(rule.domains.isNotEmpty() || rule.ipRanges.isNotEmpty() || rule.destinationPorts.isNotEmpty()) {
            "destinationRouting.rules[$index] has no destination matchers"
        }
        require(rule.domains.distinct().size == rule.domains.size) {
            "destinationRouting.rules[$index] has duplicate domain matchers"
        }
        require(rule.ipRanges.distinct().size == rule.ipRanges.size) {
            "destinationRouting.rules[$index] has duplicate IP matchers"
        }
        require(rule.destinationPorts.distinct().size == rule.destinationPorts.size) {
            "destinationRouting.rules[$index] has duplicate destination port ranges"
        }
        rule.domains.forEach { matcher -> validateDomainMatcher(index, matcher) }
        rule.ipRanges.forEach { matcher -> validateIpMatcher(index, matcher) }
        require(
            rule.destinationPorts.none {
                it.start !in ValidPortRange || it.endInclusive !in ValidPortRange || it.start > it.endInclusive
            },
        ) {
            "destinationRouting.rules[$index] has an invalid destination port range"
        }
    }

    private fun validateDomainMatcher(
        index: Int,
        matcher: NativeDestinationDomainMatcher,
    ) {
        require(matcher.value.length <= MaxRawEntryLength) {
            "destinationRouting.rules[$index] has an oversized domain matcher"
        }
        val valid =
            when (matcher.kind) {
                NativeDestinationDomainMatcherKind.EXACT,
                NativeDestinationDomainMatcherKind.SUFFIX,
                -> matcher.value.isCanonicalHost()

                NativeDestinationDomainMatcherKind.GEOSITE -> matcher.value.isCanonicalGeoToken()
            }
        require(valid) { "destinationRouting.rules[$index] has a non-canonical domain matcher" }
    }

    private fun validateIpMatcher(
        index: Int,
        matcher: NativeDestinationIpMatcher,
    ) {
        require(matcher.value.length <= MaxRawEntryLength) {
            "destinationRouting.rules[$index] has an oversized IP matcher"
        }
        val valid =
            when (matcher.kind) {
                NativeDestinationIpMatcherKind.CIDR -> canonicalCidr(matcher.value) == matcher.value
                NativeDestinationIpMatcherKind.GEO_IP -> matcher.value.isCanonicalGeoToken()
            }
        require(valid) { "destinationRouting.rules[$index] has a non-canonical IP matcher" }
    }

    private fun String.isCanonicalHost(): Boolean =
        length in 1..MaxTokenLength &&
            all { it.isLowerAsciiLetterOrDigit() || it == '-' || it == '.' } &&
            !startsWith('.') &&
            !endsWith('.') &&
            !contains("..") &&
            split('.').all { label ->
                label.length in 1..MaxHostLabelLength &&
                    label.first().isLowerAsciiLetterOrDigit() &&
                    label.last().isLowerAsciiLetterOrDigit()
            }

    private fun String.isCanonicalGeoToken(): Boolean =
        length in 1..MaxTokenLength && all { it.isLowerAsciiLetterOrDigit() || it == '-' || it == '_' }

    private fun String.isLowercaseSha256(): Boolean = length == DigestLength && all { it in '0'..'9' || it in 'a'..'f' }

    private fun Char.isLowerAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private fun canonicalCidr(raw: String): String? {
        val parts = raw.split('/')
        if (parts.size != 2) return null
        val prefix = parts[1].toIntOrNull() ?: return null
        return if ('.' in parts[0] && ':' !in parts[0]) {
            canonicalIpv4(parts[0], prefix)
        } else {
            canonicalIpv6(parts[0], prefix)
        }
    }

    private fun canonicalIpv4(
        rawAddress: String,
        prefix: Int,
    ): String? {
        val octets =
            rawAddress
                .split('.')
                .takeIf { it.size == Ipv4Octets }
                ?.takeIf { parts -> parts.all { it.isCanonicalIpv4Octet() } }
                ?.map(String::toInt)
                ?.takeIf { values -> values.all { it in MinOctet..MaxOctet } }
                ?.map(Int::toByte)
                ?.toByteArray()
                ?: return null
        if (prefix !in 0..Ipv4Bits) return null
        clearHostBits(octets, prefix)
        return octets.joinToString(".") { it.toUByte().toString() } + "/$prefix"
    }

    private fun canonicalIpv6(
        rawAddress: String,
        prefix: Int,
    ): String? {
        if (':' !in rawAddress || '%' in rawAddress || prefix !in 0..Ipv6Bits) return null
        val bytes =
            (runCatching { InetAddress.getByName(rawAddress) }.getOrNull() as? Inet6Address)?.address ?: return null
        clearHostBits(bytes, prefix)
        val address =
            bytes.asList().chunked(Ipv6GroupBytes).joinToString(":") { pair ->
                val group = ((pair[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (pair[1].toInt() and ByteMask)
                group.toString(Ipv6Radix)
            }
        return "$address/$prefix"
    }

    private fun String.isCanonicalIpv4Octet(): Boolean =
        isNotEmpty() && length <= MaxOctetDigits && all(Char::isDigit) && (length == 1 || !startsWith('0'))

    private fun clearHostBits(
        bytes: ByteArray,
        prefix: Int,
    ) {
        for (bit in prefix until bytes.size * Byte.SIZE_BITS) {
            val byteIndex = bit / Byte.SIZE_BITS
            val mask = 1 shl (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)
            bytes[byteIndex] = (bytes[byteIndex].toInt() and mask.inv()).toByte()
        }
    }

    private fun MessageDigest.put(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        update(LengthSeparator)
        update(bytes)
    }

    private const val Ipv4Bits = 32
    private const val Ipv6Bits = 128
    private const val Ipv4Octets = 4
    private const val Ipv6GroupBytes = 2
    private const val Ipv6Radix = 16
    private const val MinOctet = 0
    private const val MaxOctet = 255
    private const val MaxOctetDigits = 3
    private const val ByteMask = 0xff
    private const val LengthSeparator: Byte = 58
    private const val EntrySeparator: Byte = 0
}
