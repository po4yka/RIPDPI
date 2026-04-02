package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.applyToSettings
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
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
            Logger.w(error) { "Runtime history bootstrap skipped" }
        }
    }

@Singleton
class DefaultDiagnosticsDetailLoader
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val mapper: DiagnosticsBoundaryMapper,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsDetailLoader {
        constructor(
            scanRecordStore: DiagnosticsScanRecordStore,
            artifactReadStore: DiagnosticsArtifactReadStore,
            bypassUsageHistoryStore: BypassUsageHistoryStore,
            json: Json,
        ) : this(
            scanRecordStore = scanRecordStore,
            artifactReadStore = artifactReadStore,
            bypassUsageHistoryStore = bypassUsageHistoryStore,
            mapper = DiagnosticsBoundaryMapper(json),
            json = json,
        )

        override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
            DiagnosticsSessionQueries.loadSessionDetail(sessionId, scanRecordStore, artifactReadStore, mapper)

        override suspend fun loadApproachDetail(
            kind: BypassApproachKind,
            id: String,
        ): BypassApproachDetail =
            DiagnosticsSessionQueries.loadApproachDetail(
                kind = kind,
                id = id,
                scanRecordStore = scanRecordStore,
                bypassUsageHistoryStore = bypassUsageHistoryStore,
                mapper = mapper,
                json = json,
            )
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

        override suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive =
            withContext(Dispatchers.IO) { archiveExporter.createArchive(request) }
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
class DefaultDiagnosticsHomeWorkflowService
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val resolverActions: DiagnosticsResolverActions,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsHomeWorkflowService {
        override suspend fun currentFingerprintHash(): String? = networkFingerprintProvider.capture()?.scopeKey()

        override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
            withContext(Dispatchers.IO) {
                val session = scanRecordStore.getScanSession(sessionId)
                val report = session?.reportJson?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                val fingerprintHash =
                    session?.triggerCurrentFingerprintHash
                        ?: artifactReadStore
                            .getNativeEventsForSession(sessionId, limit = 80)
                            .lastOrNull { !it.fingerprintHash.isNullOrBlank() }
                            ?.fingerprintHash
                        ?: currentFingerprintHash()

                if (session == null || report == null) {
                    return@withContext DiagnosticsHomeAuditOutcome(
                        sessionId = sessionId,
                        fingerprintHash = fingerprintHash,
                        actionable = false,
                        headline = "Analysis finished without a reusable result",
                        summary = session?.summary ?: "Diagnostics session could not be loaded.",
                    )
                }

                val strategyProbe = report.strategyProbeReport
                val resolverRecommendation = report.resolverRecommendation
                val strategyApplied =
                    strategyProbe
                        ?.takeIf {
                            DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(it) ==
                                DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible
                        }?.recommendation
                        ?.let { recommendation ->
                            decodeRipDpiProxyUiPreferences(
                                recommendation.recommendedProxyConfigJson,
                            )?.let { preferences ->
                                val settingsBefore = appSettingsRepository.snapshot()
                                appSettingsRepository.replace(preferences.applyToSettings(settingsBefore))
                                StrategyApplyResult(
                                    recommendation = recommendation,
                                    appliedSettings =
                                        buildStrategyAppliedSettings(
                                            recommendation = recommendation,
                                            report = strategyProbe,
                                            chainSummary = preferences.chainSummary,
                                        ),
                                )
                            }
                        }
                val strategyRecommendation = report.strategyRecommendation
                val resolverApplied =
                    if (strategyApplied == null && resolverRecommendation?.persistable == true) {
                        resolverActions.saveResolverRecommendation(sessionId)
                        buildResolverAppliedSettings(resolverRecommendation)
                    } else {
                        emptyList()
                    }
                val strategyRecommendationApplied =
                    if (strategyApplied == null && strategyRecommendation?.actionable == true) {
                        buildStrategyRecommendationAppliedSettings(strategyRecommendation)
                    } else {
                        emptyList()
                    }
                val allApplied =
                    strategyApplied?.appliedSettings
                        ?: (resolverApplied + strategyRecommendationApplied)
                val actionable =
                    strategyApplied != null ||
                        resolverApplied.isNotEmpty() ||
                        strategyRecommendationApplied.isNotEmpty()
                val assessment = strategyProbe?.auditAssessment
                val strategyAdequacy =
                    when {
                        strategyApplied != null -> {
                            StrategyAdequacy.STRATEGY_APPLIED
                        }

                        strategyRecommendationApplied.isNotEmpty() -> {
                            StrategyAdequacy.STRATEGY_RECOMMENDED
                        }

                        strategyProbe != null && allTcpCandidatesFailed(strategyProbe) -> {
                            StrategyAdequacy.ALL_CANDIDATES_FAILED
                        }

                        resolverApplied.isNotEmpty() -> {
                            StrategyAdequacy.DNS_ONLY_APPLIED
                        }

                        strategyProbe == null -> {
                            StrategyAdequacy.NO_STRATEGY_PROBE
                        }

                        else -> {
                            null
                        }
                    }

                DiagnosticsHomeAuditOutcome(
                    sessionId = sessionId,
                    fingerprintHash = fingerprintHash,
                    actionable = actionable,
                    headline =
                        when {
                            strategyApplied != null -> {
                                "Analysis complete and settings applied"
                            }

                            strategyAdequacy == StrategyAdequacy.ALL_CANDIDATES_FAILED &&
                                resolverApplied.isNotEmpty() -> {
                                "DNS settings applied, but all bypass strategies failed"
                            }

                            strategyAdequacy == StrategyAdequacy.ALL_CANDIDATES_FAILED -> {
                                "All bypass strategies failed on this network"
                            }

                            actionable -> {
                                "Analysis complete and settings applied"
                            }

                            strategyProbe?.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
                                "Analysis complete, but only DNS evidence was available"
                            }

                            strategyProbe?.completionKind ==
                                StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK -> {
                                "Analysis complete with DNS fallback"
                            }

                            else -> {
                                "Analysis complete, but no settings were applied"
                            }
                        },
                    summary = report.summary.ifBlank { session.summary },
                    confidenceSummary =
                        assessment?.let {
                            "Confidence ${it.confidence.level.name.lowercase()} (${it.confidence.score})"
                        },
                    coverageSummary =
                        assessment?.let {
                            "Matrix ${it.coverage.matrixCoveragePercent}% · winners ${it.coverage.winnerCoveragePercent}%"
                        },
                    recommendationSummary =
                        when {
                            strategyApplied != null -> {
                                "${strategyApplied.recommendation.tcpCandidateLabel} + " +
                                    strategyApplied.recommendation.quicCandidateLabel
                            }

                            strategyRecommendation != null -> {
                                strategyRecommendation.rationale
                            }

                            resolverRecommendation != null -> {
                                resolverRecommendation.rationale
                            }

                            else -> {
                                null
                            }
                        },
                    appliedSettings = allApplied,
                    strategyAdequacy = strategyAdequacy,
                )
            }

        override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
            withContext(Dispatchers.IO) {
                val session = scanRecordStore.getScanSession(sessionId)
                val report = session?.reportJson?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                if (session == null || report == null) {
                    return@withContext DiagnosticsHomeVerificationOutcome(
                        sessionId = sessionId,
                        success = false,
                        headline = "VPN verification was incomplete",
                        summary = session?.summary ?: "Verification session could not be loaded.",
                    )
                }

                val normalizedOutcomes = report.results.map { it.outcome.lowercase() }
                val successCount =
                    normalizedOutcomes.count { outcome ->
                        outcome.contains("ok") || outcome == "http_redirect" || outcome == "tls_version_split"
                    }
                val failureCount =
                    normalizedOutcomes.count { outcome ->
                        outcome.contains("blocked") ||
                            outcome.contains("unreachable") ||
                            outcome.contains("failed") ||
                            outcome.contains("error") ||
                            outcome.contains("timeout")
                    }
                val connectivityIssue = report.diagnoses.any { it.code == "network_connectivity_issue" }
                val success = !connectivityIssue && successCount > 0 && successCount >= failureCount

                DiagnosticsHomeVerificationOutcome(
                    sessionId = sessionId,
                    success = success,
                    headline =
                        if (success) {
                            "VPN access confirmed"
                        } else {
                            "VPN started, but access is still limited"
                        },
                    summary = report.summary.ifBlank { session.summary },
                    detail = report.diagnoses.firstOrNull()?.summary,
                )
            }

        private data class StrategyApplyResult(
            val recommendation: StrategyProbeRecommendation,
            val appliedSettings: List<DiagnosticsAppliedSetting>,
        )

        private fun buildStrategyAppliedSettings(
            recommendation: StrategyProbeRecommendation,
            report: StrategyProbeReport,
            chainSummary: String,
        ): List<DiagnosticsAppliedSetting> =
            buildList {
                val strategySignature = recommendation.strategySignature
                val tcpLabel =
                    strategySignature?.tcpStrategyFamily?.toHumanLabel()
                        ?: report.tcpCandidates
                            .firstOrNull { it.id == recommendation.tcpCandidateId }
                            ?.family
                            ?.toHumanLabel()
                val quicLabel =
                    strategySignature?.quicStrategyFamily?.toHumanLabel()
                        ?: report.quicCandidates
                            .firstOrNull { it.id == recommendation.quicCandidateId }
                            ?.family
                            ?.toHumanLabel()
                tcpLabel?.let {
                    add(DiagnosticsAppliedSetting(label = "TCP/TLS lane", value = it))
                }
                quicLabel?.let {
                    add(DiagnosticsAppliedSetting(label = "QUIC lane", value = it))
                }
                recommendation.dnsStrategyLabel?.let {
                    add(DiagnosticsAppliedSetting(label = "DNS lane", value = it))
                }
                add(DiagnosticsAppliedSetting(label = "Chain", value = chainSummary))
            }

        private fun buildResolverAppliedSettings(
            recommendation: ResolverRecommendation,
        ): List<DiagnosticsAppliedSetting> =
            listOf(
                DiagnosticsAppliedSetting(
                    label = "Resolver",
                    value = recommendation.selectedResolverId,
                ),
                DiagnosticsAppliedSetting(
                    label = "Protocol",
                    value = recommendation.selectedProtocol.uppercase(),
                ),
            )

        private fun buildStrategyRecommendationAppliedSettings(
            recommendation: StrategyRecommendation,
        ): List<DiagnosticsAppliedSetting> =
            listOf(
                DiagnosticsAppliedSetting(
                    label = "Strategy recommendation",
                    value = recommendation.recommendedFamily.toHumanLabel(),
                ),
                DiagnosticsAppliedSetting(
                    label = "Blocking pattern",
                    value = recommendation.blockingPattern.toHumanLabel(),
                ),
            )

        private fun allTcpCandidatesFailed(report: StrategyProbeReport): Boolean =
            report.tcpCandidates.isNotEmpty() &&
                report.tcpCandidates.none { it.succeededTargets > 0 && !it.skipped }

        private fun String.toHumanLabel(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }
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
