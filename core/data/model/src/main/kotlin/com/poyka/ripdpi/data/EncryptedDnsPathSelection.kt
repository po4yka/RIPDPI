package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

const val NetworkDnsPathPreferenceRetentionLimit = 64
const val NetworkDnsPathPreferenceRetentionMaxAgeMs = 90L * 24L * 60L * 60L * 1_000L
const val NetworkDnsBlockedPathRetentionLimit = 128
const val NetworkDnsBlockedPathRetentionMaxAgeMs = 30L * 24L * 60L * 60L * 1_000L

@Serializable
data class EncryptedDnsPathCandidate(
    val resolverId: String,
    val resolverLabel: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val tlsServerName: String = "",
    val bootstrapIps: List<String> = emptyList(),
    val dohUrl: String = "",
    val dnscryptProviderName: String = "",
    val dnscryptPublicKey: String = "",
) {
    fun endpointLabel(): String =
        when (protocol) {
            EncryptedDnsProtocolDoh -> dohUrl.ifBlank { "https://$host/dns-query" }
            else -> "$host:$port"
        }

    fun pathKey(): String =
        listOf(
            resolverId.trim().lowercase(),
            protocol.trim().lowercase(),
            host.trim().lowercase(),
            port.toString(),
            tlsServerName.trim().lowercase(),
            normalizeDnsBootstrapIps(bootstrapIps).joinToString(","),
            dohUrl.trim().lowercase(),
            dnscryptProviderName.trim().lowercase(),
            dnscryptPublicKey.trim().lowercase(),
        ).joinToString("|")

    fun toActiveDnsSettings(): ActiveDnsSettings =
        activeDnsSettings(
            dnsMode = DnsModeEncrypted,
            dnsProviderId = resolverId.ifBlank { DnsProviderCustom },
            dnsIp = normalizeDnsBootstrapIps(bootstrapIps).firstOrNull().orEmpty(),
            encryptedDnsProtocol = protocol,
            encryptedDnsHost = host,
            encryptedDnsPort = port,
            encryptedDnsTlsServerName = tlsServerName,
            encryptedDnsBootstrapIps = bootstrapIps,
            encryptedDnsDohUrl = if (protocol == EncryptedDnsProtocolDoh) dohUrl else "",
            encryptedDnsDnscryptProviderName =
                if (protocol == EncryptedDnsProtocolDnsCrypt) {
                    dnscryptProviderName
                } else {
                    ""
                },
            encryptedDnsDnscryptPublicKey =
                if (protocol == EncryptedDnsProtocolDnsCrypt) {
                    dnscryptPublicKey
                } else {
                    ""
                },
        )
}

fun ActiveDnsSettings.toEncryptedDnsPathCandidate(): EncryptedDnsPathCandidate? {
    if (!isEncrypted) {
        return null
    }
    return EncryptedDnsPathCandidate(
        resolverId = providerId.ifBlank { DnsProviderCustom },
        resolverLabel = providerDisplayName,
        protocol = encryptedDnsProtocol,
        host = encryptedDnsHost,
        port = encryptedDnsPort,
        tlsServerName = encryptedDnsTlsServerName,
        bootstrapIps = normalizeDnsBootstrapIps(encryptedDnsBootstrapIps),
        dohUrl = encryptedDnsDohUrl,
        dnscryptProviderName = encryptedDnsDnscryptProviderName,
        dnscryptPublicKey = encryptedDnsDnscryptPublicKey,
    )
}

fun builtInEncryptedDnsPathCandidates(): List<EncryptedDnsPathCandidate> =
    BuiltInDnsProviders.flatMap { provider ->
        listOfNotNull(
            provider.toEncryptedDnsPathCandidate(EncryptedDnsProtocolDoh),
            provider.toEncryptedDnsPathCandidate(EncryptedDnsProtocolDot),
            provider.toEncryptedDnsPathCandidate(EncryptedDnsProtocolDnsCrypt),
        )
    }

fun buildEncryptedDnsCandidatePlan(
    activeDns: ActiveDnsSettings,
    preferredPath: EncryptedDnsPathCandidate? = null,
    blockedPathKeys: Set<String> = emptySet(),
): List<EncryptedDnsPathCandidate> {
    val currentPath = activeDns.toEncryptedDnsPathCandidate()
    val candidates =
        linkedMapOf<String, EncryptedDnsPathCandidate>()
            .apply {
                builtInEncryptedDnsPathCandidates().forEach { candidate ->
                    put(candidate.pathKey(), candidate)
                }
                preferredPath?.let { candidate ->
                    put(candidate.pathKey(), candidate)
                }
                currentPath?.let { candidate ->
                    put(candidate.pathKey(), candidate)
                }
            }.values
            .toList()

    if (candidates.isEmpty()) {
        return emptyList()
    }

    val providerOrder =
        BuiltInDnsProviders
            .mapIndexed { index, provider -> provider.providerId to index }
            .toMap()
    val preferredProtocol = preferredPath?.protocol?.takeIf { it.isNotBlank() }
    val currentProtocol = currentPath?.protocol?.takeIf { it.isNotBlank() }
    val protocolSeed = preferredProtocol ?: currentProtocol ?: EncryptedDnsProtocolDoh
    val protocolOrder =
        buildList {
            add(protocolSeed)
            listOf(
                EncryptedDnsProtocolDoh,
                EncryptedDnsProtocolDot,
                EncryptedDnsProtocolDnsCrypt,
            ).filterTo(this) { protocol -> protocol != protocolSeed }
        }

    val comparator = candidateComparator(blockedPathKeys, preferredPath, currentPath, providerOrder)
    return interleaveByProtocol(candidates, protocolOrder, comparator)
}

private fun candidateComparator(
    blockedPathKeys: Set<String>,
    preferredPath: EncryptedDnsPathCandidate?,
    currentPath: EncryptedDnsPathCandidate?,
    providerOrder: Map<String, Int>,
): Comparator<EncryptedDnsPathCandidate> =
    compareBy<EncryptedDnsPathCandidate>(
        { if (it.pathKey() in blockedPathKeys) 1 else 0 },
        { if (preferredPath != null && it.pathKey() == preferredPath.pathKey()) 0 else 1 },
        { if (preferredPath != null && it.resolverId == preferredPath.resolverId) 0 else 1 },
        { if (currentPath != null && it.pathKey() == currentPath.pathKey()) 0 else 1 },
        { if (currentPath != null && it.resolverId == currentPath.resolverId) 0 else 1 },
        { providerOrder[it.resolverId] ?: Int.MAX_VALUE },
        { it.resolverLabel.lowercase() },
        { it.host.lowercase() },
        { it.port },
    )

private fun interleaveByProtocol(
    candidates: List<EncryptedDnsPathCandidate>,
    protocolOrder: List<String>,
    comparator: Comparator<EncryptedDnsPathCandidate>,
): List<EncryptedDnsPathCandidate> {
    val grouped =
        candidates
            .groupBy { it.protocol }
            .mapValues { (_, values) -> values.sortedWith(comparator).toMutableList() }
    val ordered = mutableListOf<EncryptedDnsPathCandidate>()
    while (true) {
        var added = false
        protocolOrder.forEach { protocol ->
            val queue = grouped[protocol] ?: return@forEach
            if (queue.isNotEmpty()) {
                ordered += queue.removeAt(0)
                added = true
            }
        }
        if (!added) break
    }
    grouped.values
        .flatMap { it }
        .sortedWith(comparator)
        .forEach(ordered::add)
    return ordered
}

private fun DnsProviderDefinition.toEncryptedDnsPathCandidate(protocol: String): EncryptedDnsPathCandidate? =
    when (protocol) {
        EncryptedDnsProtocolDoh -> {
            EncryptedDnsPathCandidate(
                resolverId = providerId,
                resolverLabel = displayName,
                protocol = protocol,
                host = host,
                port = port,
                tlsServerName = tlsServerName,
                bootstrapIps = bootstrapIps,
                dohUrl = dohUrl.orEmpty(),
            )
        }

        EncryptedDnsProtocolDot -> {
            EncryptedDnsPathCandidate(
                resolverId = providerId,
                resolverLabel = displayName,
                protocol = protocol,
                host = host,
                port = 853,
                tlsServerName = tlsServerName.ifBlank { host },
                bootstrapIps = bootstrapIps,
            )
        }

        EncryptedDnsProtocolDnsCrypt -> {
            dnscryptProviderName
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { !dnscryptPublicKey.isNullOrBlank() }
                ?.let {
                    EncryptedDnsPathCandidate(
                        resolverId = providerId,
                        resolverLabel = displayName,
                        protocol = protocol,
                        host = host,
                        port = port.takeIf { it > 0 } ?: 443,
                        bootstrapIps = bootstrapIps,
                        dnscryptProviderName = dnscryptProviderName.orEmpty(),
                        dnscryptPublicKey = dnscryptPublicKey.orEmpty(),
                    )
                }
        }

        else -> {
            null
        }
    }
