package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AuditAssessmentPresenter
import com.poyka.ripdpi.activities.CandidateDetailMapper
import com.poyka.ripdpi.activities.DiagnosticsApproachDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsApproachRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1
import com.poyka.ripdpi.activities.StrategyProbeSuiteQuickV1
import com.poyka.ripdpi.activities.StrategyReportSummaryMapper
import com.poyka.ripdpi.activities.auditAssessmentMetrics
import com.poyka.ripdpi.activities.auditConfidenceLabel
import com.poyka.ripdpi.activities.auditConfidenceTone
import com.poyka.ripdpi.activities.formatDurationMs
import com.poyka.ripdpi.activities.successMetricTone
import com.poyka.ripdpi.activities.toDiagnosticsTone
import com.poyka.ripdpi.activities.toProbeResultUiModel
import com.poyka.ripdpi.activities.toSessionRowUiModel
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.util.Locale

private const val PercentageMultiplier = 100

private val strategyReportSummaryMapper = StrategyReportSummaryMapper()
private val candidateDetailMapper = CandidateDetailMapper()
private val auditAssessmentPresenter = AuditAssessmentPresenter()

internal fun DiagnosticsUiFactorySupport.toApproachDetailUiModel(
    detail: BypassApproachDetail,
): DiagnosticsApproachDetailUiModel =
    DiagnosticsApproachDetailUiModel(
        approach =
            toApproachRowUiModel(
                summary = detail.summary,
                mode =
                    when (detail.summary.approachId.kind) {
                        BypassApproachKind.Profile -> DiagnosticsApproachMode.Profiles
                        BypassApproachKind.Strategy -> DiagnosticsApproachMode.Strategies
                    },
            ),
        signature =
            buildList {
                detail.strategySignature?.let {
                    addAll(
                        strategySignatureFields(it),
                    )
                }
            }.toImmutableList(),
        breakdown =
            detail.summary.outcomeBreakdown
                .map { breakdown ->
                    DiagnosticsMetricUiModel(
                        label = breakdown.probeType,
                        value = "${breakdown.successCount}/${breakdown.failureCount}",
                        tone =
                            when {
                                breakdown.failureCount > 0 -> DiagnosticsTone.Warning
                                breakdown.successCount > 0 -> DiagnosticsTone.Positive
                                else -> DiagnosticsTone.Neutral
                            },
                    )
                }.toImmutableList(),
        runtimeSummary =
            persistentListOf(
                DiagnosticsMetricUiModel("Usage", detail.summary.usageCount.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel(
                    "Runtime",
                    formatDurationMs(detail.summary.totalRuntimeDurationMs),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    "Errors",
                    detail.summary.recentRuntimeHealth.totalErrors
                        .toString(),
                    DiagnosticsTone.Warning,
                ),
                DiagnosticsMetricUiModel(
                    "Route changes",
                    detail.summary.recentRuntimeHealth.routeChanges
                        .toString(),
                    DiagnosticsTone.Info,
                ),
            ),
        recentSessions = detail.recentValidatedSessions.map(::toSessionRowUiModel).toImmutableList(),
        recentUsageNotes =
            detail.recentUsageSessions
                .map { usage ->
                    "${usage.serviceMode} · ${usage.networkType} · ${
                        formatDurationMs(
                            (usage.finishedAt ?: usage.startedAt) - usage.startedAt,
                        )
                    }"
                }.toImmutableList(),
        failureNotes = detail.recentFailureNotes.toImmutableList(),
    )

internal fun DiagnosticsUiFactorySupport.toApproachRowUiModel(
    summary: BypassApproachSummary,
    mode: DiagnosticsApproachMode,
): DiagnosticsApproachRowUiModel =
    DiagnosticsApproachRowUiModel(
        id = summary.approachId.value,
        kind = mode,
        title = summary.displayName,
        subtitle = summary.secondaryLabel,
        verificationState = summary.uiVerificationLabel(context),
        lastValidatedResult =
            if (summary.currentStrategyAssessment != null) {
                summary.uiEvidenceNote(context)
            } else {
                summary.lastValidatedResult ?: context.getString(R.string.diagnostics_approach_unverified)
            },
        dominantFailurePattern =
            summary.topFailureOutcomes.firstOrNull()
                ?: context.getString(R.string.diagnostics_approach_no_dominant_failure),
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_approach_metric_validated),
                    value = summary.validatedScanCount.toString(),
                    tone = if (summary.validatedScanCount > 0) DiagnosticsTone.Info else DiagnosticsTone.Neutral,
                ),
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_approach_metric_success),
                    value =
                        summary.validatedSuccessRate
                            ?.let { context.getString(R.string.percent_value, (it * PercentageMultiplier).toInt()) }
                            ?: context.getString(R.string.diagnostics_approach_unverified),
                    tone = summary.successMetricTone(),
                ),
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_approach_metric_usage),
                    value = summary.usageCount.toString(),
                    tone = DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    label = context.getString(R.string.diagnostics_approach_metric_runtime),
                    value = formatDurationMs(summary.totalRuntimeDurationMs),
                    tone = DiagnosticsTone.Neutral,
                ),
            ).toImmutableList(),
        tone = summary.toDiagnosticsTone(),
    )

internal fun DiagnosticsUiFactorySupport.toStrategyProbeReportUiModel(
    report: StrategyProbeReport,
    reportResults: List<ProbeResult>,
    serviceMode: String?,
): DiagnosticsStrategyProbeReportUiModel {
    val candidateDetails =
        candidateDetailMapper.candidateDetails(
            factory = this,
            report = report,
            reportResults = reportResults,
            serviceMode = serviceMode,
        )
    val winningPath = report.buildStrategyProbeWinningPath(candidateDetails)

    return DiagnosticsStrategyProbeReportUiModel(
        suiteId = report.suiteId,
        suiteLabel = strategyProbeSuiteLabel(report.suiteId),
        summaryMetrics = strategyReportSummaryMapper.summaryMetrics(report).toImmutableList(),
        completionKind = report.completionKind,
        auditAssessment = report.auditAssessment,
        recommendation =
            report.recommendation?.let { recommendation ->
                toStrategyProbeRecommendationUiModel(
                    recommendation = recommendation,
                    completionKind = report.completionKind,
                )
            },
        winningPath = winningPath,
        families = strategyReportSummaryMapper.families(report),
        candidateDetails = candidateDetails.toImmutableMap(),
        presentation =
            auditAssessmentPresenter.reportPresentation(
                factory = this,
                report = report,
                winningPath = winningPath,
            ),
    )
}

internal fun DiagnosticsUiFactorySupport.buildStrategyProbeCandidateDetails(
    report: StrategyProbeReport,
    reportResults: List<ProbeResult>,
    serviceMode: String?,
): Map<String, DiagnosticsStrategyProbeCandidateDetailUiModel> {
    val reportResultsByCandidateId = reportResults.groupByStrategyProbeCandidateId()

    return (report.tcpCandidates + report.quicCandidates).associate { candidate ->
        candidate.id to
            toCandidateDetailUiModel(
                candidate = candidate,
                suiteId = report.suiteId,
                serviceMode = serviceMode,
                candidateResults = reportResultsByCandidateId[candidate.id].orEmpty(),
                recommended =
                    candidate.id == report.recommendation?.tcpCandidateId ||
                        candidate.id == report.recommendation?.quicCandidateId,
            )
    }
}

private fun StrategyProbeReport.buildStrategyProbeWinningPath(
    candidateDetails: Map<String, DiagnosticsStrategyProbeCandidateDetailUiModel>,
): DiagnosticsStrategyProbeWinningPathUiModel? {
    val recommendation = recommendation
    return if (recommendation != null &&
        suiteId == StrategyProbeSuiteFullMatrixV1 &&
        completionKind != StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED
    ) {
        val tcpWinner = candidateDetails[recommendation.tcpCandidateId]
        val quicWinner = candidateDetails[recommendation.quicCandidateId]
        if (tcpWinner != null && quicWinner != null) {
            DiagnosticsStrategyProbeWinningPathUiModel(
                tcpWinner =
                    tcpWinner.toWinningCandidateUiModel(
                        hiddenCandidateCount = (tcpCandidates.size - 1).coerceAtLeast(0),
                    ),
                quicWinner =
                    quicWinner.toWinningCandidateUiModel(
                        hiddenCandidateCount = (quicCandidates.size - 1).coerceAtLeast(0),
                    ),
                dnsLaneLabel = recommendation.dnsStrategyLabel,
            )
        } else {
            null
        }
    } else {
        null
    }
}

internal fun DiagnosticsUiFactorySupport.buildStrategyProbeReportPresentation(
    report: StrategyProbeReport,
    winningPath: DiagnosticsStrategyProbeWinningPathUiModel?,
): DiagnosticsStrategyProbeReportPresentationUiModel {
    val isFullAudit = report.suiteId == StrategyProbeSuiteFullMatrixV1
    val isDnsShortCircuited = report.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED
    val isPartialResults = report.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS
    val isIncomplete = isDnsShortCircuited || isPartialResults
    val supportsWinningPath = isFullAudit && !isIncomplete && winningPath != null
    val confidenceLabel =
        report.auditAssessment
            ?.confidence
            ?.level
            ?.let { auditConfidenceLabel(it, context) }
    val confidenceTone =
        report.auditAssessment
            ?.confidence
            ?.level
            ?.let(::auditConfidenceTone)
    return DiagnosticsStrategyProbeReportPresentationUiModel(
        statusLabel =
            strategyProbeStatusLabel(
                isDnsShortCircuited = isDnsShortCircuited,
                isPartialResults = isPartialResults,
                isFullAudit = isFullAudit,
            ),
        statusTone = if (isIncomplete) DiagnosticsTone.Warning else DiagnosticsTone.Positive,
        matrixTitle =
            strategyProbeMatrixTitle(
                report = report,
                isDnsShortCircuited = isDnsShortCircuited,
                isPartialResults = isPartialResults,
                isFullAudit = isFullAudit,
            ),
        manualApplyBadge = context.getString(R.string.diagnostics_profile_badge_manual_apply),
        supportsWinningPath = supportsWinningPath,
        isIncomplete = isIncomplete,
        showFullMatrixInitially = !supportsWinningPath,
        auditConfidenceLabel = confidenceLabel,
        auditConfidenceTone = confidenceTone,
        auditAssessmentMetrics =
            report.auditAssessment
                ?.let { auditAssessmentMetrics(it, context) }
                .orEmpty()
                .toImmutableList(),
    )
}

private fun DiagnosticsUiFactorySupport.strategyProbeStatusLabel(
    isDnsShortCircuited: Boolean,
    isPartialResults: Boolean,
    isFullAudit: Boolean,
): String =
    when {
        isDnsShortCircuited && isFullAudit -> context.getString(R.string.diagnostics_audit_short_circuit_title)
        isDnsShortCircuited -> context.getString(R.string.diagnostics_probe_short_circuit_title)
        isPartialResults && isFullAudit -> context.getString(R.string.diagnostics_audit_partial_results_title)
        isPartialResults -> context.getString(R.string.diagnostics_probe_partial_results_title)
        isFullAudit -> context.getString(R.string.diagnostics_audit_ready_title)
        else -> context.getString(R.string.diagnostics_probe_ready_title)
    }

private fun DiagnosticsUiFactorySupport.strategyProbeMatrixTitle(
    report: StrategyProbeReport,
    isDnsShortCircuited: Boolean,
    isPartialResults: Boolean,
    isFullAudit: Boolean,
): String =
    when {
        isDnsShortCircuited && isFullAudit -> {
            context.getString(R.string.diagnostics_audit_short_circuit_matrix_title)
        }

        isDnsShortCircuited -> {
            context.getString(R.string.diagnostics_probe_short_circuit_recommendation_title)
        }

        isPartialResults -> {
            val (executed, planned) = report.partialResultExecutionCounts()
            context.getString(R.string.diagnostics_partial_results_matrix_title, executed, planned)
        }

        isFullAudit -> {
            context.getString(R.string.diagnostics_audit_matrix_title)
        }

        else -> {
            context.getString(R.string.diagnostics_probe_recommendation_title)
        }
    }

private fun StrategyProbeReport.partialResultExecutionCounts(): Pair<Int, Int> {
    val coverage = auditAssessment?.coverage
    val executed = (coverage?.tcpCandidatesExecuted ?: 0) + (coverage?.quicCandidatesExecuted ?: 0)
    val planned = (coverage?.tcpCandidatesPlanned ?: 0) + (coverage?.quicCandidatesPlanned ?: 0)
    return executed to planned
}

private fun DiagnosticsStrategyProbeCandidateDetailUiModel.toWinningCandidateUiModel(
    hiddenCandidateCount: Int,
): DiagnosticsStrategyProbeWinningCandidateUiModel =
    DiagnosticsStrategyProbeWinningCandidateUiModel(
        id = id,
        label = label,
        familyLabel = familyLabel,
        outcome = outcome,
        rationale = rationale,
        metrics = metrics,
        tone = tone,
        hiddenCandidateCount = hiddenCandidateCount,
    )

internal fun DiagnosticsUiFactorySupport.toScopeLabel(
    request: DiagnosticsProfileProjection?,
    rawArgsEnabled: Boolean,
): String? =
    when (request?.kind) {
        ScanKind.STRATEGY_PROBE -> {
            when {
                rawArgsEnabled && request.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1 -> {
                    "Automatic audit · raw-path only · blocked by command-line mode"
                }

                rawArgsEnabled -> {
                    "Automatic probing · raw-path only · blocked by command-line mode"
                }

                request.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1 -> {
                    "Automatic audit · raw-path only"
                }

                else -> {
                    "Automatic probing · raw-path only"
                }
            }
        }

        ScanKind.CONNECTIVITY -> {
            "Connectivity profile"
        }

        null -> {
            null
        }
    }

private fun strategyProbeSuiteLabel(suiteId: String): String =
    when (suiteId) {
        StrategyProbeSuiteFullMatrixV1 -> "Automatic audit"
        StrategyProbeSuiteQuickV1 -> "Automatic probing"
        else -> suiteId.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun strategyProbeOutcomeLabel(
    outcome: String,
    skipped: Boolean,
): String =
    when {
        skipped -> "Skipped"
        outcome.equals("not_applicable", ignoreCase = true) -> "Not applicable"
        else -> outcome.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun strategyProbeCandidateTone(
    outcome: String,
    skipped: Boolean,
    recommended: Boolean,
): DiagnosticsTone =
    when {
        skipped -> DiagnosticsTone.Neutral
        recommended -> DiagnosticsTone.Positive
        outcome.equals("success", ignoreCase = true) -> DiagnosticsTone.Positive
        outcome.equals("partial", ignoreCase = true) -> DiagnosticsTone.Warning
        outcome.equals("not_applicable", ignoreCase = true) -> DiagnosticsTone.Neutral
        else -> DiagnosticsTone.Negative
    }

private fun strategyProbeFamilyLabel(family: String): String =
    strategyProbeFamilyLabels[family] ?: family.replace('_', ' ').replaceFirstChar { it.uppercase() }

private val strategyProbeFamilyLabels =
    mapOf(
        "baseline" to "Baseline",
        "parser" to "Parser",
        "parser_aggressive" to "Parser aggressive",
        "split" to "Host split",
        "ech_split" to "ECH extension split",
        "ech_tlsrec" to "ECH TLS record split",
        "tlsrec_split" to "TLS record split",
        "tlsrec_seqovl" to "TLS record sequence overlap",
        "tlsrec_fake" to "TLS record fake",
        "fake_flags" to "TCP flag crafting",
        "fake_approx" to "Fake approximation",
        "hostfake" to "Hostfake",
        "seqovl" to "Sequence overlap",
        "activation_window" to "Activation window",
        "adaptive_fake_ttl" to "Adaptive fake TTL",
        "fake_payload_library" to "Fake payload library",
        "quic_disabled" to "QUIC disabled",
        "quic_burst" to "QUIC burst",
    )

private fun parseServiceModeOrDefault(serviceMode: String?): Mode =
    when (serviceMode?.uppercase(Locale.US)) {
        "PROXY" -> Mode.Proxy
        "VPN" -> Mode.VPN
        else -> Mode.VPN
    }

private fun buildStrategyProbeCandidateMetrics(
    summary: StrategyProbeCandidateSummary,
    recommended: Boolean,
): List<DiagnosticsMetricUiModel> =
    buildList {
        add(DiagnosticsMetricUiModel("Targets", "${summary.succeededTargets}/${summary.totalTargets}"))
        add(DiagnosticsMetricUiModel("Weight", "${summary.weightedSuccessScore}/${summary.totalWeight}"))
        add(
            DiagnosticsMetricUiModel(
                "Quality",
                summary.qualityScore.toString(),
                tone =
                    when {
                        summary.qualityScore >= summary.totalTargets.coerceAtLeast(1) * 3 -> DiagnosticsTone.Positive
                        summary.qualityScore > 0 -> DiagnosticsTone.Warning
                        else -> DiagnosticsTone.Neutral
                    },
            ),
        )
        summary.averageLatencyMs?.let {
            add(DiagnosticsMetricUiModel("Latency", "$it ms", DiagnosticsTone.Info))
        }
        add(
            DiagnosticsMetricUiModel(
                "Emitter",
                strategyEmitterTierLabel(summary.emitterTier),
                tone =
                    when (summary.emitterTier) {
                        StrategyEmitterTier.NON_ROOT_PRODUCTION -> DiagnosticsTone.Info
                        StrategyEmitterTier.ROOTED_PRODUCTION -> DiagnosticsTone.Warning
                        StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY -> DiagnosticsTone.Neutral
                    },
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                "Realization",
                strategyEmitterRealizationLabel(summary),
                tone =
                    when {
                        summary.emitterDowngraded -> DiagnosticsTone.Warning
                        summary.exactEmitterRequiresRoot -> DiagnosticsTone.Info
                        else -> DiagnosticsTone.Neutral
                    },
            ),
        )
        if (recommended) {
            add(
                DiagnosticsMetricUiModel(
                    "Selected",
                    if (summary.skipped) "Fallback" else "Winner",
                    if (summary.skipped) DiagnosticsTone.Info else DiagnosticsTone.Positive,
                ),
            )
        }
    }

private fun strategyEmitterTierLabel(tier: StrategyEmitterTier): String =
    when (tier) {
        StrategyEmitterTier.NON_ROOT_PRODUCTION -> "Non-root production"
        StrategyEmitterTier.ROOTED_PRODUCTION -> "Rooted production"
        StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY -> "Lab diagnostics only"
    }

private fun strategyEmitterRealizationLabel(summary: StrategyProbeCandidateSummary): String =
    when {
        summary.emitterDowngraded -> "Approximate fallback"
        summary.exactEmitterRequiresRoot -> "Rooted exact"
        summary.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY -> "Lab path"
        else -> "Direct"
    }

private fun strategyEmitterNotes(summary: StrategyProbeCandidateSummary): List<String> =
    buildList {
        if (summary.exactEmitterRequiresRoot && !summary.emitterDowngraded) {
            add("Exact emission on this path required rooted runtime capabilities.")
        }
        if (summary.emitterDowngraded) {
            add("Exact emission was unavailable, so this candidate ran through an approximate fallback path.")
        }
        if (summary.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY) {
            add("This candidate stays in diagnostics-only coverage and is not part of production defaults.")
        }
    }

private fun DiagnosticsUiFactorySupport.deriveSignature(
    summary: StrategyProbeCandidateSummary,
    serviceMode: String?,
): BypassStrategySignature? =
    summary.proxyConfigJson
        ?.takeIf { it.isNotBlank() }
        ?.let { configJson ->
            deriveBypassStrategySignature(
                configJson = configJson,
                routeGroup = null,
                modeOverride = parseServiceModeOrDefault(serviceMode),
            )
        }

private fun ProbeResult.detailValue(key: String): String? = details.firstOrNull { it.key == key }?.value

private fun List<ProbeResult>.groupByStrategyProbeCandidateId(): Map<String, List<ProbeResult>> {
    val grouped = LinkedHashMap<String, MutableList<ProbeResult>>()
    for (result in this) {
        val candidateId = result.detailValue("candidateId") ?: continue
        grouped.getOrPut(candidateId) { mutableListOf() }.add(result)
    }
    return grouped
}

private fun DiagnosticsUiFactorySupport.buildStrategyProbeResultGroups(
    candidateResults: List<ProbeResult>,
): List<DiagnosticsProbeGroupUiModel> =
    candidateResults
        .mapIndexed { index, result ->
            toProbeResultUiModel(
                index = index,
                pathMode = ScanPathMode.RAW_PATH,
                result = result,
            )
        }.groupBy { probe ->
            probe.details
                .firstOrNull { it.label == "protocol" }
                ?.value
                ?.uppercase(Locale.US)
                ?.let { "$it results" }
                ?: probe.probeType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }.map { (title, items) ->
            DiagnosticsProbeGroupUiModel(title = title, items = items.toImmutableList())
        }.sortedBy { it.title }

internal fun StrategyProbeCandidateSummary.toCandidateUiModel(
    recommended: Boolean,
): DiagnosticsStrategyProbeCandidateUiModel =
    DiagnosticsStrategyProbeCandidateUiModel(
        id = id,
        label = label,
        outcome = strategyProbeOutcomeLabel(outcome = outcome, skipped = skipped),
        rationale = rationale,
        metrics = buildStrategyProbeCandidateMetrics(this, recommended).toImmutableList(),
        tone = strategyProbeCandidateTone(outcome = outcome, skipped = skipped, recommended = recommended),
        skipped = skipped,
        recommended = recommended,
    )

private fun DiagnosticsUiFactorySupport.toCandidateDetailUiModel(
    candidate: StrategyProbeCandidateSummary,
    suiteId: String,
    serviceMode: String?,
    candidateResults: List<ProbeResult>,
    recommended: Boolean,
): DiagnosticsStrategyProbeCandidateDetailUiModel =
    DiagnosticsStrategyProbeCandidateDetailUiModel(
        id = candidate.id,
        label = candidate.label,
        familyLabel = strategyProbeFamilyLabel(candidate.family),
        suiteLabel = strategyProbeSuiteLabel(suiteId),
        outcome = strategyProbeOutcomeLabel(outcome = candidate.outcome, skipped = candidate.skipped),
        rationale = candidate.rationale,
        tone =
            strategyProbeCandidateTone(
                outcome = candidate.outcome,
                skipped = candidate.skipped,
                recommended = recommended,
            ),
        recommended = recommended,
        notes = (candidate.notes + strategyEmitterNotes(candidate)).distinct().toImmutableList(),
        metrics = buildStrategyProbeCandidateMetrics(candidate, recommended).toImmutableList(),
        signature = deriveSignature(candidate, serviceMode)?.let(::strategySignatureFields).orEmpty().toImmutableList(),
        resultGroups =
            buildStrategyProbeResultGroups(
                candidateResults = candidateResults,
            ).toImmutableList(),
    )

private fun DiagnosticsUiFactorySupport.toStrategyProbeRecommendationUiModel(
    recommendation: StrategyProbeRecommendation,
    completionKind: StrategyProbeCompletionKind,
): DiagnosticsStrategyProbeRecommendationUiModel =
    if (completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED) {
        DiagnosticsStrategyProbeRecommendationUiModel(
            headline = context.getString(R.string.diagnostics_probe_short_circuit_recommendation_headline),
            rationale = recommendation.rationale,
            fields =
                listOf(
                    DiagnosticsFieldUiModel("TCP fallback", recommendation.tcpCandidateLabel),
                    recommendation.tcpCandidateFamily?.let {
                        DiagnosticsFieldUiModel("TCP/TLS lane", strategyProbeFamilyLabel(it))
                    },
                    DiagnosticsFieldUiModel("QUIC fallback", recommendation.quicCandidateLabel),
                    recommendation.quicCandidateFamily?.let {
                        DiagnosticsFieldUiModel("QUIC lane", strategyProbeFamilyLabel(it))
                    },
                    recommendation.dnsStrategyLabel?.let {
                        DiagnosticsFieldUiModel("DNS lane", it)
                    },
                    recommendation.tlsPathSuppressionSummary?.let {
                        DiagnosticsFieldUiModel("Suppression", it)
                    },
                    DiagnosticsFieldUiModel("Why trials stopped", recommendation.rationale),
                ).filterNotNull().toImmutableList(),
            signature =
                recommendation.strategySignature
                    ?.let { signature ->
                        strategySignatureFields(signature)
                    }.orEmpty()
                    .toImmutableList(),
        )
    } else {
        DiagnosticsStrategyProbeRecommendationUiModel(
            headline =
                listOfNotNull(
                    recommendation.tcpCandidateLabel,
                    recommendation.quicCandidateLabel,
                    recommendation.dnsStrategyLabel,
                ).joinToString(" + "),
            rationale = recommendation.rationale,
            fields =
                listOf(
                    DiagnosticsFieldUiModel("TCP recommendation", recommendation.tcpCandidateLabel),
                    recommendation.tcpCandidateFamily?.let {
                        DiagnosticsFieldUiModel("TCP/TLS lane", strategyProbeFamilyLabel(it))
                    },
                    DiagnosticsFieldUiModel("QUIC recommendation", recommendation.quicCandidateLabel),
                    recommendation.quicCandidateFamily?.let {
                        DiagnosticsFieldUiModel("QUIC lane", strategyProbeFamilyLabel(it))
                    },
                    recommendation.dnsStrategyLabel?.let {
                        DiagnosticsFieldUiModel("DNS lane", it)
                    },
                    recommendation.tlsPathSuppressionSummary?.let {
                        DiagnosticsFieldUiModel("Suppression", it)
                    },
                    DiagnosticsFieldUiModel("Why it won", recommendation.rationale),
                ).filterNotNull().toImmutableList(),
            signature =
                recommendation.strategySignature
                    ?.let { signature ->
                        strategySignatureFields(signature)
                    }.orEmpty()
                    .toImmutableList(),
        )
    }
