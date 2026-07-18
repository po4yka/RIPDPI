package com.poyka.ripdpi.core.codec

import java.security.MessageDigest
import java.util.Locale

/** Fail-closed validation for the compiler-owned destination-routing wire snapshot. */
internal object DestinationRoutingWireContract {
    private const val MaxRules = 256
    private const val MaxEntriesPerField = 256
    private const val MaxTotalMatchers = 1_024
    private const val MaxRawEntryLength = 512
    private const val MaxCanonicalLength = 65_536
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

        if (policy.rules.isEmpty()) {
            require(policy.canonicalDigest.isEmpty()) {
                "destinationRouting.canonicalDigest must be empty when rules are absent"
            }
        } else {
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
                .sortedWith(compareBy({ canonicalRank(it.kind) }, NativeDestinationDomainMatcher::value))
                .forEach { digest.put("d:${it.kind.name}:${it.value}") }
            rule.ipRanges
                .sortedWith(compareBy({ canonicalRank(it.kind) }, NativeDestinationIpMatcher::value))
                .forEach { digest.put("i:${it.kind.name}:${it.value}") }
            rule.destinationPorts
                .sortedWith(compareBy(NativeDestinationPortRange::start, NativeDestinationPortRange::endInclusive))
                .forEach { digest.put("p:${it.start}:${it.endInclusive}") }
            digest.update(EntrySeparator)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toUByte().toInt()) }
    }

    private fun canonicalRank(kind: NativeDestinationDomainMatcherKind): Int =
        when (kind) {
            NativeDestinationDomainMatcherKind.EXACT -> 0
            NativeDestinationDomainMatcherKind.SUFFIX -> 1
            NativeDestinationDomainMatcherKind.GEOSITE -> 2
        }

    private fun canonicalRank(kind: NativeDestinationIpMatcherKind): Int =
        when (kind) {
            NativeDestinationIpMatcherKind.CIDR -> 0
            NativeDestinationIpMatcherKind.GEO_IP -> 1
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
                -> {
                    DestinationRoutingCanonicalizer.isCanonicalHost(matcher.value)
                }

                NativeDestinationDomainMatcherKind.GEOSITE -> {
                    DestinationRoutingCanonicalizer.isCanonicalGeoToken(matcher.value)
                }
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
                NativeDestinationIpMatcherKind.CIDR -> {
                    DestinationRoutingCanonicalizer.isCanonicalCidr(matcher.value)
                }

                NativeDestinationIpMatcherKind.GEO_IP -> {
                    DestinationRoutingCanonicalizer.isCanonicalGeoToken(matcher.value)
                }
            }
        require(valid) { "destinationRouting.rules[$index] has a non-canonical IP matcher" }
    }

    private fun String.isLowercaseSha256(): Boolean = length == DigestLength && all { it in '0'..'9' || it in 'a'..'f' }

    private fun MessageDigest.put(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        update(LengthSeparator)
        update(bytes)
    }

    private const val LengthSeparator: Byte = 58
    private const val EntrySeparator: Byte = 0
}
