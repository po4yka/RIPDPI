@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
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
        internal suspend fun applyValidatedRecommendation(strategyProbe: StrategyProbeReport?): StrategyApplyResult? {
            val recommendation = strategyProbe?.recommendation
            if (strategyProbe == null || recommendation == null ||
                DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(strategyProbe) !=
                DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Eligible
            ) {
                return null
            }
            var appliedSettings: com.poyka.ripdpi.proto.AppSettings? = null
            // Validate and apply against one current value, so concurrent settings changes are preserved.
            appSettingsRepository.update {
                appliedSettings = DiagnosticsScanWorkflow.validatedRecommendationSettings(strategyProbe, build())
                appliedSettings?.let { clear().mergeFrom(it) }
            }
            return appliedSettings?.let { settings ->
                StrategyApplyResult(
                    recommendation = recommendation,
                    appliedSettings =
                        buildStrategyAppliedSettings(
                            recommendation = recommendation,
                            report = strategyProbe,
                            preferences = RipDpiProxyUIPreferences.fromSettings(settings),
                        ),
                )
            }
        }

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
                tcpLabel?.takeIf { recommendation.tcpCandidateId != null }?.let {
                    add(DiagnosticsAppliedSetting(label = "TCP/TLS lane", value = it))
                }
                quicLabel?.takeIf { recommendation.quicCandidateId != null }?.let {
                    add(DiagnosticsAppliedSetting(label = "QUIC lane", value = it))
                }
                recommendation.dnsStrategyLabel?.let {
                    add(DiagnosticsAppliedSetting(label = "DNS lane", value = it))
                }
                add(DiagnosticsAppliedSetting(label = "Chain", value = preferences.chainSummary))
                addAll(buildDetectionResistanceAppliedSettings(preferences))
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

        private fun String.toHumanLabel(): String =
            HumanLabelOverrides[this] ?: replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private val HumanLabelOverrides: Map<String, String> =
    mapOf(
        "tlsrec_split" to "TLS record split",
        "tlsrec_fake" to "TLS record split + fake",
        "sni_tls_suspect" to "SNI/TLS suspected",
    )

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
            capabilityEvidence: List<DiagnosticsCapabilityEvidence>,
        ): DiagnosticsHomeAuditOutcome {
            val allApplied = resolveAppliedSettings(strategyApplied, resolverApplied)
            val actionable = strategyApplied != null || resolverApplied.isNotEmpty()
            val assessment = strategyProbe?.auditAssessment
            val strategyAdequacy =
                resolveStrategyAdequacy(strategyApplied, strategyRecommendation, resolverApplied, strategyProbe)
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
            strategyRecommendation: StrategyRecommendation?,
            resolverApplied: List<DiagnosticsAppliedSetting>,
            strategyProbe: StrategyProbeReport?,
        ): StrategyAdequacy? =
            when {
                strategyApplied != null -> StrategyAdequacy.STRATEGY_APPLIED
                strategyRecommendation?.actionable == true -> StrategyAdequacy.STRATEGY_RECOMMENDED
                strategyProbe != null && allCandidatesFailed(strategyProbe) -> StrategyAdequacy.ALL_CANDIDATES_FAILED
                resolverApplied.isNotEmpty() -> StrategyAdequacy.DNS_ONLY_APPLIED
                strategyProbe == null -> StrategyAdequacy.NO_STRATEGY_PROBE
                else -> null
            }

        private fun resolveAppliedSettings(
            strategyApplied: StrategyApplyResult?,
            resolverApplied: List<DiagnosticsAppliedSetting>,
        ): List<DiagnosticsAppliedSetting> = strategyApplied?.appliedSettings ?: resolverApplied

        private fun allCandidatesFailed(report: StrategyProbeReport): Boolean {
            val executedCandidates =
                (report.tcpCandidates + report.quicCandidates)
                    .filterNot { it.skipped }
            return executedCandidates.isNotEmpty() &&
                executedCandidates.none { it.succeededTargets > 0 }
        }

        private fun hasUnverifiedStrategyWinner(report: StrategyProbeReport?): Boolean =
            report?.let { strategyProbe ->
                strategyProbe.recommendation?.let { recommendation ->
                    val winningCandidates =
                        listOfNotNull(
                            strategyProbe.tcpCandidates.firstOrNull { it.id == recommendation.tcpCandidateId },
                            strategyProbe.quicCandidates.firstOrNull { it.id == recommendation.quicCandidateId },
                        )
                    winningCandidates.any { it.succeededTargets > 0 } &&
                        DiagnosticsScanWorkflow.evaluateBackgroundAutoPersistEligibility(strategyProbe) is
                            DiagnosticsScanWorkflow.BackgroundAutoPersistEligibility.Rejected
                } ?: false
            } ?: false

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

                strategyAdequacy == StrategyAdequacy.DNS_ONLY_APPLIED && hasUnverifiedStrategyWinner(strategyProbe) -> {
                    "DNS settings applied, but bypass strategy needs more evidence"
                }

                strategyAdequacy == StrategyAdequacy.DNS_ONLY_APPLIED -> {
                    "DNS settings applied"
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
                    listOfNotNull(
                        strategyApplied.recommendation.tcpCandidateLabel,
                        strategyApplied.recommendation.quicCandidateLabel,
                    ).joinToString(" + ")
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
                val complete = session.hasCompleteHomeReport(sessionId, report)
                val strategyApplied =
                    if (complete) recommendationApplier.applyValidatedRecommendation(strategyProbe) else null
                val resolverApplied =
                    if (complete) {
                        resolverActionCoordinator.applyResolverRecommendation(
                            sessionId = sessionId,
                            strategyApplied = strategyApplied,
                            resolverRecommendation = resolverRecommendation,
                        )
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
                    capabilityEvidence = capabilityEvidence,
                )
            }

        override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
            withContext(Dispatchers.IO) {
                val session = scanRecordStore.getScanSession(sessionId)
                val report = session?.reportJson?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }
                summarizeHomeVerification(sessionId, session, report)
            }
    }
