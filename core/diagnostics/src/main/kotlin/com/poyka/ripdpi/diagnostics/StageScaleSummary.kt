package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument

/** Export only enum identifiers and bounded numeric observations from the shared projection. */
internal fun connectionStageSummaryLines(
    results: List<ProbeResult>,
    networkScopeUnverified: Boolean = false,
): List<String> {
    val lines =
        results
            .asSequence()
            .take(StageSummaryResultLimit)
            .withIndex()
            .flatMap { (index, result) ->
                result
                    .toConnectionStageScale()
                    ?.lanes
                    .orEmpty()
                    .asSequence()
                    .filter { lane -> lane.stages.any { it.state in ExportedObservationStates } }
                    .flatMap { lane ->
                        lane.stages.asSequence().map { stage ->
                            formatConnectionStage(index, lane, stage, networkScopeUnverified)
                        }
                    }
            }.take(StageSummaryLineLimit + 1)
            .toList()
    return if (lines.size > StageSummaryLineLimit || results.size > StageSummaryResultLimit) {
        lines.take(StageSummaryLineLimit) + "connectionStageTruncated=true"
    } else {
        lines
    }
}

private fun formatConnectionStage(
    index: Int,
    lane: ConnectionStageLane,
    stage: ConnectionStageMeasurement,
    networkScopeUnverified: Boolean,
): String =
    buildString {
        append("connectionStage[$index] lane=${lane.kind.name}")
        lane.attempt?.let { append(" attempt=$it") }
        append(" stage=${stage.stage.name} state=${stage.state.name}")
        stage.durationMs?.let { append(" durationMs=$it") }
        stage.elapsedMs?.let { append(" elapsedMs=$it") }
        stage.byteCount?.let { append(" byteCount=$it") }
        stage.httpStatusCode?.let { append(" httpStatusCode=$it") }
        if (networkScopeUnverified || lane.networkScopeUnverified) append(" networkScope=unverified")
    }

/** Preserve measured stage facts before source identifiers are removed from the archive report. */
internal fun DiagnosticsSummaryDocument.withConnectionStageEvidence(
    report: EngineScanReportWire?,
): DiagnosticsSummaryDocument =
    copy(
        reportMetadata =
            reportMetadata.copy(
                lines =
                    reportMetadata.lines.filterNot {
                        it.startsWith("connectionStage[") || it == "connectionStageTruncated=true"
                    } +
                        connectionStageSummaryLines(
                            report?.results.orEmpty().map { it.toProbeResult() },
                            report?.diagnoses.orEmpty().any { it.code == "network_scope_unverified" },
                        ),
            ),
    )

private const val StageSummaryResultLimit = 32
private const val StageSummaryLineLimit = 256
private val ExportedObservationStates =
    setOf(
        ConnectionStageState.SUCCEEDED,
        ConnectionStageState.OBSERVED,
        ConnectionStageState.FAILED,
        ConnectionStageState.PARTIAL,
        ConnectionStageState.RUNNING,
        ConnectionStageState.CANCELLED,
        ConnectionStageState.TIMED_OUT,
    )
