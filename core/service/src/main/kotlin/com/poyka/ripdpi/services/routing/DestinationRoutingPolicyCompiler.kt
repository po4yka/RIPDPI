package com.poyka.ripdpi.services.routing

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.routing.DestinationRoutingRule
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

/** Compiles persisted rules into one bounded snapshot or rejects the whole snapshot. */
object DestinationRoutingPolicyCompiler {
    const val MAX_RULES: Int = 256
    const val MAX_ENTRIES_PER_FIELD: Int = 256
    const val MAX_TOTAL_MATCHERS: Int = 1_024
    const val MAX_TOKEN_LENGTH: Int = 253
    const val MAX_CANONICAL_LENGTH: Int = 65_536

    fun compile(rules: List<RuleEntity>): DestinationRoutingPolicyCompileResult =
        try {
            DestinationRoutingPolicyCompileResult.Success(SnapshotCompiler(rules).compile())
        } catch (rejection: SnapshotRejectedException) {
            DestinationRoutingPolicyCompileResult.Failure(rejection.compileError)
        }
}

private class SnapshotCompiler(
    rules: List<RuleEntity>,
) {
    private val enabledRules = rules.filter(RuleEntity::enabled).sortedBy(RuleEntity::userOrder)
    private var totalMatchers = 0
    private var canonicalLength = 0

    fun compile(): DestinationRoutingPolicy {
        validateDistinctUserOrders()
        if (enabledRules.size > DestinationRoutingPolicyCompiler.MAX_RULES) {
            reject(
                code = DestinationRoutingPolicyErrorCode.TOO_MANY_RULES,
                message =
                    "Enabled rule count ${enabledRules.size} exceeds " +
                        DestinationRoutingPolicyCompiler.MAX_RULES,
            )
        }
        val rules = enabledRules.map(::compileRule)
        return DestinationRoutingPolicy(
            rules = rules,
            canonicalDigest = DestinationRoutingPolicyDigest.compute(rules),
        )
    }

    private fun validateDistinctUserOrders() {
        val duplicate =
            enabledRules
                .groupBy(RuleEntity::userOrder)
                .filterValues { it.size > 1 }
                .minByOrNull(Map.Entry<Int, List<RuleEntity>>::key)
        duplicate?.let { (order, rules) ->
            val ids = rules.map(RuleEntity::id).sorted()
            reject(
                rule = rules.minBy(RuleEntity::id),
                field = DestinationRoutingPolicyField.USER_ORDER,
                code = DestinationRoutingPolicyErrorCode.DUPLICATE_RULE_ORDER,
                message = "Enabled rules $ids share userOrder $order",
            )
        }
    }

    private fun compileRule(rule: RuleEntity): DestinationRoutingRule {
        validateSupportedSemantics(rule)
        val domains =
            compileLines(rule, DestinationRoutingPolicyField.DOMAINS, rule.domains, DestinationMatcherParser::domain)
        val ipRanges =
            compileLines(rule, DestinationRoutingPolicyField.IP_CIDRS, rule.ipCidrs, DestinationMatcherParser::ip)
        val ports =
            compileLines(rule, DestinationRoutingPolicyField.PORTS, rule.ports, DestinationMatcherParser::port)
        val matcherCount = domains.size + ipRanges.size + ports.size
        validateSnapshotBounds(rule, matcherCount, domains, ipRanges, ports)
        return DestinationRoutingRule(
            action = rule.outboundTag.toDestinationAction(),
            network = rule.network.toDestinationNetwork(),
            domains = domains,
            ipRanges = ipRanges,
            destinationPorts = ports,
        )
    }

    private fun validateSupportedSemantics(rule: RuleEntity) {
        val unsupported =
            when {
                rule.outboundTag is OutboundTag.Profile || rule.outboundTag is OutboundTag.Group -> {
                    UnsupportedField(
                        DestinationRoutingPolicyField.OUTBOUND,
                        DestinationRoutingPolicyErrorCode.UNSUPPORTED_OUTBOUND,
                        "Profile and group outbounds are not supported by destination routing",
                    )
                }

                rule.packages.isNotEmpty() -> {
                    UnsupportedField(
                        DestinationRoutingPolicyField.PACKAGES,
                        message = "Package matchers are not supported by destination routing",
                    )
                }

                rule.processName.isNotBlank() -> {
                    UnsupportedField(
                        DestinationRoutingPolicyField.PROCESS_NAME,
                        message = "Process-name matchers are not supported by destination routing",
                    )
                }

                rule.sourcePorts.isNotBlank() -> {
                    UnsupportedField(
                        DestinationRoutingPolicyField.SOURCE_PORTS,
                        message = "Source-port matchers are not supported by destination routing",
                    )
                }

                else -> {
                    null
                }
            }
        unsupported?.let {
            reject(rule, it.field, it.code, it.message)
        }
    }

    private fun validateSnapshotBounds(
        rule: RuleEntity,
        matcherCount: Int,
        domains: List<DestinationDomainMatcher>,
        ipRanges: List<DestinationIpMatcher>,
        ports: List<DestinationPortRange>,
    ) {
        if (matcherCount == 0) {
            reject(
                rule = rule,
                code = DestinationRoutingPolicyErrorCode.EMPTY_DESTINATION_MATCH,
                message = "Enabled rule has no supported destination matcher",
            )
        }
        totalMatchers += matcherCount
        if (totalMatchers > DestinationRoutingPolicyCompiler.MAX_TOTAL_MATCHERS) {
            reject(
                rule = rule,
                code = DestinationRoutingPolicyErrorCode.TOO_MANY_MATCHERS,
                message = "Snapshot matcher count exceeds ${DestinationRoutingPolicyCompiler.MAX_TOTAL_MATCHERS}",
            )
        }
        canonicalLength +=
            domains.sumOf { it.value.length } +
            ipRanges.sumOf { it.value.length } +
            ports.sumOf { it.start.toString().length + it.endInclusive.toString().length } +
            matcherCount
        if (canonicalLength > DestinationRoutingPolicyCompiler.MAX_CANONICAL_LENGTH) {
            reject(
                rule = rule,
                code = DestinationRoutingPolicyErrorCode.CANONICAL_SIZE_EXCEEDED,
                message =
                    "Canonical matcher data exceeds " +
                        "${DestinationRoutingPolicyCompiler.MAX_CANONICAL_LENGTH} characters",
            )
        }
    }

    private fun <T> compileLines(
        rule: RuleEntity,
        field: DestinationRoutingPolicyField,
        raw: String,
        parse: (String) -> MatcherParseResult<T>,
    ): List<T> {
        val lines =
            raw
                .lineSequence()
                .mapIndexedNotNull { index, line ->
                    line.trim().takeIf(String::isNotEmpty)?.let { IndexedMatcherLine(index, it) }
                }.toList()
        if (lines.size > DestinationRoutingPolicyCompiler.MAX_ENTRIES_PER_FIELD) {
            reject(
                rule,
                field,
                DestinationRoutingPolicyErrorCode.TOO_MANY_FIELD_ENTRIES,
                "${field.name} entry count ${lines.size} exceeds " +
                    DestinationRoutingPolicyCompiler.MAX_ENTRIES_PER_FIELD,
            )
        }
        val values = LinkedHashSet<T>(lines.size)
        lines.forEach { line ->
            when (val result = parse(line.value)) {
                is MatcherParseResult.Value -> {
                    values += result.value
                }

                MatcherParseResult.Unsupported -> {
                    reject(
                        rule,
                        field,
                        DestinationRoutingPolicyErrorCode.UNSUPPORTED_MATCHER,
                        "Unsupported ${field.displayName()} matcher at line ${line.index + 1}: ${line.value}",
                        entryIndex = line.index,
                    )
                }

                MatcherParseResult.Malformed -> {
                    reject(
                        rule,
                        field,
                        DestinationRoutingPolicyErrorCode.MALFORMED_MATCHER,
                        "Malformed ${field.displayName()} matcher at line ${line.index + 1}: ${line.value}",
                        entryIndex = line.index,
                    )
                }
            }
        }
        return values.toList()
    }
}

private object DestinationMatcherParser {
    fun domain(raw: String): MatcherParseResult<DestinationDomainMatcher> {
        val regex = raw.takeIf { it.startsWith(REGEX_PREFIX, ignoreCase = true) }?.substring(REGEX_PREFIX.length)
        val parsed =
            raw
                .takeIf { regex == null }
                ?.let(::parseDomainToken)
        val canonical = parsed?.second?.lowercase(Locale.ROOT)?.removeSuffix(".")
        val matcher =
            when (parsed?.first) {
                DestinationDomainMatcherKind.GEOSITE -> {
                    canonical
                        ?.takeIf { it.isValidGeoToken() }
                        ?.let { DestinationDomainMatcher(parsed.first, it) }
                }

                DestinationDomainMatcherKind.EXACT,
                DestinationDomainMatcherKind.SUFFIX,
                -> {
                    canonical
                        ?.takeIf { it.isValidHost() }
                        ?.let { DestinationDomainMatcher(parsed.first, it) }
                }

                null -> {
                    null
                }
            }
        return when {
            regex?.isValidRegex() == true -> MatcherParseResult.Unsupported
            regex != null -> MatcherParseResult.Malformed
            matcher != null -> MatcherParseResult.Value(matcher)
            else -> MatcherParseResult.Malformed
        }
    }

    fun ip(raw: String): MatcherParseResult<DestinationIpMatcher> {
        val matcher =
            if (raw.startsWith(GEO_IP_PREFIX, ignoreCase = true)) {
                raw
                    .substring(GEO_IP_PREFIX.length)
                    .lowercase(Locale.ROOT)
                    .takeIf { it.isValidGeoToken() }
                    ?.let { DestinationIpMatcher(DestinationIpMatcherKind.GEO_IP, it) }
            } else {
                val parts = raw.split('/')
                parts
                    .takeIf { it.size == CIDR_PART_COUNT }
                    ?.let { CidrCanonicalizer.canonical(it[0], it[1].toIntOrNull()) }
                    ?.let { DestinationIpMatcher(DestinationIpMatcherKind.CIDR, it) }
            }
        return matcher?.let { MatcherParseResult.Value(it) } ?: MatcherParseResult.Malformed
    }

    fun port(raw: String): MatcherParseResult<DestinationPortRange> {
        val parts = raw.split('-')
        val start = parts.firstOrNull()?.toIntOrNull()
        val end = parts.getOrNull(1)?.toIntOrNull() ?: start
        val matcher =
            DestinationPortRange(start ?: INVALID_PORT, end ?: INVALID_PORT)
                .takeIf { parts.size in 1..PORT_RANGE_PART_COUNT }
                ?.takeIf { it.start in MIN_PORT..MAX_PORT && it.endInclusive in MIN_PORT..MAX_PORT }
                ?.takeIf { it.start <= it.endInclusive }
        return matcher?.let { MatcherParseResult.Value(it) } ?: MatcherParseResult.Malformed
    }

    private fun parseDomainToken(raw: String): Pair<DestinationDomainMatcherKind, String>? =
        when {
            raw.startsWith(EXACT_PREFIX, ignoreCase = true) -> {
                DestinationDomainMatcherKind.EXACT to raw.substring(EXACT_PREFIX.length)
            }

            raw.startsWith(SUFFIX_PREFIX, ignoreCase = true) -> {
                DestinationDomainMatcherKind.SUFFIX to raw.substring(SUFFIX_PREFIX.length).removePrefix(".")
            }

            raw.startsWith(GEOSITE_PREFIX, ignoreCase = true) -> {
                DestinationDomainMatcherKind.GEOSITE to raw.substring(GEOSITE_PREFIX.length)
            }

            raw.startsWith(".") -> {
                DestinationDomainMatcherKind.SUFFIX to raw.removePrefix(".")
            }

            ':' in raw -> {
                null
            }

            else -> {
                DestinationDomainMatcherKind.EXACT to raw
            }
        }

    private fun String.isValidHost(): Boolean =
        isValidTokenLength() &&
            !startsWith('.') &&
            !endsWith('.') &&
            !contains("..") &&
            split('.').all { it.isValidHostLabel() }

    private fun String.isValidHostLabel(): Boolean =
        isNotEmpty() &&
            length <= MAX_HOST_LABEL_LENGTH &&
            first().isAsciiLetterOrDigit() &&
            last().isAsciiLetterOrDigit() &&
            all { it.isAsciiLetterOrDigit() || it == '-' }

    private fun String.isValidGeoToken(): Boolean =
        isValidTokenLength() && all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }

    private fun String.isValidRegex(): Boolean = isNotEmpty() && runCatching { Pattern.compile(this) }.isSuccess

    private fun String.isValidTokenLength(): Boolean =
        isNotEmpty() && length <= DestinationRoutingPolicyCompiler.MAX_TOKEN_LENGTH

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private const val EXACT_PREFIX = "domain:"
    private const val SUFFIX_PREFIX = "domain_suffix:"
    private const val GEOSITE_PREFIX = "geosite:"
    private const val GEO_IP_PREFIX = "geoip:"
    private const val REGEX_PREFIX = "domain_regex:"
    private const val MAX_HOST_LABEL_LENGTH = 63
    private const val CIDR_PART_COUNT = 2
    private const val PORT_RANGE_PART_COUNT = 2
    private const val INVALID_PORT = -1
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
}

private object CidrCanonicalizer {
    fun canonical(
        rawAddress: String,
        prefix: Int?,
    ): String? =
        if ('.' in rawAddress && ':' !in rawAddress) {
            canonicalIpv4(rawAddress, prefix)
        } else {
            canonicalIpv6(rawAddress, prefix)
        }

    private fun canonicalIpv4(
        rawAddress: String,
        prefix: Int?,
    ): String? =
        parseIpv4(rawAddress)
            ?.takeIf { prefix in 0..IPV4_BITS }
            ?.also { clearHostBits(it, checkNotNull(prefix)) }
            ?.joinToString(".") { it.toUByte().toString() }
            ?.let { "$it/$prefix" }

    private fun canonicalIpv6(
        rawAddress: String,
        prefix: Int?,
    ): String? =
        rawAddress
            .takeIf { ':' in it && '%' !in it && prefix in 0..IPV6_BITS }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() as? Inet6Address }
            ?.address
            ?.also { clearHostBits(it, checkNotNull(prefix)) }
            ?.let(::formatIpv6)
            ?.let { "$it/$prefix" }

    private fun parseIpv4(raw: String): ByteArray? {
        val parts = raw.split('.')
        val values =
            parts
                .takeIf { it.size == IPV4_OCTETS }
                ?.takeIf { parts -> parts.all { it.isCanonicalIpv4Octet() } }
                ?.map { it.toInt() }
                ?.takeIf { octets -> octets.all { it in MIN_OCTET..MAX_OCTET } }
        return values?.map(Int::toByte)?.toByteArray()
    }

    private fun String.isCanonicalIpv4Octet(): Boolean =
        isNotEmpty() &&
            length <= MAX_OCTET_DIGITS &&
            all(Char::isDigit) &&
            (length == 1 || !startsWith('0'))

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

    private fun formatIpv6(bytes: ByteArray): String =
        bytes.asList().chunked(IPV6_GROUP_BYTES).joinToString(":") { pair ->
            val group =
                ((pair[0].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or
                    (pair[1].toInt() and BYTE_MASK)
            group.toString(IPV6_RADIX)
        }

    private const val IPV4_BITS = 32
    private const val IPV6_BITS = 128
    private const val IPV4_OCTETS = 4
    private const val IPV6_GROUP_BYTES = 2
    private const val IPV6_RADIX = 16
    private const val MIN_OCTET = 0
    private const val MAX_OCTET = 255
    private const val MAX_OCTET_DIGITS = 3
    private const val BYTE_MASK = 0xff
}

private object DestinationRoutingPolicyDigest {
    fun compute(rules: List<DestinationRoutingRule>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.put("destination-routing-policy-v1")
        digest.put(DestinationRoutingAction.TUNNELED.name)
        rules.forEach { rule ->
            digest.put(rule.action.name)
            digest.put(rule.network.name)
            rule.domains
                .sortedWith(compareBy(DestinationDomainMatcher::kind, DestinationDomainMatcher::value))
                .forEach { digest.put("d:${it.kind.name}:${it.value}") }
            rule.ipRanges
                .sortedWith(compareBy(DestinationIpMatcher::kind, DestinationIpMatcher::value))
                .forEach { digest.put("i:${it.kind.name}:${it.value}") }
            rule.destinationPorts
                .sortedWith(compareBy(DestinationPortRange::start, DestinationPortRange::endInclusive))
                .forEach { digest.put("p:${it.start}:${it.endInclusive}") }
            digest.update(ENTRY_SEPARATOR)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toUByte().toInt()) }
    }

    private fun MessageDigest.put(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        update(LENGTH_SEPARATOR)
        update(bytes)
    }

    private const val LENGTH_SEPARATOR: Byte = 58
    private const val ENTRY_SEPARATOR: Byte = 0
}

private fun OutboundTag.toDestinationAction(): DestinationRoutingAction =
    when (this) {
        OutboundTag.Proxy -> DestinationRoutingAction.TUNNELED

        OutboundTag.Bypass -> DestinationRoutingAction.DIRECT

        OutboundTag.Block -> DestinationRoutingAction.BLOCK

        is OutboundTag.Profile,
        is OutboundTag.Group,
        -> error("unsupported outbound should have been rejected")
    }

private fun RuleNetwork.toDestinationNetwork(): DestinationRoutingNetwork =
    when (this) {
        RuleNetwork.TCP -> DestinationRoutingNetwork.TCP
        RuleNetwork.UDP -> DestinationRoutingNetwork.UDP
        RuleNetwork.BOTH -> DestinationRoutingNetwork.BOTH
    }

private fun reject(
    rule: RuleEntity? = null,
    field: DestinationRoutingPolicyField? = null,
    code: DestinationRoutingPolicyErrorCode,
    message: String,
    entryIndex: Int? = null,
): Nothing =
    throw SnapshotRejectedException(
        DestinationRoutingPolicyCompileError(rule?.id, field, entryIndex, code, message),
    )

private class SnapshotRejectedException(
    val compileError: DestinationRoutingPolicyCompileError,
) : RuntimeException(compileError.message)

private data class UnsupportedField(
    val field: DestinationRoutingPolicyField,
    val code: DestinationRoutingPolicyErrorCode = DestinationRoutingPolicyErrorCode.UNSUPPORTED_MATCHER,
    val message: String,
)

private data class IndexedMatcherLine(
    val index: Int,
    val value: String,
)

private sealed interface MatcherParseResult<out T> {
    data class Value<T>(
        val value: T,
    ) : MatcherParseResult<T>

    data object Unsupported : MatcherParseResult<Nothing>

    data object Malformed : MatcherParseResult<Nothing>
}

sealed interface DestinationRoutingPolicyCompileResult {
    data class Success(
        val policy: DestinationRoutingPolicy,
    ) : DestinationRoutingPolicyCompileResult

    data class Failure(
        val error: DestinationRoutingPolicyCompileError,
    ) : DestinationRoutingPolicyCompileResult
}

data class DestinationRoutingPolicyCompileError(
    val ruleId: Long?,
    val field: DestinationRoutingPolicyField?,
    /** Zero-based line index within [field], when the error points at one entry. */
    val entryIndex: Int? = null,
    val code: DestinationRoutingPolicyErrorCode,
    val message: String,
)

enum class DestinationRoutingPolicyField {
    DOMAINS,
    IP_CIDRS,
    PORTS,
    SOURCE_PORTS,
    PROCESS_NAME,
    PACKAGES,
    OUTBOUND,
    USER_ORDER,
}

enum class DestinationRoutingPolicyErrorCode {
    TOO_MANY_RULES,
    TOO_MANY_FIELD_ENTRIES,
    TOO_MANY_MATCHERS,
    CANONICAL_SIZE_EXCEEDED,
    EMPTY_DESTINATION_MATCH,
    UNSUPPORTED_OUTBOUND,
    UNSUPPORTED_MATCHER,
    MALFORMED_MATCHER,
    DUPLICATE_RULE_ORDER,
}

private fun DestinationRoutingPolicyField.displayName(): String = name.lowercase(Locale.ROOT)
