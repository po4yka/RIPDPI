package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveDecisionTraceEntryBuilder(
    private val json: Json,
) {
    fun build(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry {
        val payload =
            DecisionTraceArchivePayload(
                sessionId = sessionId,
                profileId = profileId,
                decisions = report?.let(::buildDecisions).orEmpty(),
            )
        return DiagnosticsArchiveEntry(
            name = name,
            bytes = json.encodeToString(DecisionTraceArchivePayload.serializer(), payload).toByteArray(),
        )
    }

    private fun buildDecisions(report: EngineScanReportWire): List<DecisionTraceArchiveEntry> =
        buildList {
            addScanCompletion(report)
            addDiagnoses(report)
            addResolverSelection(report)
            addStrategyFamilyRecommendation(report)
            addStrategyProbeDecisions(report.strategyProbeReport)
            addDirectModeVerdict(report)
            addConfirmGoodDpiVerdict(report)
        }.mapIndexed { index, decision -> decision.copy(sequence = index + 1) }

    private fun MutableList<DecisionTraceArchiveEntry>.addScanCompletion(report: EngineScanReportWire) {
        add(
            decision(
                type = "SCAN_COMPLETION",
                outcome = report.completionKind.name,
                reasonCode = report.terminationReason?.name,
                rationale = report.summary,
                evidenceRefs = listOf(primaryReportEvidenceRef()),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addDiagnoses(report: EngineScanReportWire) {
        report.diagnoses.forEachIndexed { index, diagnosis ->
            add(
                decision(
                    type = "DIAGNOSIS",
                    outcome = diagnosis.code,
                    reasonCode = diagnosis.severity,
                    rationale = diagnosis.summary,
                    evidenceRefs = listOf(primaryReportEvidenceRef(pointer = "/diagnoses/$index")),
                ),
            )
        }
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addResolverSelection(report: EngineScanReportWire) {
        val resolver = report.resolverRecommendation ?: return
        add(
            decision(
                type = "RESOLVER_SELECTION",
                outcome = "SELECTED",
                reasonCode = resolver.triggerOutcome,
                rationale = resolver.rationale,
                selections =
                    listOf(
                        selection("resolver", resolver.selectedResolverId),
                        selection("protocol", resolver.selectedProtocol),
                    ),
                evidenceRefs = listOf(primaryReportEvidenceRef(pointer = "/resolverRecommendation")),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addStrategyFamilyRecommendation(report: EngineScanReportWire) {
        val recommendation = report.strategyRecommendation ?: return
        add(
            decision(
                type = "STRATEGY_FAMILY_RECOMMENDATION",
                outcome = if (recommendation.actionable) "ACTIONABLE" else "INFORMATIONAL",
                reasonCode = recommendation.blockingPattern,
                rationale = recommendation.rationale,
                selections = listOf(selection("family", recommendation.recommendedFamily)),
                confidence =
                    DecisionTraceArchiveConfidence(
                        level = recommendation.confidence.name,
                        score = recommendation.evidenceScore,
                    ),
                evidenceRefs = listOf(primaryReportEvidenceRef(pointer = "/strategyRecommendation")),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addStrategyProbeDecisions(strategyProbe: StrategyProbeReport?) {
        strategyProbe ?: return
        addStrategyProbeCompletion(strategyProbe)
        addStrategySelection(strategyProbe)
        addStrategyAudit(strategyProbe)
        addConnectionConcurrencyDecision(strategyProbe)
        addTransportPivotDecision(strategyProbe)
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addStrategyProbeCompletion(strategyProbe: StrategyProbeReport) {
        add(
            decision(
                type = "STRATEGY_PROBE_COMPLETION",
                outcome = strategyProbe.completionKind.name,
                evidenceRefs = listOf("strategy-matrix.json#/strategyProbeReport/completionKind"),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addStrategySelection(strategyProbe: StrategyProbeReport) {
        val recommendation = strategyProbe.recommendation
        add(
            decision(
                type = "STRATEGY_SELECTION",
                outcome = "SELECTED",
                rationale = recommendation.rationale,
                selections =
                    buildList {
                        add(selection("tcpCandidate", recommendation.tcpCandidateId))
                        add(selection("quicCandidate", recommendation.quicCandidateId))
                        recommendation.dnsStrategyFamily?.let { add(selection("dnsStrategy", it)) }
                    },
                evidenceRefs =
                    buildList {
                        add("strategy-matrix.json#/strategyProbeReport/recommendation")
                        if (strategyProbe.attempts.isNotEmpty()) add("attempts.jsonl")
                    },
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addStrategyAudit(strategyProbe: StrategyProbeReport) {
        val assessment = strategyProbe.auditAssessment ?: return
        add(
            decision(
                type = "STRATEGY_AUDIT",
                outcome = assessment.confidence.level.name,
                rationale = assessment.confidence.rationale,
                selections =
                    listOf(
                        selection("matrixCoveragePercent", assessment.coverage.matrixCoveragePercent.toString()),
                        selection("winnerCoveragePercent", assessment.coverage.winnerCoveragePercent.toString()),
                    ),
                confidence =
                    DecisionTraceArchiveConfidence(
                        level = assessment.confidence.level.name,
                        score = assessment.confidence.score,
                    ),
                evidenceRefs = listOf("strategy-matrix.json#/strategyProbeReport/auditAssessment"),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addConnectionConcurrencyDecision(
        strategyProbe: StrategyProbeReport,
    ) {
        val assessment = strategyProbe.connectionConcurrencyAssessment ?: return
        add(
            decision(
                type = "CONNECTION_CONCURRENCY",
                outcome = assessment.verdict.name,
                selections =
                    buildList {
                        assessment.selectedProfileId?.let { add(selection("tlsProfile", it)) }
                        assessment.safeCap?.let { add(selection("safeCap", it.toString())) }
                    },
                evidenceRefs = listOf("strategy-matrix.json#/strategyProbeReport/connectionConcurrencyAssessment"),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addTransportPivotDecision(strategyProbe: StrategyProbeReport) {
        val pivot = strategyProbe.recommendation.transportPivot ?: return
        add(
            decision(
                type = "TRANSPORT_PIVOT",
                outcome = pivot.viability.name,
                reasonCode = pivot.reasonCode,
                selections =
                    buildList {
                        add(selection("preferredFamily", pivot.preferredFamily.name))
                        pivot.selectedRelayRole?.let { add(selection("relayRole", it)) }
                    },
                evidenceRefs = listOf("strategy-matrix.json#/strategyProbeReport/recommendation/transportPivot"),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addDirectModeVerdict(report: EngineScanReportWire) {
        val verdict = report.directModeVerdict ?: return
        add(
            decision(
                type = "DIRECT_MODE_VERDICT",
                outcome = verdict.result.name,
                reasonCode = verdict.reasonCode?.name,
                selections = verdict.transportClass?.let { listOf(selection("transportClass", it.name)) }.orEmpty(),
                evidenceRefs = listOf(primaryReportEvidenceRef(pointer = "/directModeVerdict")),
            ),
        )
    }

    private fun MutableList<DecisionTraceArchiveEntry>.addConfirmGoodDpiVerdict(report: EngineScanReportWire) {
        val verdict = report.confirmGoodDpiVerdict ?: return
        add(
            decision(
                type = "CONFIRM_GOOD_DPI_VERDICT",
                outcome = verdict.status.name,
                selections =
                    listOf(
                        selection("source", verdict.evidence.source.name),
                        selection("stalledFlowCount", verdict.evidence.stalledFlowCount.toString()),
                        selection("distinctTargetCount", verdict.evidence.distinctTargetCount.toString()),
                    ),
                evidenceRefs = listOf(primaryReportEvidenceRef(pointer = "/confirmGoodDpiVerdict")),
            ),
        )
    }

    private fun decision(
        type: String,
        outcome: String,
        evidenceRefs: List<String>,
        reasonCode: String? = null,
        rationale: String? = null,
        selections: List<DecisionTraceArchiveSelection> = emptyList(),
        confidence: DecisionTraceArchiveConfidence? = null,
    ) = DecisionTraceArchiveEntry(
        sequence = 0,
        decisionType = type,
        outcome = outcome,
        reasonCode = reasonCode,
        rationale = rationale,
        selections = selections,
        confidence = confidence,
        evidenceRefs = evidenceRefs,
    )

    private fun selection(
        dimension: String,
        value: String,
    ) = DecisionTraceArchiveSelection(dimension = dimension, value = value)
}
