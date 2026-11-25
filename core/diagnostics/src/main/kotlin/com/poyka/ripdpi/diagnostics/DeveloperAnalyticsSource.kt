package com.poyka.ripdpi.diagnostics

import java.io.File

data class DeveloperAnalyticsContext(
    val archiveCreatedAtMs: Long,
    val archiveFileName: String,
    val homeRunId: String? = null,
    val homeCompositeOutcome: DiagnosticsHomeCompositeOutcome? = null,
    val primarySessionId: String? = null,
    val primaryProfileId: String? = null,
    val pcapFiles: List<File> = emptyList(),
    val compositeSessionIds: List<String> = emptyList(),
    val stageProbeEvidence: List<DeveloperStageProbeEvidence> = emptyList(),
)

data class DeveloperStageProbeEvidence(
    val stageKey: String,
    val probeType: String,
    val outcome: String,
)

interface DeveloperAnalyticsSource {
    suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload
}
