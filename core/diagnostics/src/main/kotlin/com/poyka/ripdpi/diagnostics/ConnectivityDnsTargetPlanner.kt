package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.buildEncryptedDnsCandidatePlan

private const val DefaultResolverCandidateLimit = 3

internal object ConnectivityDnsTargetPlanner {
    fun expandTargets(
        targets: List<DnsTarget>,
        activeDns: ActiveDnsSettings,
        preferredPath: EncryptedDnsPathCandidate?,
        networkSnapshot: NativeNetworkSnapshot? = null,
        resolverCandidateLimit: Int? = null,
    ): List<DnsTarget> {
        val plan =
            buildEncryptedDnsCandidatePlan(activeDns = activeDns, preferredPath = preferredPath)
                .let { candidates -> resolverCandidateLimit?.let(candidates::take) ?: candidates }
        val snapshotUdpServers =
            networkSnapshot
                ?.dnsServers
                .orEmpty()
                .mapNotNull(::normalizeUdpDnsServer)
                .distinct()
        return targets.flatMap { target ->
            val targetWithUdpBaseline = target.withSnapshotUdpBaseline(snapshotUdpServers.firstOrNull())
            if (targetWithUdpBaseline.hasExplicitEncryptedConfig()) {
                listOf(targetWithUdpBaseline)
            } else {
                plan.map { candidate ->
                    targetWithUdpBaseline.copy(
                        encryptedResolverId = candidate.resolverId,
                        encryptedProtocol = candidate.protocol,
                        encryptedHost = candidate.host,
                        encryptedPort = candidate.port,
                        encryptedTlsServerName = candidate.tlsServerName,
                        encryptedBootstrapIps = candidate.bootstrapIps,
                        encryptedDohUrl = candidate.dohUrl.ifBlank { null },
                        encryptedDnscryptProviderName = candidate.dnscryptProviderName.ifBlank { null },
                        encryptedDnscryptPublicKey = candidate.dnscryptPublicKey.ifBlank { null },
                    )
                }
            }
        }
    }
}

private fun DnsTarget.hasExplicitEncryptedConfig(): Boolean =
    encryptedResolverId != null ||
        encryptedProtocol != null ||
        encryptedHost != null ||
        encryptedDohUrl != null ||
        encryptedBootstrapIps.isNotEmpty()

private fun DnsTarget.withSnapshotUdpBaseline(snapshotUdpServer: String?): DnsTarget =
    if (udpServer.isNullOrBlank() && !snapshotUdpServer.isNullOrBlank()) {
        copy(udpServer = snapshotUdpServer)
    } else {
        this
    }

internal fun defaultDiagnosticsResolverCandidateLimit(profileId: String): Int? =
    if (profileId == "default") DefaultResolverCandidateLimit else null

private fun normalizeUdpDnsServer(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        return null
    }
    return when {
        trimmed.startsWith("[") && "]:" in trimmed -> trimmed
        trimmed.startsWith("[") && trimmed.endsWith("]") -> "$trimmed:53"
        trimmed.count { it == ':' } >= 2 -> "[$trimmed]:53"
        ":" in trimmed -> trimmed
        else -> "$trimmed:53"
    }
}
