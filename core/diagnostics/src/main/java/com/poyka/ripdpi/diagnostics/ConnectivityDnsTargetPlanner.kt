package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.buildEncryptedDnsCandidatePlan

@Suppress("ComplexCondition")
internal object ConnectivityDnsTargetPlanner {
    fun expandTargets(
        targets: List<DnsTarget>,
        activeDns: ActiveDnsSettings,
        preferredPath: EncryptedDnsPathCandidate?,
    ): List<DnsTarget> {
        val plan = buildEncryptedDnsCandidatePlan(activeDns = activeDns, preferredPath = preferredPath)
        return targets.flatMap { target ->
            if (
                target.encryptedResolverId != null ||
                target.encryptedProtocol != null ||
                target.encryptedHost != null ||
                target.encryptedDohUrl != null ||
                target.encryptedBootstrapIps.isNotEmpty()
            ) {
                listOf(target)
            } else {
                plan.map { candidate ->
                    target.copy(
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
