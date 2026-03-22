package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsBootstrapper
    @Inject
    constructor(
        private val archiveExporter: DiagnosticsArchiveExporter,
        private val profileImporter: BundledDiagnosticsProfileImporter,
        private val runtimeHistoryStartup: RuntimeHistoryStartup,
        private val policyHandoverEventStore: PolicyHandoverEventStore,
        private val automaticProbeScheduler: AutomaticProbeScheduler,
        @param:Named("importBundledProfilesOnInitialize")
        private val importBundledProfilesOnInitialize: Boolean,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) : DiagnosticsBootstrapper {
        private val initialized = AtomicBoolean(false)

        override suspend fun initialize() {
            if (!initialized.compareAndSet(false, true)) {
                return
            }
            runCatching {
                runtimeHistoryStartup.start()
            }.onFailure { error ->
                logRuntimeHistoryBootstrapFailure(error)
            }
            archiveExporter.cleanupCache()
            if (importBundledProfilesOnInitialize) {
                profileImporter.importProfiles()
            }
            scope.launch {
                policyHandoverEventStore.events.collect { event ->
                    automaticProbeScheduler.schedule(event)
                }
            }
        }

        private fun logRuntimeHistoryBootstrapFailure(error: Throwable) {
            System.err.println("Runtime history bootstrap skipped")
        }
    }

@Singleton
class DefaultDiagnosticsDetailLoader
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsDetailLoader {
        override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
            DiagnosticsSessionQueries.loadSessionDetail(sessionId, scanRecordStore, artifactReadStore)

        override suspend fun loadApproachDetail(
            kind: BypassApproachKind,
            id: String,
        ): BypassApproachDetail =
            DiagnosticsSessionQueries.loadApproachDetail(kind, id, scanRecordStore, bypassUsageHistoryStore, json)
    }

@Singleton
class DefaultDiagnosticsShareService
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val archiveExporter: DiagnosticsArchiveExporter,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsShareService {
        override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
            withContext(Dispatchers.IO) {
                DiagnosticsShareSummaryBuilder.build(
                    sessionId = sessionId,
                    scanRecordStore = scanRecordStore,
                    artifactReadStore = artifactReadStore,
                    json = json,
                )
            }

        override suspend fun createArchive(sessionId: String?): DiagnosticsArchive =
            withContext(Dispatchers.IO) { archiveExporter.createArchive(sessionId) }
    }

@Singleton
class DiagnosticsRecommendationStore
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun loadResolverRecommendation(sessionId: String): ResolverRecommendation? =
            scanRecordStore
                .getScanSession(sessionId)
                ?.reportJson
                ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                ?.resolverRecommendation
    }

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
        override suspend fun keepResolverRecommendationForSession(sessionId: String) {
            val recommendation = recommendationStore.loadResolverRecommendation(sessionId) ?: return
            resolverOverrideStore.setTemporaryOverride(
                DiagnosticsScanWorkflow.buildTemporaryResolverOverride(recommendation),
            )
        }

        override suspend fun saveResolverRecommendation(sessionId: String) {
            val recommendation = recommendationStore.loadResolverRecommendation(sessionId) ?: return
            val selectedPath = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
            appSettingsRepository.update {
                dnsMode = com.poyka.ripdpi.data.DnsModeEncrypted
                dnsProviderId = selectedPath.resolverId
                dnsIp = selectedPath.bootstrapIps.firstOrNull().orEmpty()
                dnsDohUrl = selectedPath.dohUrl
                clearDnsDohBootstrapIps()
                addAllDnsDohBootstrapIps(selectedPath.bootstrapIps)
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
            networkFingerprintProvider.capture()?.let { fingerprint ->
                networkDnsPathPreferenceStore.rememberPreferredPath(
                    fingerprint = fingerprint,
                    path = selectedPath,
                )
            }
            resolverOverrideStore.clear()
        }
    }
