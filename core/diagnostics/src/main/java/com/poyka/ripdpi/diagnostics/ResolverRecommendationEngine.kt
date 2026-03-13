package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.toEncryptedDnsPathCandidate
import java.net.URI
import java.util.Locale

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "MagicNumber", "ComplexCondition")
internal object ResolverRecommendationEngine {
    fun compute(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredPath: EncryptedDnsPathCandidate?,
    ): ResolverRecommendation? {
        val dnsResults = report.results.filter { it.probeType == "dns_integrity" }
        if (dnsResults.isEmpty()) {
            return null
        }
        val triggerOutcome =
            dnsResults
                .firstOrNull { it.outcome == "dns_substitution" || it.outcome == "udp_blocked" }
                ?.outcome
                ?: return null

        data class Candidate(
            val path: EncryptedDnsPathCandidate,
            val healthyCount: Int,
            val matchCount: Int,
            val bootstrapValidatedCount: Int,
            val averageLatencyMs: Long?,
        )

        data class CandidateObservation(
            val result: ProbeResult,
            val details: Map<String, String>,
            val path: EncryptedDnsPathCandidate,
        )

        val candidates =
            dnsResults
                .mapNotNull { result ->
                    val details = result.details.associate { it.key to it.value }
                    parseResolverPathCandidate(details)?.let { path ->
                        CandidateObservation(result = result, details = details, path = path)
                    }
                }.groupBy { it.path.pathKey() }
                .mapNotNull { (_, entries) ->
                    val selectedPath = entries.firstOrNull()?.path ?: return@mapNotNull null
                    val healthyEntries =
                        entries.filter { observation ->
                            observation.result.outcome == "dns_match" ||
                                observation.result.outcome == "dns_substitution" ||
                                hasValidEncryptedAddresses(observation.details)
                        }
                    if (healthyEntries.isEmpty()) {
                        return@mapNotNull null
                    }
                    val matchEntries = healthyEntries.filter { it.result.outcome == "dns_match" }
                    Candidate(
                        path = selectedPath,
                        healthyCount = healthyEntries.size,
                        matchCount = matchEntries.size,
                        bootstrapValidatedCount =
                            healthyEntries.count { observation ->
                                observation.details["encryptedBootstrapValidated"]
                                    .orEmpty()
                                    .equals("true", ignoreCase = true)
                            },
                        averageLatencyMs =
                            (matchEntries.ifEmpty { healthyEntries })
                                .mapNotNull { observation -> observation.details["encryptedLatencyMs"]?.toLongOrNull() }
                                .takeIf { it.isNotEmpty() }
                                ?.average()
                                ?.toLong(),
                    )
                }
        if (candidates.isEmpty()) {
            return null
        }

        val currentPath = settings.activeDnsSettings().toEncryptedDnsPathCandidate()
        val preferredPathKey = preferredPath?.pathKey()
        val currentPathKey = currentPath?.pathKey()
        val selected =
            candidates.minWithOrNull(
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
            ) ?: return null

        val protocolPreferenceHint =
            when {
                preferredPathKey != null && selected.path.pathKey() == preferredPathKey -> {
                    " This network already preferred this encrypted DNS path."
                }

                preferredPath != null && selected.path.protocol == preferredPath.protocol -> {
                    " This network already leaned toward ${selected.path.protocol.uppercase(Locale.US)}."
                }

                else -> {
                    ""
                }
            }
        val bootstrapHint =
            if (selected.bootstrapValidatedCount > 0) {
                " Bootstrap reachability validated on ${selected.bootstrapValidatedCount} probe(s)."
            } else {
                ""
            }
        val latencyHint = "with ${selected.averageLatencyMs ?: 0} ms average latency."
        return ResolverRecommendation(
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
            rationale =
                if (selected.matchCount > 0) {
                    "UDP DNS showed $triggerOutcome while " +
                        "${selected.path.resolverLabel} returned matching encrypted answers " +
                        "over ${selected.path.protocol.uppercase(Locale.US)} " +
                        "on ${selected.matchCount} probe(s) " +
                        "$latencyHint$bootstrapHint$protocolPreferenceHint"
                } else {
                    "System DNS showed $triggerOutcome while " +
                        "${selected.path.resolverLabel} returned usable encrypted answers " +
                        "over ${selected.path.protocol.uppercase(Locale.US)} " +
                        "on ${selected.healthyCount} probe(s) " +
                        "$latencyHint$bootstrapHint$protocolPreferenceHint"
                },
            appliedTemporarily = false,
            persistable = true,
        )
    }

    fun parseResolverPathCandidate(details: Map<String, String>): EncryptedDnsPathCandidate? {
        val resolverId = details["encryptedResolverId"].orEmpty().ifBlank { com.poyka.ripdpi.data.DnsProviderCustom }
        val protocol = details["encryptedProtocol"].orEmpty().ifBlank { EncryptedDnsProtocolDoh }
        val builtIn = dnsProviderById(resolverId)
        val dohUrl =
            details["encryptedDohUrl"].orEmpty().ifBlank {
                if (protocol == EncryptedDnsProtocolDoh) {
                    details["encryptedEndpoint"]
                        .orEmpty()
                        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: builtIn?.dohUrl.orEmpty()
                } else {
                    ""
                }
            }
        val host =
            details["encryptedHost"].orEmpty().ifBlank {
                parseHostFromEncryptedEndpoint(details["encryptedEndpoint"].orEmpty())
                    ?: builtIn?.host.orEmpty()
            }
        val port =
            details["encryptedPort"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: parsePortFromEncryptedEndpoint(details["encryptedEndpoint"].orEmpty())
                ?: when {
                    protocol == EncryptedDnsProtocolDot -> 853
                    protocol == EncryptedDnsProtocolDoh -> 443
                    else -> builtIn?.port ?: 443
                }
        val bootstrapIps =
            details["encryptedBootstrapIps"]
                ?.split('|')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.takeIf { it.isNotEmpty() }
                ?: builtIn?.bootstrapIps.orEmpty()
        if (host.isBlank() && protocol != EncryptedDnsProtocolDoh && dohUrl.isBlank()) {
            return null
        }
        return EncryptedDnsPathCandidate(
            resolverId = resolverId,
            resolverLabel = builtIn?.displayName ?: resolverId.replace('_', ' ').replaceFirstChar { it.uppercase() },
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
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                URI(trimmed)
                    .host
                    ?.takeIf { it.isNotBlank() }
            } else {
                trimmed
                    .substringBefore(':')
                    .takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun parsePortFromEncryptedEndpoint(endpoint: String): Int? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val uri = URI(trimmed)
                if (uri.port > 0) {
                    uri.port
                } else {
                    when (uri.scheme?.lowercase(Locale.US)) {
                        "http" -> 80
                        else -> 443
                    }
                }
            } else {
                trimmed.substringAfterLast(':').toIntOrNull()
            }
        }.getOrNull()
    }

    fun ResolverRecommendation.toEncryptedDnsPathCandidate(): EncryptedDnsPathCandidate =
        EncryptedDnsPathCandidate(
            resolverId = selectedResolverId,
            resolverLabel =
                dnsProviderById(selectedResolverId)?.displayName
                    ?: selectedResolverId.replace('_', ' ').replaceFirstChar { it.uppercase() },
            protocol = selectedProtocol,
            host = selectedHost.ifBlank { parseHostFromEncryptedEndpoint(selectedEndpoint).orEmpty() },
            port =
                selectedPort.takeIf { it > 0 }
                    ?: parsePortFromEncryptedEndpoint(selectedEndpoint)
                    ?: if (selectedProtocol == EncryptedDnsProtocolDot) 853 else 443,
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

    private fun hasValidEncryptedAddresses(details: Map<String, String>): Boolean {
        val addresses = details["encryptedAddresses"].orEmpty()
        return addresses.isNotBlank() && !addresses.startsWith("error:")
    }
}
