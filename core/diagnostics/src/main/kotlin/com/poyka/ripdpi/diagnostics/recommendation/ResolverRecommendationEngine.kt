@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultDnsProviderDefinition
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.toEncryptedDnsPathCandidate
import java.net.URI
import java.util.Locale

private const val MaxBlockedBootstrapDisplay = 3

private const val ProbeTypeDnsIntegrity = "dns_integrity"
private const val ProbeTypeTcpFatHeader = "tcp_fat_header"

// Only a reset or a close at the 16 KB threshold is evidence of DPI; a timeout or failed handshake is not.
private val TcpFatHeaderDpiBlockedOutcomes = setOf("tcp_reset", "tcp_16kb_blocked")
private val TcpFatHeaderUnusableOutcomes = TcpFatHeaderDpiBlockedOutcomes + setOf("tcp_timeout", "tls_handshake_failed")
private const val OutcomeDnsMatch = "dns_match"

// DNS outcomes the native classifier rates Failed or Attention as interference (outcome_policy.rs).
private val DnsInterferenceOutcomes =
    setOf(
        "dns_sinkhole_substitution",
        "dns_nxdomain_mismatch",
        "dns_system_resolution_failed",
        "dns_suspicious_divergence",
    )
private const val OutcomeUdpBlocked = "udp_blocked"
private const val OutcomeUdpSkippedOrBlocked = "udp_skipped_or_blocked"
private const val DetailDnsSelectedResolverRole = "dnsSelectedResolverRole"
private const val ResolverRolePrimary = "primary"
private const val ResolverRoleSystem = "system"
private const val SchemeHttp = "http://"
private const val SchemeHttps = "https://"
private const val DefaultPortHttp = 80
private const val DefaultPortDoh = 443
private const val DefaultPortDot = 853
private const val EmptyAddressSet = "[]"
private const val DetailSeparator = "|"
private const val MinimumFailedDnsAttempts = 2
private val TrustedDnsOracleResults = setOf("trusted_agreement", "primary_only")
private val CompletedDnsOracleResults = setOf("quorum_reached", "fallback_limit_reached")

private data class Candidate(
    val path: EncryptedDnsPathCandidate,
    val healthyCount: Int,
    val matchCount: Int,
    val bootstrapValidatedCount: Int,
    val averageLatencyMs: Long?,
)

private data class CandidateObservation(
    val result: ProbeResult,
    val details: Map<String, String>,
    val path: EncryptedDnsPathCandidate,
)

internal object ResolverRecommendationEngine {
    fun compute(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredPath: EncryptedDnsPathCandidate?,
    ): ResolverRecommendation? {
        val dnsResults = report.results.filter { it.probeType == ProbeTypeDnsIntegrity }
        val triggers = dnsResults.recommendationTriggers(report.pathMode)
        val advisoryTrigger = triggers.firstOrNull() ?: return null
        val blockedBootstrapIps = extractBlockedBootstrapIps(report.results)
        val dpiBlockedBootstrapIps = extractBlockedBootstrapIps(report.results, TcpFatHeaderDpiBlockedOutcomes)
        val currentPath = settings.activeDnsSettings().toEncryptedDnsPathCandidate()
        val confirmed =
            if (report.hasCompleteResolverEvidence()) {
                triggers.mapNotNull(
                    ProbeResult::confirmedRecovery,
                )
            } else {
                emptyList()
            }
        val confirmedCandidate =
            selectCandidate(confirmed.toCandidates(), preferredPath, currentPath, blockedBootstrapIps)
        val selected =
            confirmedCandidate
                ?: selectCandidate(collectCandidates(dnsResults), preferredPath, currentPath, blockedBootstrapIps)
        return selected?.let { candidate ->
            val trigger =
                confirmed.firstOrNull { it.path.pathKey() == candidate.path.pathKey() }?.result ?: advisoryTrigger
            ResolverRecommendation(
                triggerOutcome = trigger.outcome,
                selectedResolverId = candidate.path.resolverId,
                selectedProtocol = candidate.path.protocol,
                selectedEndpoint = candidate.path.endpointLabel(),
                selectedBootstrapIps = candidate.path.bootstrapIps,
                selectedHost = candidate.path.host,
                selectedPort = candidate.path.port,
                selectedTlsServerName = candidate.path.tlsServerName,
                selectedDohUrl = candidate.path.dohUrl,
                selectedDnscryptProviderName = candidate.path.dnscryptProviderName,
                selectedDnscryptPublicKey = candidate.path.dnscryptPublicKey,
                rationale = candidate.rationale(trigger.outcome, preferredPath, currentPath, dpiBlockedBootstrapIps),
                persistable = confirmedCandidate != null,
            )
        }
    }

    fun extractBlockedBootstrapIps(
        results: List<ProbeResult>,
        outcomes: Set<String> = TcpFatHeaderUnusableOutcomes,
    ): Set<String> =
        results
            .filter { it.probeType == ProbeTypeTcpFatHeader && it.outcome in outcomes }
            .mapNotNull { result -> result.target.targetHost().takeIf { it.isNotBlank() } }
            .map(String::canonicalIpOrSelf)
            .toSet()

    fun parseResolverPathCandidate(details: Map<String, String>): EncryptedDnsPathCandidate? {
        val resolverId = details["encryptedResolverId"].orEmpty().ifBlank { DnsProviderCustom }
        val protocol = details["encryptedProtocol"].orEmpty().ifBlank { EncryptedDnsProtocolDoh }
        val builtIn = dnsProviderById(resolverId)
        val endpoint = details["encryptedEndpoint"].orEmpty()
        val dohUrl =
            details["encryptedDohUrl"].orEmpty().ifBlank {
                if (protocol == EncryptedDnsProtocolDoh) {
                    endpoint.takeIf(::hasHttpScheme) ?: builtIn?.dohUrl.orEmpty()
                } else {
                    ""
                }
            }
        val host =
            details["encryptedHost"].orEmpty().ifBlank {
                parseHostFromEncryptedEndpoint(endpoint) ?: builtIn?.host.orEmpty()
            }
        val port =
            details["encryptedPort"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: parsePortFromEncryptedEndpoint(endpoint)
                ?: defaultPort(protocol, builtIn?.port)
        val bootstrapIps =
            details["encryptedBootstrapIps"]
                ?.split('|')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.takeIf { it.isNotEmpty() }
                ?: builtIn?.bootstrapIps.orEmpty()
        if (host.isBlank() && protocol.requiresHost(dohUrl)) {
            return null
        }
        return EncryptedDnsPathCandidate(
            resolverId = resolverId,
            resolverLabel = builtIn?.displayName ?: resolverId.displayLabel(),
            protocol = protocol,
            host = host,
            port = port,
            tlsServerName =
                details["encryptedTlsServerName"].orEmpty().ifBlank {
                    when {
                        protocol == EncryptedDnsProtocolDnsCrypt -> ""
                        builtIn?.tlsServerName?.isNotBlank() == true -> builtIn.tlsServerName
                        else -> host
                    }
                },
            bootstrapIps = bootstrapIps,
            dohUrl = dohUrl,
            dnscryptProviderName =
                details["encryptedDnscryptProviderName"]
                    .orEmpty()
                    .ifBlank { builtIn?.dnscryptProviderName.orEmpty() },
            dnscryptPublicKey =
                details["encryptedDnscryptPublicKey"]
                    .orEmpty()
                    .ifBlank { builtIn?.dnscryptPublicKey.orEmpty() },
        )
    }

    fun parseHostFromEncryptedEndpoint(endpoint: String): String? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            if (hasHttpScheme(trimmed)) {
                URI(trimmed).host?.takeIf(String::isNotBlank)
            } else {
                trimmed.targetHost().takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    fun parsePortFromEncryptedEndpoint(endpoint: String): Int? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            if (hasHttpScheme(trimmed)) {
                URI(trimmed).resolvedPort()
            } else {
                trimmed.targetPort()
            }
        }.getOrNull()
    }

    fun ResolverRecommendation.toEncryptedDnsPathCandidate(): EncryptedDnsPathCandidate =
        EncryptedDnsPathCandidate(
            resolverId = selectedResolverId,
            resolverLabel = dnsProviderById(selectedResolverId)?.displayName ?: selectedResolverId.displayLabel(),
            protocol = selectedProtocol,
            host = selectedHost.ifBlank { parseHostFromEncryptedEndpoint(selectedEndpoint).orEmpty() },
            port =
                selectedPort.takeIf { it > 0 }
                    ?: parsePortFromEncryptedEndpoint(selectedEndpoint)
                    ?: defaultPort(selectedProtocol, null),
            tlsServerName =
                selectedTlsServerName.ifBlank {
                    if (selectedProtocol == EncryptedDnsProtocolDnsCrypt) {
                        ""
                    } else {
                        selectedHost.ifBlank { parseHostFromEncryptedEndpoint(selectedEndpoint).orEmpty() }
                    }
                },
            bootstrapIps = selectedBootstrapIps,
            dohUrl =
                if (selectedProtocol == EncryptedDnsProtocolDoh) {
                    selectedDohUrl.ifBlank { selectedEndpoint }
                } else {
                    ""
                },
            dnscryptProviderName = selectedDnscryptProviderName,
            dnscryptPublicKey = selectedDnscryptPublicKey,
        )
}

internal fun ScanReport.resolverRecommendationForAutomaticUse(): ResolverRecommendation? =
    resolverRecommendation?.takeIf { it.persistable && hasCompleteResolverEvidence() }

private fun ScanReport.hasCompleteResolverEvidence(): Boolean =
    completionKind == ScanCompletionKind.NORMAL &&
        terminationReason == null &&
        strategyProbeReport?.completionKind != StrategyProbeCompletionKind.PARTIAL_RESULTS

private fun collectCandidates(dnsResults: List<ProbeResult>): List<Candidate> =
    dnsResults.mapNotNull(ProbeResult::resolverObservation).toCandidates()

private fun List<CandidateObservation>.toCandidates(): List<Candidate> =
    groupBy { it.path.pathKey() }.mapNotNull { (_, entries) -> entries.toCandidate() }

private fun ProbeResult.resolverObservation(): CandidateObservation? {
    val details = details.associate { it.key to it.value }
    val path = ResolverRecommendationEngine.parseResolverPathCandidate(details) ?: return null
    return CandidateObservation(this, details, path)
}

// One native DNS-integrity row measures the plain and encrypted answers to the same question.
private fun ProbeResult.confirmedRecovery(): CandidateObservation? {
    val candidate = resolverObservation() ?: return null
    val details = candidate.details
    val resolverId = details["encryptedResolverId"].orEmpty().ifBlank { candidate.path.host }
    val trustedAssessment =
        details["oracleTrust"] in TrustedDnsOracleResults && details["oracleTermination"] in CompletedDnsOracleResults
    val verifiedResolver = details["oracleSelectedResolverId"] == resolverId && candidate.hasValidatedBootstrap()
    val hasEncryptedAnswer =
        details["encryptedAddresses"].orEmpty().split(DetailSeparator).any { NumericIp.parseAddress(it) != null }
    val confirmedFailure =
        when (outcome) {
            "dns_nxdomain_mismatch", "dns_sinkhole_substitution" -> {
                true
            }

            else -> {
                details["udpAttemptCount"]?.toIntOrNull()?.let { it >= MinimumFailedDnsAttempts } == true &&
                    details["udpSuccessCount"] == "0" &&
                    details["udpRetryRecovered"] == "false" &&
                    details["udpCacheHit"] == "false"
            }
        }
    return candidate.takeIf { trustedAssessment && verifiedResolver && hasEncryptedAnswer && confirmedFailure }
}

private fun List<ProbeResult>.recommendationTriggers(pathMode: ScanPathMode): List<ProbeResult> =
    filter {
        it.isDnsInterference(pathMode) && (!hasHealthyPrimaryDnsFor(it.target) || it.isPrimaryOrSystemDnsObservation())
    }

private fun ProbeResult.isDnsInterference(pathMode: ScanPathMode): Boolean =
    when (outcome) {
        in DnsInterferenceOutcomes -> true
        OutcomeUdpBlocked -> pathMode == ScanPathMode.RAW_PATH
        OutcomeUdpSkippedOrBlocked -> pathMode == ScanPathMode.IN_PATH
        else -> false
    }

/** Host of a `host:port`, `[v6]:port` or bare IPv6 target. */
private fun String.targetHost(): String {
    val target = trim()
    return when {
        target.startsWith('[') -> target.substringAfter('[').substringBefore(']')
        target.count { it == ':' } > 1 -> target
        else -> target.substringBefore(':')
    }.trim()
}

/** Port of a `host:port` or `[v6]:port` target; a bare IPv6 address has none. */
private fun String.targetPort(): Int? {
    val target = trim()
    return when {
        target.startsWith('[') -> target.substringAfter(']', "").removePrefix(":")
        target.count { it == ':' } == 1 -> target.substringAfter(':')
        else -> null
    }?.toIntOrNull()
}

/** One spelling per IP literal, so `2001:db8::1` and `2001:DB8:0::1` compare equal; hostnames pass through. */
private fun String.canonicalIpOrSelf(): String = NumericIp.parseAddress(trim())?.toCanonicalString() ?: trim()

private fun List<ProbeResult>.hasHealthyPrimaryDnsFor(target: String): Boolean =
    any { result ->
        result.target == target &&
            result.outcome == OutcomeDnsMatch &&
            result.isPrimaryOrSystemDnsObservation()
    }

private fun ProbeResult.isPrimaryOrSystemDnsObservation(): Boolean =
    (
        detailValue(DetailDnsSelectedResolverRole)
            ?.trim()
            ?.lowercase(Locale.US)
            ?.let { it == ResolverRolePrimary || it == ResolverRoleSystem } == true
    )

private fun List<CandidateObservation>.toCandidate(): Candidate? {
    val healthyEntries = filter(CandidateObservation::isHealthy)
    return firstOrNull()?.path?.takeIf { healthyEntries.isNotEmpty() }?.let { selectedPath ->
        val matchEntries = healthyEntries.filter { it.result.outcome == OutcomeDnsMatch }
        Candidate(
            path = selectedPath,
            healthyCount = healthyEntries.size,
            matchCount = matchEntries.size,
            bootstrapValidatedCount = healthyEntries.count(CandidateObservation::hasValidatedBootstrap),
            averageLatencyMs = averageLatency(matchEntries.ifEmpty { healthyEntries }),
        )
    }
}

private fun selectCandidate(
    candidates: List<Candidate>,
    preferredPath: EncryptedDnsPathCandidate?,
    currentPath: EncryptedDnsPathCandidate?,
    blockedBootstrapIps: Set<String> = emptySet(),
): Candidate? {
    val preferredPathKey = preferredPath?.pathKey()
    val currentPathKey = currentPath?.pathKey()
    return candidates.minWithOrNull(
        compareBy<Candidate>(
            {
                if (blockedBootstrapIps.isNotEmpty() &&
                    it.path.bootstrapIps.any { ip -> ip.canonicalIpOrSelf() in blockedBootstrapIps }
                ) {
                    1
                } else {
                    0
                }
            },
            { if (it.matchCount > 0) 0 else 1 },
            { if (it.bootstrapValidatedCount > 0) 0 else 1 },
            { -it.matchCount },
            { -it.bootstrapValidatedCount },
            { if (preferredPathKey != null && it.path.pathKey() == preferredPathKey) 0 else 1 },
            { if (preferredPath != null && it.path.protocol == preferredPath.protocol) 0 else 1 },
            { if (currentPathKey != null && it.path.pathKey() == currentPathKey) 0 else 1 },
            { if (currentPath != null && it.path.protocol == currentPath.protocol) 0 else 1 },
            { -it.healthyCount },
            { it.averageLatencyMs ?: Long.MAX_VALUE },
            // A full tie goes to the default provider, never to Cloudflare, which is blocked in Russia.
            { if (it.path.resolverId == canonicalDefaultDnsProviderDefinition().providerId) 0 else 1 },
        ),
    )
}

private fun Candidate.rationale(
    triggerOutcome: String,
    preferredPath: EncryptedDnsPathCandidate?,
    currentPath: EncryptedDnsPathCandidate?,
    blockedBootstrapIps: Set<String> = emptySet(),
): String {
    val latencyHint = "with ${averageLatencyMs ?: 0} ms average latency."
    val bootstrapHint =
        if (bootstrapValidatedCount > 0) {
            " Bootstrap reachability validated on $bootstrapValidatedCount probe(s)."
        } else {
            ""
        }
    val protocolHint = path.protocolPreferenceHint(preferredPath, currentPath)
    val blockedHint =
        if (blockedBootstrapIps.isNotEmpty()) {
            val avoided = blockedBootstrapIps.take(MaxBlockedBootstrapDisplay).joinToString(", ")
            " Avoided bootstrap IPs blocked by DPI: $avoided."
        } else {
            ""
        }
    val statusDescription =
        if (matchCount > 0) {
            "${path.resolverLabel} returned matching encrypted answers over " +
                "${path.protocol.uppercase(Locale.US)} on $matchCount probe(s) "
        } else {
            "${path.resolverLabel} returned usable encrypted answers over " +
                "${path.protocol.uppercase(Locale.US)} on $healthyCount probe(s) "
        }
    val prefix =
        if (matchCount > 0) {
            "UDP DNS showed $triggerOutcome while "
        } else {
            "System DNS showed $triggerOutcome while "
        }
    return prefix + statusDescription + latencyHint + bootstrapHint + protocolHint + blockedHint
}

private fun EncryptedDnsPathCandidate.protocolPreferenceHint(
    preferredPath: EncryptedDnsPathCandidate?,
    currentPath: EncryptedDnsPathCandidate?,
): String =
    when {
        preferredPath != null && pathKey() == preferredPath.pathKey() -> {
            " This network already preferred this encrypted DNS path."
        }

        preferredPath != null && protocol == preferredPath.protocol -> {
            " This network already leaned toward ${protocol.uppercase(Locale.US)}."
        }

        currentPath != null && pathKey() == currentPath.pathKey() -> {
            " This matches the currently active encrypted DNS path."
        }

        currentPath != null && protocol == currentPath.protocol -> {
            " This keeps the current encrypted DNS protocol family."
        }

        else -> {
            ""
        }
    }

private fun CandidateObservation.isHealthy(): Boolean =
    result.outcome == OutcomeDnsMatch || hasValidEncryptedAddresses(details)

private fun CandidateObservation.hasValidatedBootstrap(): Boolean =
    details["encryptedBootstrapValidated"].orEmpty().equals("true", ignoreCase = true)

private fun averageLatency(entries: List<CandidateObservation>): Long? =
    entries
        .mapNotNull { it.details["encryptedLatencyMs"]?.toLongOrNull() }
        .takeIf(List<Long>::isNotEmpty)
        ?.average()
        ?.toLong()

private fun defaultPort(
    protocol: String,
    builtInPort: Int?,
): Int =
    when (protocol) {
        EncryptedDnsProtocolDot -> DefaultPortDot
        EncryptedDnsProtocolDoh -> DefaultPortDoh
        else -> builtInPort ?: DefaultPortDoh
    }

private fun String.requiresHost(dohUrl: String): Boolean = this != EncryptedDnsProtocolDoh && dohUrl.isBlank()

private fun String.displayLabel(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun hasHttpScheme(value: String): Boolean = value.startsWith(SchemeHttp) || value.startsWith(SchemeHttps)

private fun URI.resolvedPort(): Int =
    if (port > 0) {
        port
    } else {
        when (scheme?.lowercase(Locale.US)) {
            "http" -> DefaultPortHttp
            else -> DefaultPortDoh
        }
    }

private fun hasValidEncryptedAddresses(details: Map<String, String>): Boolean {
    val addresses =
        details["encryptedAddresses"]
            .orEmpty()
            .split(DetailSeparator)
            .map(String::trim)
            .filter(String::isNotEmpty)
    return addresses.isNotEmpty() && addresses.none(String::isInvalidEncryptedAddressToken)
}

private fun String.isInvalidEncryptedAddressToken(): Boolean {
    val normalized = lowercase(Locale.US)
    return normalized == EmptyAddressSet ||
        normalized.startsWith("error:") ||
        normalized.startsWith("dns_oracle_")
}
