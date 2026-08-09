package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.StrategyCandidatePlanSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeAttempt
import com.poyka.ripdpi.diagnostics.StrategyProbeAttemptStatus
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal class DiagnosticsArchiveEmissionReceiptsEntryBuilder(
    private val json: Json,
) {
    fun buildRoot(
        selection: DiagnosticsArchiveSelection,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        build(
            name = "emission-receipts.jsonl",
            sessionId = selection.primarySession?.id,
            profileId = selection.primarySession?.profileId,
            report = report,
            referencePrefix = "",
        )

    fun buildStage(
        prefix: String,
        stage: DiagnosticsArchiveCompositeStageSelection,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        build(
            name = "$prefix/emission-receipts.jsonl",
            sessionId = stage.session?.id,
            profileId = stage.session?.profileId,
            report = report,
            referencePrefix = "$prefix/",
        )

    private fun build(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
        referencePrefix: String,
    ): DiagnosticsArchiveEntry =
        DiagnosticsArchiveEntry(
            name = name,
            bytes = buildJsonLines(sessionId, profileId, report, referencePrefix).toByteArray(),
        )

    private fun buildJsonLines(
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
        referencePrefix: String,
    ): String =
        plannedEmissionCandidates(report)
            .mapIndexed { index, candidate ->
                candidate.toReceipt(
                    sessionId = sessionId,
                    profileId = profileId,
                    sequence = index + 1,
                    report = report,
                    referencePrefix = referencePrefix,
                )
            }.joinToString(separator = "", postfix = "") { receipt ->
                json.encodeToJsonElement(DiagnosticsArchiveEmissionReceipt.serializer(), receipt).toString() + "\n"
            }
}

private data class PlannedEmissionCandidate(
    val lane: StrategyProbeProgressLane,
    val planIndex: Int,
    val plan: StrategyCandidatePlanSnapshot,
) {
    fun toReceipt(
        sessionId: String?,
        profileId: String?,
        sequence: Int,
        report: EngineScanReportWire?,
        referencePrefix: String,
    ): DiagnosticsArchiveEmissionReceipt {
        val attempts = report.attemptsFor(this)
        val summary = report.summaryFor(this)
        val executedAttempts = attempts.filter { it.value.status == StrategyProbeAttemptStatus.EXECUTED }
        val capabilitySkipped =
            summary?.value?.outcome == CapabilitySkippedEmissionOutcome ||
                attempts.any { it.value.outcome == CapabilitySkippedEmissionOutcome }
        val evidence = buildEvidenceRefs(attempts, summary, executedAttempts, capabilitySkipped, referencePrefix)
        val (emissionStatus, evidenceBasis) =
            emissionAssessment(
                plan = plan,
                summary = summary?.value,
                attempts = attempts.map(IndexedValue<StrategyProbeAttempt>::value),
            )
        return DiagnosticsArchiveEmissionReceipt(
            sessionId = sessionId,
            profileId = profileId,
            sequence = sequence,
            lane = lane,
            candidateId = plan.id,
            candidateFamily = plan.family,
            plannedEmitterTier = plan.emitterTier,
            observedEmitterTier = summary?.value?.emitterTier,
            exactEmitterRequiresRoot = plan.exactEmitterRequiresRoot,
            emitterDowngraded = summary?.value?.emitterDowngraded,
            requiredCapabilities = plan.requiredCapabilities,
            emissionStatus = emissionStatus,
            evidenceBasis = evidenceBasis,
            executedAttemptCount = executedAttempts.size,
            evidenceRefs = evidence,
        )
    }

    private fun buildEvidenceRefs(
        attempts: List<IndexedValue<StrategyProbeAttempt>>,
        summary: IndexedValue<StrategyProbeCandidateSummary>?,
        executedAttempts: List<IndexedValue<StrategyProbeAttempt>>,
        capabilitySkipped: Boolean,
        referencePrefix: String,
    ): List<String> =
        buildList {
            add(planEvidenceRef(referencePrefix))
            when {
                executedAttempts.isNotEmpty() -> {
                    executedAttempts.forEach {
                        add(it.attemptEvidenceRef(referencePrefix))
                    }
                }

                capabilitySkipped -> {
                    attempts.filter { it.value.outcome == CapabilitySkippedEmissionOutcome }.forEach {
                        add(it.attemptEvidenceRef(referencePrefix))
                    }
                }

                attempts.isNotEmpty() -> {
                    attempts.forEach { add(it.attemptEvidenceRef(referencePrefix)) }
                }
            }
            summary?.let { add(it.summaryEvidenceRef(lane, referencePrefix)) }
        }.distinct()

    private fun planEvidenceRef(referencePrefix: String): String {
        val laneField = if (lane == StrategyProbeProgressLane.TCP) "tcpCandidates" else "quicCandidates"
        return "${referencePrefix}execution-plan.json#/executionPlan/strategy/$laneField/$planIndex"
    }
}

private fun plannedEmissionCandidates(report: EngineScanReportWire?): List<PlannedEmissionCandidate> {
    val strategy = report?.executionPlan?.strategy ?: return emptyList()
    return strategy.tcpCandidates.mapIndexed { index, plan ->
        PlannedEmissionCandidate(StrategyProbeProgressLane.TCP, index, plan)
    } +
        strategy.quicCandidates.mapIndexed { index, plan ->
            PlannedEmissionCandidate(StrategyProbeProgressLane.QUIC, index, plan)
        }
}

private fun EngineScanReportWire?.attemptsFor(
    candidate: PlannedEmissionCandidate,
): List<IndexedValue<StrategyProbeAttempt>> =
    this
        ?.strategyProbeReport
        ?.attempts
        .orEmpty()
        .withIndex()
        .filter { it.value.lane == candidate.lane && it.value.candidateId == candidate.plan.id }

private fun EngineScanReportWire?.summaryFor(
    candidate: PlannedEmissionCandidate,
): IndexedValue<StrategyProbeCandidateSummary>? {
    val report = this?.strategyProbeReport ?: return null
    val candidates =
        if (candidate.lane == StrategyProbeProgressLane.TCP) {
            report.tcpCandidates
        } else {
            report.quicCandidates
        }
    return candidates.withIndex().firstOrNull { it.value.id == candidate.plan.id }
}

private fun emissionAssessment(
    plan: StrategyCandidatePlanSnapshot,
    summary: StrategyProbeCandidateSummary?,
    attempts: List<StrategyProbeAttempt>,
): Pair<DiagnosticsArchiveEmissionStatus, DiagnosticsArchiveEmissionEvidenceBasis> {
    val executed = attempts.any { it.status == StrategyProbeAttemptStatus.EXECUTED }
    val capabilitySkipped =
        summary?.outcome == CapabilitySkippedEmissionOutcome ||
            attempts.any { it.outcome == CapabilitySkippedEmissionOutcome }
    val emitterIdentityMatchesPlan =
        summary?.emitterTier == plan.emitterTier &&
            summary.exactEmitterRequiresRoot == plan.exactEmitterRequiresRoot
    return when {
        capabilitySkipped && executed -> {
            DiagnosticsArchiveEmissionStatus.UNVERIFIED to
                DiagnosticsArchiveEmissionEvidenceBasis.CONFLICTING_EVIDENCE
        }

        capabilitySkipped -> {
            DiagnosticsArchiveEmissionStatus.NOT_EMITTED to
                DiagnosticsArchiveEmissionEvidenceBasis.CAPABILITY_SKIPPED
        }

        executed && summary?.emitterDowngraded == false && emitterIdentityMatchesPlan -> {
            DiagnosticsArchiveEmissionStatus.EXACT_EMITTER_EXECUTED to
                DiagnosticsArchiveEmissionEvidenceBasis.ATTEMPT_EXECUTED
        }

        executed && summary?.emitterDowngraded == false -> {
            DiagnosticsArchiveEmissionStatus.UNVERIFIED to
                DiagnosticsArchiveEmissionEvidenceBasis.CONFLICTING_EVIDENCE
        }

        executed && summary?.emitterDowngraded == true -> {
            DiagnosticsArchiveEmissionStatus.DOWNGRADED_EMITTER_EXECUTED to
                DiagnosticsArchiveEmissionEvidenceBasis.ATTEMPT_EXECUTED
        }

        executed -> {
            DiagnosticsArchiveEmissionStatus.UNVERIFIED to
                DiagnosticsArchiveEmissionEvidenceBasis.ATTEMPT_EXECUTED
        }

        attempts.isNotEmpty() -> {
            DiagnosticsArchiveEmissionStatus.NOT_EMITTED to
                DiagnosticsArchiveEmissionEvidenceBasis.ATTEMPT_NOT_EXECUTED
        }

        summary != null -> {
            DiagnosticsArchiveEmissionStatus.UNVERIFIED to
                DiagnosticsArchiveEmissionEvidenceBasis.SUMMARY_ONLY
        }

        else -> {
            DiagnosticsArchiveEmissionStatus.UNVERIFIED to
                DiagnosticsArchiveEmissionEvidenceBasis.PLAN_ONLY
        }
    }
}

private fun IndexedValue<StrategyProbeAttempt>.attemptEvidenceRef(referencePrefix: String): String =
    "${referencePrefix}attempts.jsonl#L${index + 1}"

private fun IndexedValue<StrategyProbeCandidateSummary>.summaryEvidenceRef(
    lane: StrategyProbeProgressLane,
    referencePrefix: String,
): String {
    val laneField = if (lane == StrategyProbeProgressLane.TCP) "tcpCandidates" else "quicCandidates"
    return "${referencePrefix}strategy-matrix.json#/strategyProbeReport/$laneField/$index"
}

private const val CapabilitySkippedEmissionOutcome = "capability_skipped"
