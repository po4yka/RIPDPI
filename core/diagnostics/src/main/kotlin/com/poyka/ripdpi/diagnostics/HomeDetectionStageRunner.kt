package com.poyka.ripdpi.diagnostics

data class HomeDetectionStageOutcome(
    val verdict: DiagnosticsHomeDetectionVerdict,
    val detectedSignalCount: Int,
    val findings: List<String>,
)

interface HomeDetectionStageRunner {
    suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome?
}

object NoopHomeDetectionStageRunner : HomeDetectionStageRunner {
    override suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome? =
        null
}
