@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal class HomeDetectionStageCoordinator(
    private val detectionStageRunner: HomeDetectionStageRunner,
    private val stageExecutor: HomeCompositeStageExecutor,
    private val progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    private val runDetectionResults: ConcurrentHashMap<String, HomeDetectionStageOutcome>,
) {
    private companion object {
        private val log = Logger.withTag("HomeAnalysis")
    }

    suspend fun runStage(
        runId: String,
        stageIndex: Int,
        spec: HomeCompositeStageSpec,
    ) {
        stageExecutor.updateStage(progressState, runId, stageIndex) { stage ->
            stage.copy(
                status = DiagnosticsHomeCompositeStageStatus.RUNNING,
                headline = "${spec.label} running",
                summary = "Starting ${spec.label.lowercase()}.",
            )
        }
        log.i { "stage ${spec.key} started (detection-runner)" }
        val outcome =
            runCatching {
                withTimeoutOrNull(DetectionStageTimeoutMs) {
                    detectionStageRunner.run { label, detail ->
                        stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                            current.copy(summary = "$label: $detail")
                        }
                    }
                }
            }.getOrNull()
        if (outcome == null) {
            log.w { "detection stage failed or timed out" }
            stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
                current.copy(
                    status = DiagnosticsHomeCompositeStageStatus.FAILED,
                    headline = "${spec.label} failed",
                    summary = "Detection checks did not complete within the allowed time.",
                )
            }
            return
        }
        runDetectionResults[runId] = outcome
        stageExecutor.updateStage(progressState, runId, stageIndex) { current ->
            current.copy(
                status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                headline = "${spec.label} complete",
                summary = outcome.summaryLine(),
            )
        }
    }
}

private fun HomeDetectionStageOutcome.summaryLine(): String {
    val verdictText =
        when (verdict) {
            DiagnosticsHomeDetectionVerdict.DETECTED -> "Detection signals were observed"
            DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW -> "Detection results need review"
            DiagnosticsHomeDetectionVerdict.NOT_DETECTED -> "No detection signals observed"
        }
    return if (detectedSignalCount > 0) {
        "$verdictText. $detectedSignalCount detection signal" +
            "${if (detectedSignalCount == 1) "" else "s"} observed."
    } else {
        "$verdictText."
    }
}
