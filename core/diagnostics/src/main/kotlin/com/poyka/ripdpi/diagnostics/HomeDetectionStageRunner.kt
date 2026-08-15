package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope

data class HomeDetectionStageOutcome(
    val verdict: DiagnosticsHomeDetectionVerdict,
    val detectedSignalCount: Int,
    val findings: List<String>,
    val ruleApplied: String? = null,
    val evidenceScopes: List<DetectionScope> = emptyList(),
    val localFindings: List<String> = emptyList(),
    val networkFindings: List<String> = emptyList(),
)

interface HomeDetectionStageRunner {
    suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome?
}

object NoopHomeDetectionStageRunner : HomeDetectionStageRunner {
    override suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome? =
        null
}
