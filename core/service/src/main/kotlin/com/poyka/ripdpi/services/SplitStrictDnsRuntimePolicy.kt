package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import java.net.IDN
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale

private const val MaxResolverCandidates = 16
private const val MaxDnsNameLength = 253
private const val MaxDnsLabelLength = 63
private const val MaxRawDnsNameLength = 1024
private const val MaxNumericAddressLength = 64
private const val Ipv4PartCount = 4
private const val MaxIpv4Octet = 255
private const val Ipv6WordCount = 8
private const val Ipv6ByteCount = 16
private const val BitsPerByte = 8
private const val ByteMask = 0xff
private const val BytesPerWord = 2
private const val LowByteOffset = 1
private const val HexRadix = 16
private const val MaxIpv6WordLength = 4
private const val EmbeddedIpv4HighIndex = 0
private const val EmbeddedIpv4LowIndex = 1
private const val EmbeddedIpv4SecondHighIndex = 2
private const val EmbeddedIpv4SecondLowIndex = 3

internal data class SplitStrictDnsDecision(
    val plane: DnsResolverPlane,
    val resolverCandidates: List<String> = emptyList(),
    val coverageReason: String? = null,
)

/**
 * Validated, immutable DNS projection of the canonical ordered destination-routing policy.
 *
 * Stage A carries this plan through the service control plane. The v2 TUN serializer deliberately
 * ignores it until the native multi-plane runtime lands in stages B-C.
 */
class ValidatedSplitStrictDnsPolicy private constructor(
    val destinationRouting: DestinationRoutingPolicy,
    directResolverCandidates: List<String>,
    bootstrapPins: List<String>,
    val policyCoverageReason: String?,
    val underlayLeaseGeneration: Long? = null,
) {
    val directResolverCandidates: List<String> = directResolverCandidates.toList()
    val bootstrapPins: List<String> = bootstrapPins.toList()
    val canonicalDigest: String =
        if (destinationRouting.rules.isEmpty() && policyCoverageReason == null) {
            ""
        } else {
            listOf(
                destinationRouting.canonicalDigest,
                this.directResolverCandidates.joinToString(","),
                this.bootstrapPins.joinToString(","),
                policyCoverageReason.orEmpty(),
            ).joinToString("|").encodeSha256()
        }

    internal fun decide(queryName: String): SplitStrictDnsDecision =
        canonicalDnsName(queryName)?.let(::projectCanonicalName) ?: proxyDecision("invalid_qname")

    private fun projectCanonicalName(queryName: String): SplitStrictDnsDecision =
        destinationRouting.rules
            .asSequence()
            .mapNotNull { rule ->
                val match = evaluateDomainMatchers(rule.domains, queryName)
                when (match) {
                    DomainMatch.NO_MATCH -> null
                    DomainMatch.INCONCLUSIVE -> proxyDecision("unsupported_domain_matcher")
                    DomainMatch.MATCH -> projectMatchedRule(rule)
                }
            }.firstOrNull()
            ?: proxyDecision(policyCoverageReason)

    private fun projectMatchedRule(rule: com.poyka.ripdpi.core.routing.DestinationRoutingRule): SplitStrictDnsDecision =
        if (
            rule.network != DestinationRoutingNetwork.BOTH ||
            rule.ipRanges.isNotEmpty() ||
            rule.destinationPorts.isNotEmpty()
        ) {
            proxyDecision("mixed_route_constraints")
        } else {
            when (rule.action) {
                DestinationRoutingAction.TUNNELED -> proxyDecision()
                DestinationRoutingAction.BLOCK -> SplitStrictDnsDecision(DnsResolverPlane.BLOCK)
                DestinationRoutingAction.DIRECT -> directDecision()
            }
        }

    private fun directDecision(): SplitStrictDnsDecision =
        if (directResolverCandidates.isEmpty()) {
            proxyDecision("direct_resolver_unavailable")
        } else {
            SplitStrictDnsDecision(
                plane = DnsResolverPlane.DIRECT,
                resolverCandidates = directResolverCandidates,
            )
        }

    private fun proxyDecision(reason: String? = null): SplitStrictDnsDecision =
        SplitStrictDnsDecision(
            plane = DnsResolverPlane.PROXY,
            coverageReason = reason,
        )

    companion object {
        fun build(
            activeDns: ActiveDnsSettings,
            routingSnapshot: DestinationRoutingPolicySnapshot,
            underlayDnsServers: List<String>,
            underlayLeaseGeneration: Long? = null,
        ): ValidatedSplitStrictDnsPolicy {
            require(activeDns.isEncrypted) { "Split-strict proxy resolver must be encrypted" }
            val bootstrap = numericAddressesOrEmpty(activeDns.encryptedDnsBootstrapIps, "bootstrap")
            val underlay = numericAddressesOrEmpty(underlayDnsServers)
            val policy =
                when (routingSnapshot) {
                    is DestinationRoutingPolicySnapshot.Available -> routingSnapshot.policy
                    is DestinationRoutingPolicySnapshot.Unavailable -> DestinationRoutingPolicy(canonicalDigest = "")
                }
            require(policy.defaultAction == DestinationRoutingAction.TUNNELED) {
                "Split-strict DNS requires a tunneled default route"
            }
            return ValidatedSplitStrictDnsPolicy(
                destinationRouting = policy,
                directResolverCandidates = underlay.values,
                bootstrapPins = bootstrap.values,
                policyCoverageReason =
                    when (routingSnapshot) {
                        is DestinationRoutingPolicySnapshot.Unavailable -> {
                            "route_policy_unavailable:${routingSnapshot.reason}"
                        }

                        is DestinationRoutingPolicySnapshot.Available -> {
                            if (routingSnapshot.policy.rules.isEmpty()) {
                                null
                            } else {
                                bootstrap.reason ?: underlay.reason
                            }
                        }
                    },
                underlayLeaseGeneration = underlayLeaseGeneration,
            )
        }
    }
}

private data class NumericAddresses(
    val values: List<String>,
    val reason: String? = null,
)

private fun numericAddressesOrEmpty(
    values: List<String>,
    role: String = "direct",
): NumericAddresses =
    if (values.size > MaxResolverCandidates) {
        NumericAddresses(emptyList(), "${role}_resolver_list_too_large")
    } else {
        val parsed = values.mapNotNull(::canonicalNumericAddress)
        if (parsed.size != values.size) {
            NumericAddresses(emptyList(), "${role}_resolver_invalid")
        } else {
            NumericAddresses(parsed.distinct())
        }
    }

private fun canonicalNumericAddress(raw: String): String? =
    if (!isBoundedNumericLiteral(raw)) {
        null
    } else if ('.' in raw && ':' !in raw) {
        parseIpv4Octets(raw)?.joinToString(".")
    } else {
        parseIpv6Bytes(raw)?.let(InetAddress::getByAddress)?.hostAddress?.lowercase(Locale.ROOT)
    }

private fun isBoundedNumericLiteral(raw: String): Boolean {
    val bounded = raw.isNotEmpty() && raw.length <= MaxNumericAddressLength
    val canonicalSpacing = raw == raw.trim()
    return bounded && canonicalSpacing && '%' !in raw
}

private fun parseIpv6Bytes(raw: String): ByteArray? {
    val validCharacters = ':' in raw && raw.all { it in "0123456789abcdefABCDEF:." }
    val compressionIndex = raw.indexOf("::")
    val compressionPresent = compressionIndex >= 0
    val duplicateCompression = compressionPresent && raw.indexOf("::", compressionIndex + 2) >= 0
    val leftRaw = if (compressionIndex >= 0) raw.substring(0, compressionIndex) else raw
    val rightRaw = if (compressionIndex >= 0) raw.substring(compressionIndex + 2) else ""
    val left = parseIpv6Side(leftRaw)
    val right = parseIpv6Side(rightRaw)
    val missing = Ipv6WordCount - left.orEmpty().size - right.orEmpty().size
    val validWordCount =
        missing >= 0 &&
            ((compressionPresent && missing > 0) || (!compressionPresent && missing == 0))
    val validSyntax = validCharacters && !duplicateCompression
    val validSides = left != null && right != null
    val words =
        if (validSyntax && validSides && validWordCount) {
            left.orEmpty() + List(missing) { 0 } + right.orEmpty()
        } else {
            null
        }
    return words?.takeIf { it.size == Ipv6WordCount }?.toIpv6Bytes()
}

private fun List<Int>.toIpv6Bytes(): ByteArray =
    ByteArray(Ipv6ByteCount).also { bytes ->
        forEachIndexed { index, word ->
            bytes[index * BytesPerWord] = (word ushr BitsPerByte).toByte()
            bytes[index * BytesPerWord + LowByteOffset] = word.toByte()
        }
    }

private fun parseIpv6Side(raw: String): List<Int>? =
    if (raw.isEmpty()) {
        emptyList()
    } else {
        val parts = raw.split(':')
        parts.takeUnless { it.any(String::isEmpty) }?.foldIndexed(emptyList<Int>() as List<Int>?) { index, acc, part ->
            acc?.plus(parseIpv6Part(part, index == parts.lastIndex) ?: return@foldIndexed null)
        }
    }

private fun parseIpv6Part(
    part: String,
    isLast: Boolean,
): List<Int>? =
    if ('.' in part) {
        parseIpv4Octets(part)?.takeIf { isLast }?.let { ipv4 ->
            listOf(
                (ipv4[EmbeddedIpv4HighIndex] shl BitsPerByte) or ipv4[EmbeddedIpv4LowIndex],
                (ipv4[EmbeddedIpv4SecondHighIndex] shl BitsPerByte) or ipv4[EmbeddedIpv4SecondLowIndex],
            )
        }
    } else {
        part.toIntOrNull(HexRadix)?.takeIf { part.length <= MaxIpv6WordLength }?.let(::listOf)
    }

private fun parseIpv4Octets(raw: String): List<Int>? {
    val parts = raw.split('.')
    val parsed = parts.map(::parseIpv4Octet)
    return if (parts.size == Ipv4PartCount && parsed.none { it == null }) parsed.filterNotNull() else null
}

private fun parseIpv4Octet(part: String): Int? =
    part
        .takeIf { it.isNotEmpty() && (it.length == 1 || !it.startsWith('0')) }
        ?.toIntOrNull()
        ?.takeIf { it in 0..MaxIpv4Octet }

private enum class DomainMatch { MATCH, NO_MATCH, INCONCLUSIVE }

private fun evaluateDomainMatchers(
    matchers: List<DestinationDomainMatcher>,
    queryName: String,
): DomainMatch =
    if (matchers.isEmpty()) {
        DomainMatch.MATCH
    } else {
        val results = matchers.map { matcher -> matcher.evaluate(queryName) }
        when {
            DomainMatch.MATCH in results -> DomainMatch.MATCH
            DomainMatch.INCONCLUSIVE in results -> DomainMatch.INCONCLUSIVE
            else -> DomainMatch.NO_MATCH
        }
    }

private fun DestinationDomainMatcher.evaluate(queryName: String): DomainMatch =
    when (kind) {
        DestinationDomainMatcherKind.EXACT -> {
            if (queryName == value) DomainMatch.MATCH else DomainMatch.NO_MATCH
        }

        DestinationDomainMatcherKind.SUFFIX -> {
            if (queryName == value || queryName.endsWith(".$value")) DomainMatch.MATCH else DomainMatch.NO_MATCH
        }

        DestinationDomainMatcherKind.GEOSITE -> {
            DomainMatch.INCONCLUSIVE
        }
    }

internal fun canonicalDnsName(raw: String): String? =
    raw
        .takeIf(::isBoundedDnsName)
        ?.removeSuffix(".")
        ?.let { value -> runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES) }.getOrNull() }
        ?.lowercase(Locale.ROOT)
        ?.takeIf(::isValidAsciiDnsName)

private fun isBoundedDnsName(raw: String): Boolean {
    val bounded = raw.isNotEmpty() && raw.length <= MaxRawDnsNameLength
    val canonicalSpacing = raw == raw.trim()
    return bounded && canonicalSpacing && !raw.endsWith("..")
}

private fun isValidAsciiDnsName(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= MaxDnsNameLength &&
        value.split('.').none { label -> label.isEmpty() || label.length > MaxDnsLabelLength }

private fun String.encodeSha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(bytes.size * 2) {
        bytes.forEach { byte -> append("%02x".format(Locale.ROOT, byte.toInt() and ByteMask)) }
    }
}
