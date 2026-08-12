package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.StrategyProbeAttemptStatus
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire

internal fun DiagnosticsArchiveSelection.buildStageEvidenceCompleteness():
    List<DiagnosticsArchiveStageEvidenceCompleteness> =
    compositeStages.map { stage ->
        val attempts = stage.report?.strategyProbeReport?.attempts
        val executedAttempts = attempts?.count { it.status == StrategyProbeAttemptStatus.EXECUTED }
        val nonSessionEvidence = stage.session == null && stage.hasStageEvidence()
        val verdictEvidenceRefs =
            stage.report?.verdictEvidenceRefCount()
                ?: if (nonSessionEvidence) {
                    1 + stage.stageSummary.detectionFindings.size
                } else {
                    null
                }
        val stageAbsenceReason =
            DiagnosticsArchiveStageEvidenceAbsenceReason.STAGE_NOT_EXECUTED
                .takeIf { stage.stageSummary.status.isNotExecuted() }
        val missingCollectionReason =
            if (stageAbsenceReason != null) {
                stageAbsenceReason
            } else if (nonSessionEvidence) {
                DiagnosticsArchiveStageEvidenceAbsenceReason.NOT_APPLICABLE_FOR_STAGE
            } else if (stage.session == null) {
                DiagnosticsArchiveStageEvidenceAbsenceReason.STAGE_SESSION_UNAVAILABLE
            } else {
                null
            }
        DiagnosticsArchiveStageEvidenceCompleteness(
            stageKey = stage.stageSummary.stageKey,
            stageStatus =
                stage.stageSummary.status.name
                    .lowercase(),
            counts =
                stage.evidenceCounts(
                    attempts?.size,
                    executedAttempts,
                    verdictEvidenceRefs,
                    missingCollectionReason,
                    stageAbsenceReason,
                    nonSessionEvidence,
                ),
        )
    }

private fun DiagnosticsHomeCompositeStageStatus.isNotExecuted(): Boolean =
    this == DiagnosticsHomeCompositeStageStatus.PENDING ||
        this == DiagnosticsHomeCompositeStageStatus.SKIPPED ||
        this == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE

private fun DiagnosticsArchiveCompositeStageSelection.evidenceCounts(
    plannedAttemptCount: Int?,
    executedAttemptCount: Int?,
    verdictEvidenceRefCount: Int?,
    missingCollectionReason: DiagnosticsArchiveStageEvidenceAbsenceReason?,
    stageAbsenceReason: DiagnosticsArchiveStageEvidenceAbsenceReason?,
    nonSessionEvidence: Boolean,
) = DiagnosticsArchiveStageEvidenceCounts(
    snapshots =
        collectionEvidenceCount(
            sourceCount = sourceSnapshotCount,
            includedCount = snapshots.size,
            truncated = sourceSnapshotCount > DiagnosticsArchiveFormat.snapshotLimit,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.NO_SNAPSHOTS_COLLECTED,
            missingCollectionReason = missingCollectionReason,
        ),
    contexts =
        collectionEvidenceCount(
            sourceCount = sourceContextCount,
            includedCount = contexts.size,
            truncated = sourceContextCount > DiagnosticsArchiveFormat.snapshotLimit,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.NO_CONTEXTS_COLLECTED,
            missingCollectionReason = missingCollectionReason,
        ),
    nativeEvents =
        collectionEvidenceCount(
            sourceCount = sourceEventCount,
            includedCount = events.size,
            truncated = sourceEventCount > DiagnosticsArchiveFormat.sessionEventLimit,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.NO_NATIVE_EVENTS_COLLECTED,
            missingCollectionReason = missingCollectionReason,
        ),
    telemetrySamples =
        collectionEvidenceCount(
            sourceCount = sourceTelemetryCount,
            includedCount = telemetry.size,
            truncated = sourceTelemetryCount > DiagnosticsArchiveFormat.telemetryLimit,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.NO_TELEMETRY_SAMPLES_COLLECTED,
            missingCollectionReason = missingCollectionReason,
        ),
    plannedAttempts =
        attemptEvidenceCount(
            plannedAttemptCount,
            executed = false,
            stageAbsenceReason = stageAbsenceReason,
            nonSessionEvidence = nonSessionEvidence,
        ),
    executedAttempts =
        attemptEvidenceCount(
            executedAttemptCount,
            executed = true,
            stageAbsenceReason = stageAbsenceReason,
            nonSessionEvidence = nonSessionEvidence,
        ),
    observations =
        reportEvidenceCount(
            count = report?.observations?.size,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.NO_OBSERVATIONS_EMITTED,
            stageAbsenceReason = stageAbsenceReason,
            nonSessionEvidence = nonSessionEvidence,
        ),
    diagnoses =
        reportEvidenceCount(
            count = report?.diagnoses?.size,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.NO_DIAGNOSES_EMITTED,
            stageAbsenceReason = stageAbsenceReason,
            nonSessionEvidence = nonSessionEvidence,
        ),
    verdictEvidenceRefs =
        reportEvidenceCount(
            count = verdictEvidenceRefCount,
            emptyReason = DiagnosticsArchiveStageEvidenceAbsenceReason.REPORT_UNAVAILABLE,
            stageAbsenceReason = stageAbsenceReason,
            nonSessionEvidence = false,
        ),
)

private fun collectionEvidenceCount(
    sourceCount: Int,
    includedCount: Int,
    truncated: Boolean,
    emptyReason: DiagnosticsArchiveStageEvidenceAbsenceReason,
    missingCollectionReason: DiagnosticsArchiveStageEvidenceAbsenceReason?,
) = evidenceCount(
    sourceCount = sourceCount,
    includedCount = includedCount,
    truncated = truncated,
    absenceReason = missingCollectionReason ?: emptyReason,
)

private fun evidenceCount(
    sourceCount: Int?,
    includedCount: Int?,
    truncated: Boolean = false,
    absenceReason: DiagnosticsArchiveStageEvidenceAbsenceReason,
): DiagnosticsArchiveStageEvidenceCount =
    DiagnosticsArchiveStageEvidenceCount(
        sourceCount = sourceCount,
        includedCount = includedCount,
        truncated = truncated,
        absenceReason = absenceReason.takeIf { sourceCount == null || sourceCount == 0 },
    )

private fun DiagnosticsArchiveCompositeStageSelection.attemptEvidenceCount(
    count: Int?,
    executed: Boolean,
    stageAbsenceReason: DiagnosticsArchiveStageEvidenceAbsenceReason?,
    nonSessionEvidence: Boolean,
): DiagnosticsArchiveStageEvidenceCount {
    val reason =
        when {
            stageAbsenceReason != null -> {
                stageAbsenceReason
            }

            nonSessionEvidence -> {
                DiagnosticsArchiveStageEvidenceAbsenceReason.NOT_APPLICABLE_FOR_STAGE
            }

            report == null -> {
                DiagnosticsArchiveStageEvidenceAbsenceReason.REPORT_UNAVAILABLE
            }

            report.strategyProbeReport == null && report.executionPlan?.strategy == null -> {
                DiagnosticsArchiveStageEvidenceAbsenceReason.NOT_APPLICABLE_FOR_STAGE
            }

            report.strategyProbeReport == null || report.strategyProbeReport.attempts.isEmpty() -> {
                DiagnosticsArchiveStageEvidenceAbsenceReason.ATTEMPT_LEDGER_UNAVAILABLE
            }

            executed -> {
                DiagnosticsArchiveStageEvidenceAbsenceReason.NO_EXECUTED_ATTEMPTS
            }

            else -> {
                DiagnosticsArchiveStageEvidenceAbsenceReason.ATTEMPT_LEDGER_UNAVAILABLE
            }
        }
    return evidenceCount(count, count, absenceReason = reason)
}

private fun DiagnosticsArchiveCompositeStageSelection.reportEvidenceCount(
    count: Int?,
    emptyReason: DiagnosticsArchiveStageEvidenceAbsenceReason,
    stageAbsenceReason: DiagnosticsArchiveStageEvidenceAbsenceReason?,
    nonSessionEvidence: Boolean,
): DiagnosticsArchiveStageEvidenceCount =
    evidenceCount(
        sourceCount = count,
        includedCount = count,
        absenceReason =
            if (stageAbsenceReason != null) {
                stageAbsenceReason
            } else if (nonSessionEvidence) {
                DiagnosticsArchiveStageEvidenceAbsenceReason.NOT_APPLICABLE_FOR_STAGE
            } else if (report == null) {
                DiagnosticsArchiveStageEvidenceAbsenceReason.REPORT_UNAVAILABLE
            } else {
                emptyReason
            },
    )

private fun EngineScanReportWire.verdictEvidenceRefCount(): Int {
    var count = 1 + diagnoses.size
    if (resolverRecommendation != null) count += 1
    if (strategyRecommendation != null) count += 1
    strategyProbeReport?.let { strategyProbe ->
        count += 2
        if (strategyProbe.attempts.isNotEmpty()) count += 1
        if (strategyProbe.auditAssessment != null) count += 1
        if (strategyProbe.connectionConcurrencyAssessment != null) count += 1
        if (strategyProbe.recommendation.transportPivot != null) count += 1
    }
    if (directModeVerdict != null) count += 1
    if (confirmGoodDpiVerdict != null) count += 1
    return count
}
