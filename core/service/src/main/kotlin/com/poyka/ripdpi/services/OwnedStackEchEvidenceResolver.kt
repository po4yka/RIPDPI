package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModePolicyTtlMs
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import javax.inject.Inject
import javax.inject.Singleton

internal data class OwnedStackAuthorityEvidence(
    val confirmedEchCapable: Boolean = false,
    val echEnforcedDomain: Boolean = false,
)

private data class OwnedStackEchDomainRule(
    val domain: String,
    val includeSubdomains: Boolean,
)

private val knownAndroid17EchDomains =
    listOf(
        OwnedStackEchDomainRule(domain = "raw.githubusercontent.com", includeSubdomains = true),
        OwnedStackEchDomainRule(domain = "connectivitycheck.gstatic.com", includeSubdomains = false),
    )

@Singleton
internal class OwnedStackEchEvidenceResolver
    @Inject
    constructor(
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val serverCapabilityStore: ServerCapabilityStore,
    ) {
        suspend fun resolve(
            authority: String?,
            dnsPolicy: SecureHttpDnsPolicy,
        ): OwnedStackAuthorityEvidence {
            val normalizedHost =
                authority?.normalizeOwnedStackHost()
                    ?: return OwnedStackAuthorityEvidence()
            val echEnforcedDomain = normalizedHost.matchesKnownAndroid17EchDomain()
            val fingerprintHash =
                networkFingerprintProvider
                    .capture()
                    ?.scopeKey()
                    .takeIf { dnsPolicy == SecureHttpDnsPolicy.CAPABILITY_SCOPED }
            val confirmedEchCapable =
                if (fingerprintHash != null) {
                    hasFreshEchCapability(fingerprintHash, normalizedHost)
                } else {
                    false
                }
            return OwnedStackAuthorityEvidence(
                confirmedEchCapable = confirmedEchCapable,
                echEnforcedDomain = echEnforcedDomain,
            )
        }

        private suspend fun hasFreshEchCapability(
            fingerprintHash: String,
            normalizedHost: String,
        ): Boolean {
            val now = System.currentTimeMillis()
            return serverCapabilityStore
                .directPathCapabilitiesForFingerprint(fingerprintHash)
                .firstOrNull { record ->
                    record.authority.normalizeOwnedStackHost() == normalizedHost &&
                        record.hasFreshOwnedStackDnsEvidence(now)
                }?.effectiveTransportPolicyEnvelope()
                ?.dnsClassification == DirectDnsClassification.ECH_CAPABLE
        }
    }

private fun String.normalizeOwnedStackHost(): String = substringBefore(':').trim().lowercase()

private fun String.matchesKnownAndroid17EchDomain(): Boolean =
    knownAndroid17EchDomains.any { rule ->
        if (rule.includeSubdomains) {
            this == rule.domain || this.endsWith(".${rule.domain}")
        } else {
            this == rule.domain
        }
    }

private fun ServerCapabilityRecord.hasFreshOwnedStackDnsEvidence(nowMillis: Long): Boolean =
    updatedAt > 0L && nowMillis - updatedAt <= DirectModePolicyTtlMs
