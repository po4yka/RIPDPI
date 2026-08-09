package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.StrategyCandidatePlanSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeAttemptStatus
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveCapabilitiesEntryBuilder(
    private val json: Json,
) {
    fun buildRoot(
        selection: DiagnosticsArchiveSelection,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        build(
            name = "capabilities.json",
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
            name = "$prefix/capabilities.json",
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
    ): DiagnosticsArchiveEntry {
        val payload =
            DiagnosticsArchiveCapabilitiesPayload(
                sessionId = sessionId,
                profileId = profileId,
                capabilities = buildCapabilities(report, referencePrefix),
            )
        return DiagnosticsArchiveEntry(
            name = name,
            bytes = json.encodeToString(DiagnosticsArchiveCapabilitiesPayload.serializer(), payload).toByteArray(),
        )
    }

    private fun buildCapabilities(
        report: EngineScanReportWire?,
        referencePrefix: String,
    ): List<DiagnosticsArchiveCapabilityEvidence> {
        val candidates = plannedCandidates(report)
        val attempts = report?.strategyProbeReport?.attempts.orEmpty()
        val summaries = candidateSummaries(report)
        return candidates
            .flatMap { candidate -> candidate.plan.requiredCapabilities.map { capability -> capability to candidate } }
            .groupBy(
                keySelector = Pair<String, PlannedCandidate>::first,
                valueTransform = Pair<String, PlannedCandidate>::second,
            ).toSortedMap()
            .map { (capability, requiredBy) ->
                buildCapabilityEvidence(capability, requiredBy, summaries, attempts, referencePrefix)
            }
    }

    private fun buildCapabilityEvidence(
        capability: String,
        requiredBy: List<PlannedCandidate>,
        summaries: Map<CandidateKey, IndexedCandidateSummary>,
        attempts: List<com.poyka.ripdpi.diagnostics.StrategyProbeAttempt>,
        referencePrefix: String,
    ): DiagnosticsArchiveCapabilityEvidence {
        val evidence = mutableListOf<String>()
        val observations =
            requiredBy.map { candidate ->
                evidence += candidate.capabilityEvidenceRef(capability, referencePrefix)
                val summary = summaries[candidate.key]
                val candidateAttempts =
                    attempts.withIndex().filter {
                        it.value.lane == candidate.lane && it.value.candidateId == candidate.plan.id
                    }
                val executed = candidateAttempts.filter { it.value.status == StrategyProbeAttemptStatus.EXECUTED }
                val unavailable =
                    summary?.value?.outcome == CapabilitySkippedOutcome ||
                        candidateAttempts.any { it.value.outcome == CapabilitySkippedOutcome }
                val available =
                    executed.isNotEmpty() &&
                        summary?.value?.emitterDowngraded == false &&
                        summary.value.emitterTier == candidate.plan.emitterTier &&
                        summary.value.exactEmitterRequiresRoot == candidate.plan.exactEmitterRequiresRoot
                when {
                    unavailable && available -> {
                        evidence += summary.outcomeEvidenceRef(candidate.lane, referencePrefix)
                        executed.forEach { evidence += "${referencePrefix}attempts.jsonl#L${it.index + 1}" }
                        DiagnosticsArchiveCapabilityStatus.UNKNOWN
                    }

                    unavailable -> {
                        summary?.let { evidence += it.outcomeEvidenceRef(candidate.lane, referencePrefix) }
                        candidateAttempts
                            .filter { it.value.outcome == CapabilitySkippedOutcome }
                            .forEach { evidence += "${referencePrefix}attempts.jsonl#L${it.index + 1}" }
                        DiagnosticsArchiveCapabilityStatus.UNAVAILABLE
                    }

                    available -> {
                        executed.forEach { evidence += "${referencePrefix}attempts.jsonl#L${it.index + 1}" }
                        evidence += summary.emitterEvidenceRef(candidate.lane, referencePrefix)
                        DiagnosticsArchiveCapabilityStatus.AVAILABLE
                    }

                    else -> {
                        DiagnosticsArchiveCapabilityStatus.UNKNOWN
                    }
                }
            }
        return DiagnosticsArchiveCapabilityEvidence(
            id = capability,
            status = observations.aggregateCapabilityStatus(),
            requiredBy =
                requiredBy.map { candidate ->
                    DiagnosticsArchiveCapabilityRequirement(
                        lane = candidate.lane,
                        candidateId = candidate.plan.id,
                        emitterTier = candidate.plan.emitterTier,
                        exactEmitterRequiresRoot = candidate.plan.exactEmitterRequiresRoot,
                    )
                },
            evidenceRefs = evidence.distinct(),
        )
    }
}

private data class CandidateKey(
    val lane: StrategyProbeProgressLane,
    val id: String,
)

private data class PlannedCandidate(
    val lane: StrategyProbeProgressLane,
    val index: Int,
    val plan: StrategyCandidatePlanSnapshot,
) {
    val key = CandidateKey(lane, plan.id)

    fun capabilityEvidenceRef(
        capability: String,
        referencePrefix: String,
    ): String {
        val laneField = if (lane == StrategyProbeProgressLane.TCP) "tcpCandidates" else "quicCandidates"
        val capabilityIndex = plan.requiredCapabilities.indexOf(capability)
        return "${referencePrefix}execution-plan.json#/executionPlan/strategy/" +
            "$laneField/$index/requiredCapabilities/$capabilityIndex"
    }
}

private data class IndexedCandidateSummary(
    val index: Int,
    val value: StrategyProbeCandidateSummary,
) {
    fun outcomeEvidenceRef(
        lane: StrategyProbeProgressLane,
        referencePrefix: String,
    ): String = evidenceRef(lane, "outcome", referencePrefix)

    fun emitterEvidenceRef(
        lane: StrategyProbeProgressLane,
        referencePrefix: String,
    ): String = evidenceRef(lane, "emitterDowngraded", referencePrefix)

    private fun evidenceRef(
        lane: StrategyProbeProgressLane,
        field: String,
        referencePrefix: String,
    ): String {
        val laneField = if (lane == StrategyProbeProgressLane.TCP) "tcpCandidates" else "quicCandidates"
        return "${referencePrefix}strategy-matrix.json#/strategyProbeReport/$laneField/$index/$field"
    }
}

private fun plannedCandidates(report: EngineScanReportWire?): List<PlannedCandidate> {
    val strategy = report?.executionPlan?.strategy ?: return emptyList()
    return strategy.tcpCandidates.mapIndexed { index, plan ->
        PlannedCandidate(StrategyProbeProgressLane.TCP, index, plan)
    } +
        strategy.quicCandidates.mapIndexed { index, plan ->
            PlannedCandidate(StrategyProbeProgressLane.QUIC, index, plan)
        }
}

private fun candidateSummaries(report: EngineScanReportWire?): Map<CandidateKey, IndexedCandidateSummary> {
    val strategy = report?.strategyProbeReport ?: return emptyMap()
    return buildMap {
        strategy.tcpCandidates.forEachIndexed { index, summary ->
            put(CandidateKey(StrategyProbeProgressLane.TCP, summary.id), IndexedCandidateSummary(index, summary))
        }
        strategy.quicCandidates.forEachIndexed { index, summary ->
            put(CandidateKey(StrategyProbeProgressLane.QUIC, summary.id), IndexedCandidateSummary(index, summary))
        }
    }
}

private fun List<DiagnosticsArchiveCapabilityStatus>.aggregateCapabilityStatus(): DiagnosticsArchiveCapabilityStatus {
    val distinct = toSet()
    return when {
        DiagnosticsArchiveCapabilityStatus.AVAILABLE in distinct &&
            DiagnosticsArchiveCapabilityStatus.UNAVAILABLE in distinct -> DiagnosticsArchiveCapabilityStatus.CONFLICTING

        DiagnosticsArchiveCapabilityStatus.AVAILABLE in distinct -> DiagnosticsArchiveCapabilityStatus.AVAILABLE

        DiagnosticsArchiveCapabilityStatus.UNAVAILABLE in distinct -> DiagnosticsArchiveCapabilityStatus.UNAVAILABLE

        else -> DiagnosticsArchiveCapabilityStatus.UNKNOWN
    }
}

private const val CapabilitySkippedOutcome = "capability_skipped"
