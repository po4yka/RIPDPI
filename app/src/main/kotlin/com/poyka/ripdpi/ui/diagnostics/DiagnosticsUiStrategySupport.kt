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
import com.poyka.ripdpi.activities.localizedDisplayName
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
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.util.Locale

private const val PercentageMultiplier = 100

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
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_approach_metric_usage),
                    detail.summary.usageCount.toString(),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_approach_metric_runtime),
                    formatDurationMs(detail.summary.totalRuntimeDurationMs),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_metric_errors),
                    detail.summary.recentRuntimeHealth.totalErrors
                        .toString(),
                    DiagnosticsTone.Warning,
                ),
                DiagnosticsMetricUiModel(
                    context.getString(R.string.diagnostics_metric_route_changes),
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
        title = summary.localizedDisplayName(context),
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
    sessionId: String? = null,
): DiagnosticsStrategyProbeReportUiModel {
    val strategyReportSummaryMapper = StrategyReportSummaryMapper(context)
    val candidateDetails =
        candidateDetailMapper.candidateDetails(
            factory = this,
            report = report,
            reportResults = reportResults,
            serviceMode = serviceMode,
            sessionId = sessionId,
        )
    val winningPath = report.buildStrategyProbeWinningPath(candidateDetails, context)

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
    sessionId: String? = null,
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
                sessionId = sessionId,
            )
    }
}

private fun StrategyProbeReport.buildStrategyProbeWinningPath(
    candidateDetails: Map<String, DiagnosticsStrategyProbeCandidateDetailUiModel>,
    stringResolver: StringResolver,
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
                dnsLaneLabel = recommendation.dnsStrategyLabel?.let(StrategyProbeValueLabels(stringResolver)::dnsLane),
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
            context.getQuantityString(R.plurals.diagnostics_partial_results_matrix_title, planned, executed, planned)
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
            val workflowLabel =
                context.getString(
                    if (request.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1) {
                        R.string.diagnostics_scan_automatic_audit
                    } else {
                        R.string.diagnostics_scan_automatic_probing
                    },
                )
            context.getString(
                if (rawArgsEnabled) {
                    R.string.diagnostics_scope_strategy_blocked_format
                } else {
                    R.string.diagnostics_scope_strategy_format
                },
                workflowLabel,
            )
        }

        // The card title already names the connectivity profile.
        ScanKind.CONNECTIVITY, null -> {
            null
        }
    }

private fun DiagnosticsUiFactorySupport.strategyProbeSuiteLabel(suiteId: String): String =
    when (suiteId) {
        StrategyProbeSuiteFullMatrixV1 -> context.getString(R.string.diagnostics_scan_automatic_audit)
        StrategyProbeSuiteQuickV1 -> context.getString(R.string.diagnostics_scan_automatic_probing)
        else -> suiteId
    }

private fun strategyProbeOutcomeLabel(
    outcome: String,
    skipped: Boolean,
    stringResolver: StringResolver,
): String = StrategyProbeValueLabels(stringResolver).outcome(outcome, skipped)

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

private fun DiagnosticsUiFactorySupport.strategyProbeFamilyLabel(family: String): String =
    StrategyProbeValueLabels(context).family(family)

private fun parseServiceModeOrDefault(serviceMode: String?): Mode =
    when (serviceMode?.uppercase(Locale.US)) {
        "PROXY" -> Mode.Proxy
        "VPN" -> Mode.VPN
        else -> Mode.VPN
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
            val title =
                result
                    .detailValue("protocol")
                    ?.uppercase(Locale.US)
                    ?.let { context.getString(R.string.diagnostics_strategy_protocol_results, it) }
                    ?: when (result.probeType) {
                        "strategy_http" -> context.getString(R.string.diagnostics_strategy_protocol_results, "HTTP")
                        "strategy_https" -> context.getString(R.string.diagnostics_strategy_protocol_results, "HTTPS")
                        "strategy_quic" -> context.getString(R.string.diagnostics_strategy_protocol_results, "QUIC")
                        "strategy_probe" -> context.getString(R.string.blockcheck_section_title)
                        else -> result.probeType
                    }
            title to toProbeResultUiModel(index = index, pathMode = ScanPathMode.RAW_PATH, result = result)
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (title, items) ->
            DiagnosticsProbeGroupUiModel(title = title, items = items.toImmutableList())
        }.sortedBy { it.title }

internal fun StrategyProbeCandidateSummary.toCandidateUiModel(
    recommended: Boolean,
    stringResolver: StringResolver,
): DiagnosticsStrategyProbeCandidateUiModel =
    DiagnosticsStrategyProbeCandidateUiModel(
        id = id,
        label = label,
        outcome = strategyProbeOutcomeLabel(outcome = outcome, skipped = skipped, stringResolver = stringResolver),
        rationale = rationale,
        metrics = buildStrategyProbeCandidateMetrics(this, recommended, stringResolver).toImmutableList(),
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
    sessionId: String?,
): DiagnosticsStrategyProbeCandidateDetailUiModel =
    DiagnosticsStrategyProbeCandidateDetailUiModel(
        id = candidate.id,
        label = candidate.label,
        familyLabel = strategyProbeFamilyLabel(candidate.family),
        suiteLabel = strategyProbeSuiteLabel(suiteId),
        outcome =
            strategyProbeOutcomeLabel(
                outcome = candidate.outcome,
                skipped = candidate.skipped,
                stringResolver = context,
            ),
        rationale = candidate.rationale,
        tone =
            strategyProbeCandidateTone(
                outcome = candidate.outcome,
                skipped = candidate.skipped,
                recommended = recommended,
            ),
        recommended = recommended,
        notes = (candidate.notes + strategyEmitterNotes(candidate, context)).distinct().toImmutableList(),
        metrics = buildStrategyProbeCandidateMetrics(candidate, recommended, context).toImmutableList(),
        signature = deriveSignature(candidate, serviceMode)?.let(::strategySignatureFields).orEmpty().toImmutableList(),
        resultGroups =
            buildStrategyProbeResultGroups(
                candidateResults = candidateResults,
            ).toImmutableList(),
        replaySessionId = sessionId?.takeIf { candidate.proxyConfigJson != null },
    )

private fun DiagnosticsUiFactorySupport.toStrategyProbeRecommendationUiModel(
    recommendation: StrategyProbeRecommendation,
    completionKind: StrategyProbeCompletionKind,
): DiagnosticsStrategyProbeRecommendationUiModel {
    val shortCircuited = completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED
    return DiagnosticsStrategyProbeRecommendationUiModel(
        headline =
            if (shortCircuited) {
                context.getString(R.string.diagnostics_probe_short_circuit_recommendation_headline)
            } else {
                listOfNotNull(
                    recommendation.tcpCandidateLabel,
                    recommendation.quicCandidateLabel,
                    recommendation.dnsStrategyLabel?.let(StrategyProbeValueLabels(context)::dnsLane),
                ).joinToString(" + ")
            },
        rationale = recommendation.rationale,
        fields = strategyProbeRecommendationFields(recommendation, shortCircuited).toImmutableList(),
        signature =
            recommendation.strategySignature
                ?.let(::strategySignatureFields)
                .orEmpty()
                .toImmutableList(),
    )
}

private fun DiagnosticsUiFactorySupport.strategyProbeRecommendationFields(
    recommendation: StrategyProbeRecommendation,
    shortCircuited: Boolean,
): List<DiagnosticsFieldUiModel> =
    listOfNotNull(
        DiagnosticsFieldUiModel(
            context.getString(
                if (shortCircuited) {
                    R.string.diagnostics_strategy_field_tcp_fallback
                } else {
                    R.string.diagnostics_strategy_field_tcp_recommendation
                },
            ),
            recommendation.tcpCandidateLabel,
        ),
        recommendation.tcpCandidateFamily?.let {
            DiagnosticsFieldUiModel(
                context.getString(R.string.diagnostics_signature_tcp_tls_lane),
                strategyProbeFamilyLabel(it),
            )
        },
        DiagnosticsFieldUiModel(
            context.getString(
                if (shortCircuited) {
                    R.string.diagnostics_strategy_field_quic_fallback
                } else {
                    R.string.diagnostics_strategy_field_quic_recommendation
                },
            ),
            recommendation.quicCandidateLabel,
        ),
        recommendation.quicCandidateFamily?.let {
            DiagnosticsFieldUiModel(
                context.getString(R.string.diagnostics_signature_quic_lane),
                strategyProbeFamilyLabel(it),
            )
        },
        recommendation.dnsStrategyLabel?.let {
            DiagnosticsFieldUiModel(
                context.getString(R.string.diagnostics_audit_winning_dns_lane),
                StrategyProbeValueLabels(context).dnsLane(it),
            )
        },
        recommendation.tlsPathSuppressionSummary?.let {
            DiagnosticsFieldUiModel(context.getString(R.string.diagnostics_strategy_field_suppression), it)
        },
        DiagnosticsFieldUiModel(
            context.getString(
                if (shortCircuited) {
                    R.string.diagnostics_strategy_field_trials_stopped
                } else {
                    R.string.diagnostics_strategy_field_why_won
                },
            ),
            recommendation.rationale,
        ),
    )
