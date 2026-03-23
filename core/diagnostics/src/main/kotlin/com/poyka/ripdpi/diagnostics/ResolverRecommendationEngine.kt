package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.toEncryptedDnsPathCandidate
import java.net.URI
import java.util.Locale

private const val ProbeTypeDnsIntegrity = "dns_integrity"
private const val OutcomeDnsMatch = "dns_match"
private const val OutcomeDnsSubstitution = "dns_substitution"
private const val OutcomeUdpBlocked = "udp_blocked"
private const val SchemeHttp = "http://"
private const val SchemeHttps = "https://"
private const val DefaultPortHttp = 80
private const val DefaultPortDoh = 443
private const val DefaultPortDot = 853

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
        return dnsResults.firstTriggerOutcome()?.let { triggerOutcome ->
            val currentPath = settings.activeDnsSettings().toEncryptedDnsPathCandidate()
            selectCandidate(collectCandidates(dnsResults), preferredPath, currentPath)?.let { selected ->
                ResolverRecommendation(
                    triggerOutcome = triggerOutcome,
                    selectedResolverId = selected.path.resolverId,
                    selectedProtocol = selected.path.protocol,
                    selectedEndpoint = selected.path.endpointLabel(),
                    selectedBootstrapIps = selected.path.bootstrapIps,
                    selectedHost = selected.path.host,
                    selectedPort = selected.path.port,
                    selectedTlsServerName = selected.path.tlsServerName,
                    selectedDohUrl = selected.path.dohUrl,
                    selectedDnscryptProviderName = selected.path.dnscryptProviderName,
                    selectedDnscryptPublicKey = selected.path.dnscryptPublicKey,
                    rationale = selected.rationale(triggerOutcome, preferredPath, currentPath),
                    appliedTemporarily = false,
                    persistable = true,
                )
            }
        }
    }

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
                trimmed.substringBefore(':').takeIf(String::isNotBlank)
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
                trimmed.substringAfterLast(':').toIntOrNull()
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

private fun collectCandidates(dnsResults: List<ProbeResult>): List<Candidate> =
    dnsResults
        .mapNotNull { result ->
            val details = result.details.associate { it.key to it.value }
            ResolverRecommendationEngine.parseResolverPathCandidate(details)?.let { path ->
                CandidateObservation(result = result, details = details, path = path)
            }
        }.groupBy { it.path.pathKey() }
        .mapNotNull { (_, entries) -> entries.toCandidate() }

private fun List<ProbeResult>.firstTriggerOutcome(): String? =
    firstOrNull { it.outcome == OutcomeDnsSubstitution || it.outcome == OutcomeUdpBlocked }?.outcome

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
): Candidate? {
    val preferredPathKey = preferredPath?.pathKey()
    val currentPathKey = currentPath?.pathKey()
    return candidates.minWithOrNull(
        compareBy<Candidate>(
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
            { if (it.path.resolverId == DnsProviderCloudflare) 0 else 1 },
        ),
    )
}

private fun Candidate.rationale(
    triggerOutcome: String,
    preferredPath: EncryptedDnsPathCandidate?,
    currentPath: EncryptedDnsPathCandidate?,
): String {
    val latencyHint = "with ${averageLatencyMs ?: 0} ms average latency."
    val bootstrapHint =
        if (bootstrapValidatedCount > 0) {
            " Bootstrap reachability validated on $bootstrapValidatedCount probe(s)."
        } else {
            ""
        }
    val protocolHint = path.protocolPreferenceHint(preferredPath, currentPath)
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
    return prefix + statusDescription + latencyHint + bootstrapHint + protocolHint
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
    result.outcome == OutcomeDnsMatch ||
        result.outcome == OutcomeDnsSubstitution ||
        hasValidEncryptedAddresses(details)

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
    val addresses = details["encryptedAddresses"].orEmpty()
    return addresses.isNotBlank() && !addresses.startsWith("error:")
}
