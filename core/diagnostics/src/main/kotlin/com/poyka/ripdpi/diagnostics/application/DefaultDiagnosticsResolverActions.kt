package com.poyka.ripdpi.diagnostics.application

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanWorkflow
import com.poyka.ripdpi.diagnostics.ResolverRecommendationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsResolverActions
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val recommendationStore: DiagnosticsRecommendationStore,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val resolverOverrideStore: ResolverOverrideStore,
    ) : DiagnosticsResolverActions {
        override suspend fun keepResolverRecommendationForSession(sessionId: String): Boolean {
            val fingerprint = networkFingerprintProvider.capture()
            val recommendation =
                fingerprint?.let { recommendationStore.loadResolverRecommendation(sessionId, it.scopeKey()) }
            if (fingerprint == null || recommendation == null) return false
            resolverOverrideStore.setTemporaryOverride(
                DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation),
            )
            return true
        }

        override suspend fun saveResolverRecommendation(sessionId: String): Boolean {
            val fingerprint = networkFingerprintProvider.capture()
            val recommendation =
                fingerprint?.let { recommendationStore.loadResolverRecommendation(sessionId, it.scopeKey()) }
            if (fingerprint == null || recommendation == null) return false
            val selectedPath = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
            appSettingsRepository.update {
                dnsMode = com.poyka.ripdpi.data.DnsModeEncrypted
                dnsProviderId = selectedPath.resolverId
                dnsIp = selectedPath.bootstrapIps.firstOrNull().orEmpty()
                encryptedDnsProtocol = selectedPath.protocol
                encryptedDnsHost = selectedPath.host
                encryptedDnsPort = selectedPath.port
                encryptedDnsTlsServerName = selectedPath.tlsServerName
                clearEncryptedDnsBootstrapIps()
                addAllEncryptedDnsBootstrapIps(selectedPath.bootstrapIps)
                encryptedDnsDohUrl = selectedPath.dohUrl
                encryptedDnsDnscryptProviderName = selectedPath.dnscryptProviderName
                encryptedDnsDnscryptPublicKey = selectedPath.dnscryptPublicKey
            }
            networkDnsPathPreferenceStore.rememberPreferredPath(
                fingerprint = fingerprint,
                path = selectedPath,
            )
            resolverOverrideStore.clear()
            return true
        }
    }
