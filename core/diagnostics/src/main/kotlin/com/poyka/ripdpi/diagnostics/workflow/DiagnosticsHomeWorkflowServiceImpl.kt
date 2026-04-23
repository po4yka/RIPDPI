@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.applyToSettings
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.WarpEndpointSelectionManual
import com.poyka.ripdpi.data.WarpRouteModeRules
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.tlsFingerprintProfileSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal data class StrategyApplyResult(
    val recommendation: StrategyProbeRecommendation,
    val appliedSettings: List<DiagnosticsAppliedSetting>,
)

@Singleton
internal class DiagnosticsHomeRecommendationApplier
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        internal suspend fun applyValidatedRecommendation(strategyProbe: StrategyProbeReport?): StrategyApplyResult? =
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
                                    preferences = preferences,
                                ),
                        )
                    }
                }

        internal fun buildStrategyRecommendationAppliedSettings(
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

        private fun buildStrategyAppliedSettings(
            recommendation: StrategyProbeRecommendation,
            report: StrategyProbeReport,
            preferences: RipDpiProxyUIPreferences,
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
                add(DiagnosticsAppliedSetting(label = "Chain", value = preferences.chainSummary))
                addAll(buildDetectionResistanceAppliedSettings(preferences))
                addAll(buildWarpAppliedSettings(preferences.warp))
            }

        private fun buildDetectionResistanceAppliedSettings(
            preferences: RipDpiProxyUIPreferences,
        ): List<DiagnosticsAppliedSetting> =
            buildList {
                if (preferences.fakePackets.tlsFingerprintProfile != TlsFingerprintProfileChromeStable) {
                    add(
                        DiagnosticsAppliedSetting(
                            label = "TLS fingerprint",
                            value = tlsFingerprintProfileSummary(preferences.fakePackets.tlsFingerprintProfile),
                        ),
                    )
                }
                if (preferences.fakePackets.entropyMode != "disabled") {
                    add(
                        DiagnosticsAppliedSetting(
                            label = "Traffic morphing",
                            value =
                                "${preferences.fakePackets.entropyMode} · " +
                                    "pad ${preferences.fakePackets.entropyPaddingTargetPermil} · " +
                                    "shannon ${preferences.fakePackets.shannonEntropyTargetPermil}",
                        ),
                    )
                    add(
                        DiagnosticsAppliedSetting(
                            label = "Morphing budget",
                            value = "${preferences.fakePackets.entropyPaddingMax} bytes",
                        ),
                    )
                }
                if (preferences.adaptiveFallback.strategyEvolution) {
                    add(
                        DiagnosticsAppliedSetting(
                            label = "Strategy evolution",
                            value = "Epsilon ${"%.2f".format(preferences.adaptiveFallback.evolutionEpsilon)}",
                        ),
                    )
                }
                if (preferences.fakePackets.quicBindLowPort || preferences.fakePackets.quicMigrateAfterHandshake) {
                    val quicSummary =
                        buildList {
                            if (preferences.fakePackets.quicBindLowPort) add("low-port bind")
                            if (preferences.fakePackets.quicMigrateAfterHandshake) add("post-handshake UDP rebind")
                        }.joinToString(separator = " · ")
                    add(DiagnosticsAppliedSetting(label = "QUIC resistance", value = quicSummary))
                }
            }

        private fun buildWarpAppliedSettings(warp: RipDpiWarpConfig): List<DiagnosticsAppliedSetting> =
            buildList {
                if (!warp.enabled) {
                    return@buildList
                }
                add(
                    DiagnosticsAppliedSetting(
                        label = "WARP routing",
                        value =
                            when (warp.routeMode) {
                                WarpRouteModeRules -> "Rules"
                                else -> "Off"
                            },
                    ),
                )
                summarizeWarpHostlist(warp.routeHosts)?.let { summary ->
                    add(DiagnosticsAppliedSetting(label = "WARP hostlist", value = summary))
                }
                add(
                    DiagnosticsAppliedSetting(
                        label = "WARP control-plane",
                        value =
                            if (warp.builtInRulesEnabled) {
                                "Built-in exclusions enabled"
                            } else {
                                "Off"
                            },
                    ),
                )
                add(
                    DiagnosticsAppliedSetting(
                        label = "WARP endpoint",
                        value =
                            if (warp.endpointSelectionMode == WarpEndpointSelectionManual) {
                                buildManualWarpEndpointLabel(warp)
                            } else {
                                "Automatic"
                            },
                    ),
                )
                if (warp.endpointSelectionMode != WarpEndpointSelectionManual) {
                    add(
                        DiagnosticsAppliedSetting(
                            label = "WARP scanner",
                            value =
                                if (warp.scannerEnabled) {
                                    "${warp.scannerParallelism} parallel · ${warp.scannerMaxRttMs} ms max RTT"
                                } else {
                                    "Disabled"
                                },
                        ),
                    )
                }
                if (warp.amnezia.enabled) {
                    add(
                        DiagnosticsAppliedSetting(
                            label = "WARP AmneziaWG",
                            value =
                                "JC ${warp.amnezia.jc} · Jmin ${warp.amnezia.jmin} · " +
                                    "Jmax ${warp.amnezia.jmax}",
                        ),
                    )
                }
            }

        private fun summarizeWarpHostlist(routeHosts: String): String? {
            val hosts =
                routeHosts
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            return when (hosts.size) {
                0 -> null
                1 -> hosts.first()
                else -> "${hosts.size} hosts"
            }
        }

        private fun buildManualWarpEndpointLabel(warp: RipDpiWarpConfig): String {
            val address =
                sequenceOf(
                    warp.manualEndpoint.host,
                    warp.manualEndpoint.ipv4,
                    warp.manualEndpoint.ipv6,
                ).map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
            return if (address != null) {
                "$address:${warp.manualEndpoint.port}"
            } else {
                "Manual port ${warp.manualEndpoint.port}"
            }
        }

        private fun String.toHumanLabel(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

@Singleton
internal class DiagnosticsHomeResolverActionCoordinator
    @Inject
    constructor(
        private val resolverActions: DiagnosticsResolverActions,
    ) {
        internal suspend fun applyResolverRecommendation(
            sessionId: String,
            strategyApplied: StrategyApplyResult?,
            resolverRecommendation: ResolverRecommendation?,
        ): List<DiagnosticsAppliedSetting> =
            if (strategyApplied == null && resolverRecommendation?.persistable == true) {
                resolverActions.saveResolverRecommendation(sessionId)
                buildResolverAppliedSettings(resolverRecommendation)
            } else {
                emptyList()
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
    }

@Singleton
internal class DiagnosticsHomeCapabilityEvidenceSummarizer
    @Inject
    constructor(
        private val serverCapabilityStore: ServerCapabilityStore,
    ) {
        internal suspend fun summarize(fingerprintHash: String?): List<DiagnosticsCapabilityEvidence> =
            if (fingerprintHash != null) {
                summarizeCapabilityEvidence(
                    serverCapabilityStore.directPathCapabilitiesForFingerprint(fingerprintHash),
                )
            } else {
                emptyList()
            }
    }

@Singleton
internal class DiagnosticsHomeAuditOutcomeBuilder
    @Inject
    constructor() {
        internal fun build(
            sessionId: String,
            fingerprintHash: String?,
            session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
            report: ScanReport,
            strategyProbe: StrategyProbeReport?,
            strategyApplied: StrategyApplyResult?,
            strategyRecommendation: StrategyRecommendation?,
            resolverRecommendation: ResolverRecommendation?,
            resolverApplied: List<DiagnosticsAppliedSetting>,
            strategyRecommendationApplied: List<DiagnosticsAppliedSetting>,
            capabilityEvidence: List<DiagnosticsCapabilityEvidence>,
        ): DiagnosticsHomeAuditOutcome {
            val allApplied = resolveAppliedSettings(strategyApplied, resolverApplied, strategyRecommendationApplied)
            val actionable =
                strategyApplied != null ||
                    resolverApplied.isNotEmpty() ||
                    strategyRecommendationApplied.isNotEmpty()
            val assessment = strategyProbe?.auditAssessment
            val strategyAdequacy =
                resolveStrategyAdequacy(strategyApplied, strategyRecommendationApplied, resolverApplied, strategyProbe)
            val coverageSummary =
                assessment?.let {
                    "Matrix ${it.coverage.matrixCoveragePercent}%" +
                        " · winners ${it.coverage.winnerCoveragePercent}%"
                }
            return DiagnosticsHomeAuditOutcome(
                sessionId = sessionId,
                fingerprintHash = fingerprintHash,
                actionable = actionable,
                headline =
                    buildAuditHeadline(
                        strategyApplied,
                        strategyAdequacy,
                        actionable,
                        resolverApplied,
                        strategyProbe,
                    ),
                summary = session.displaySummary(report),
                confidenceSummary =
                    assessment?.let {
                        "Confidence ${it.confidence.level.name.lowercase()} (${it.confidence.score})"
                    },
                coverageSummary = coverageSummary,
                recommendationSummary =
                    buildAuditRecommendationSummary(
                        strategyApplied,
                        strategyRecommendation,
                        resolverRecommendation,
                    ),
                appliedSettings = allApplied,
                capabilityEvidence = capabilityEvidence,
                strategyAdequacy = strategyAdequacy,
                directModeVerdict = report.directModeVerdict,
            )
        }

        private fun resolveStrategyAdequacy(
            strategyApplied: StrategyApplyResult?,
            strategyRecommendationApplied: List<DiagnosticsAppliedSetting>,
            resolverApplied: List<DiagnosticsAppliedSetting>,
            strategyProbe: StrategyProbeReport?,
        ): StrategyAdequacy? =
            when {
                strategyApplied != null -> StrategyAdequacy.STRATEGY_APPLIED
                strategyRecommendationApplied.isNotEmpty() -> StrategyAdequacy.STRATEGY_RECOMMENDED
                strategyProbe != null && allTcpCandidatesFailed(strategyProbe) -> StrategyAdequacy.ALL_CANDIDATES_FAILED
                resolverApplied.isNotEmpty() -> StrategyAdequacy.DNS_ONLY_APPLIED
                strategyProbe == null -> StrategyAdequacy.NO_STRATEGY_PROBE
                else -> null
            }

        private fun resolveAppliedSettings(
            strategyApplied: StrategyApplyResult?,
            resolverApplied: List<DiagnosticsAppliedSetting>,
            strategyRecommendationApplied: List<DiagnosticsAppliedSetting>,
        ): List<DiagnosticsAppliedSetting> =
            strategyApplied?.appliedSettings ?: (resolverApplied + strategyRecommendationApplied)

        private fun allTcpCandidatesFailed(report: StrategyProbeReport): Boolean =
            report.tcpCandidates.isNotEmpty() &&
                report.tcpCandidates.none { it.succeededTargets > 0 && !it.skipped }

        private fun buildAuditHeadline(
            strategyApplied: StrategyApplyResult?,
            strategyAdequacy: StrategyAdequacy?,
            actionable: Boolean,
            resolverApplied: List<DiagnosticsAppliedSetting>,
            strategyProbe: StrategyProbeReport?,
        ): String =
            when {
                strategyApplied != null -> {
                    "Analysis complete and settings applied"
                }

                strategyAdequacy == StrategyAdequacy.ALL_CANDIDATES_FAILED && resolverApplied.isNotEmpty() -> {
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

                strategyProbe?.completionKind == StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK -> {
                    "Analysis complete with DNS fallback"
                }

                else -> {
                    "Analysis complete, but no settings were applied"
                }
            }

        private fun buildAuditRecommendationSummary(
            strategyApplied: StrategyApplyResult?,
            strategyRecommendation: StrategyRecommendation?,
            resolverRecommendation: ResolverRecommendation?,
        ): String? =
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
            }
    }

@Singleton
internal class DefaultDiagnosticsHomeWorkflowService
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactQueryStore: DiagnosticsArtifactQueryStore,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val recommendationApplier: DiagnosticsHomeRecommendationApplier,
        private val resolverActionCoordinator: DiagnosticsHomeResolverActionCoordinator,
        private val capabilityEvidenceSummarizer: DiagnosticsHomeCapabilityEvidenceSummarizer,
        private val auditOutcomeBuilder: DiagnosticsHomeAuditOutcomeBuilder,
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
                        ?: artifactQueryStore
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
                val strategyRecommendation = report.strategyRecommendation
                val strategyApplied = recommendationApplier.applyValidatedRecommendation(strategyProbe)
                val resolverApplied =
                    resolverActionCoordinator.applyResolverRecommendation(
                        sessionId = sessionId,
                        strategyApplied = strategyApplied,
                        resolverRecommendation = resolverRecommendation,
                    )
                val strategyRecommendationApplied =
                    if (strategyApplied == null && strategyRecommendation?.actionable == true) {
                        recommendationApplier.buildStrategyRecommendationAppliedSettings(strategyRecommendation)
                    } else {
                        emptyList()
                    }
                val capabilityEvidence = capabilityEvidenceSummarizer.summarize(fingerprintHash)

                auditOutcomeBuilder.build(
                    sessionId = sessionId,
                    fingerprintHash = fingerprintHash,
                    session = session,
                    report = report,
                    strategyProbe = strategyProbe,
                    strategyApplied = strategyApplied,
                    strategyRecommendation = strategyRecommendation,
                    resolverRecommendation = resolverRecommendation,
                    resolverApplied = resolverApplied,
                    strategyRecommendationApplied = strategyRecommendationApplied,
                    capabilityEvidence = capabilityEvidence,
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
                    summary = session.displaySummary(report),
                    detail = report.diagnoses.firstOrNull()?.summary,
                )
            }
    }
