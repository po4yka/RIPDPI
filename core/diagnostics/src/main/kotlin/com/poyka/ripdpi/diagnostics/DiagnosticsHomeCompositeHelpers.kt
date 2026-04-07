package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

internal suspend fun buildCompletedStageSummary(
    spec: HomeCompositeStageSpec,
    sessionId: String,
    session: DiagnosticScanSession,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
): DiagnosticsHomeCompositeStageSummary {
    val persistedSession = scanRecordStore.getScanSession(sessionId)
    val report =
        persistedSession
            ?.reportJson
            ?.takeIf(String::isNotBlank)
            ?.let { reportJson -> DiagnosticsSessionQueries.decodeScanReport(json, reportJson) }
    val status =
        if (session.status == "completed") {
            DiagnosticsHomeCompositeStageStatus.COMPLETED
        } else {
            DiagnosticsHomeCompositeStageStatus.FAILED
        }
    val summary = report?.summary?.ifBlank { session.summary } ?: session.summary
    val headline =
        when {
            status == DiagnosticsHomeCompositeStageStatus.FAILED -> "${spec.label} failed"
            report?.diagnoses?.isNotEmpty() == true -> spec.label
            else -> "${spec.label} complete"
        }
    return DiagnosticsHomeCompositeStageSummary(
        stageKey = spec.key,
        stageLabel = spec.label,
        profileId = spec.profileId,
        pathMode = spec.pathMode,
        sessionId = sessionId,
        status = status,
        headline = headline,
        summary = summary,
        recommendationContributor = false,
    )
}

internal fun buildHomeCompositeOutcome(
    runId: String,
    auditOutcome: DiagnosticsHomeAuditOutcome?,
    coverageNote: String?,
    dnsIssuesDetected: Boolean,
    networkChanged: Boolean,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
): DiagnosticsHomeCompositeOutcome {
    val progress = requireNotNull(progressState.value[runId]) { "Unknown Home diagnostics run '$runId'" }
    val stageSummaries = progress.stages
    val completedStageCount = stageSummaries.count { it.status == DiagnosticsHomeCompositeStageStatus.COMPLETED }
    val failedStageCount =
        stageSummaries.count {
            it.status == DiagnosticsHomeCompositeStageStatus.FAILED ||
                it.status == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE
        }
    val skippedStageCount = stageSummaries.count { it.status == DiagnosticsHomeCompositeStageStatus.SKIPPED }
    val actionable = auditOutcome?.actionable == true
    val fingerprintHash = auditOutcome?.fingerprintHash ?: progress.fingerprintHash
    val failureSuffix = if (failedStageCount != 1) "s" else ""
    val fallbackBase =
        "Completed $completedStageCount of ${stageSummaries.size} diagnostics stages." +
            if (failedStageCount >
                0
            ) {
                " $failedStageCount stage$failureSuffix finished with failures or were unavailable."
            } else {
                ""
            }
    val outcomeSummary =
        listOfNotNull(
            auditOutcome?.summary?.takeIf { it.isNotBlank() } ?: fallbackBase,
            coverageNote,
            "DNS issues were detected during the audit.".takeIf { dnsIssuesDetected },
            "Network changed during analysis \u2014 results may not reflect current network.".takeIf { networkChanged },
        ).joinToString(" ")
    val headline =
        when {
            actionable -> "Analysis complete and settings applied"
            failedStageCount > 0 -> "Analysis finished \u2014 $failedStageCount of ${stageSummaries.size} stages failed"
            else -> "Analysis complete"
        }
    return DiagnosticsHomeCompositeOutcome(
        runId = runId,
        fingerprintHash = fingerprintHash,
        actionable = actionable,
        headline = headline,
        summary = outcomeSummary,
        recommendationSummary = auditOutcome?.recommendationSummary,
        confidenceSummary = auditOutcome?.confidenceSummary,
        coverageSummary = auditOutcome?.coverageSummary,
        appliedSettings = auditOutcome?.appliedSettings.orEmpty(),
        recommendedSessionId = auditOutcome?.takeIf { it.actionable }?.sessionId,
        stageSummaries = stageSummaries,
        completedStageCount = completedStageCount,
        failedStageCount = failedStageCount,
        skippedStageCount = skippedStageCount,
        bundleSessionIds = stageSummaries.mapNotNull { it.sessionId },
    )
}

internal suspend fun decodeReport(
    scanRecordStore: DiagnosticsScanRecordStore,
    sessionId: String?,
    json: Json,
) = sessionId?.let {
    scanRecordStore.getScanSession(it)?.reportJson?.let { r ->
        DiagnosticsSessionQueries.decodeScanReport(json, r)
    }
}

internal suspend fun crossValidateHomeStrategy(
    runId: String,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
): String? {
    val stages = progressState.value[runId]?.stages ?: return null
    val auditReport =
        decodeReport(
            scanRecordStore,
            stages.firstOrNull { it.stageKey == HomeCompositeStageSpecs[0].key }?.sessionId,
            json,
        )
    val dpiFullReport =
        decodeReport(
            scanRecordStore,
            stages.firstOrNull { it.stageKey == HomeCompositeStageSpecs[2].key }?.sessionId,
            json,
        )
    val auditProbeHosts =
        auditReport
            ?.strategyProbeReport
            ?.targetSelection
            ?.domainHosts
            ?.toSet() ?: emptySet()
    val coverageGapCount =
        dpiFullReport?.observations?.count { obs ->
            obs.kind == ObservationKind.DOMAIN && obs.domain != null && obs.target !in auditProbeHosts &&
                (
                    obs.domain.transportFailure != TransportFailureKind.NONE ||
                        obs.domain.httpStatus == HttpProbeStatus.UNREACHABLE
                )
        } ?: 0
    val suffix = if (coverageGapCount != 1) "s" else ""
    return if (coverageGapCount >
        0
    ) {
        "$coverageGapCount additional domain$suffix showed connectivity issues not covered by the strategy probe."
    } else {
        null
    }
}

internal suspend fun detectHomeRunDnsIssues(
    runId: String,
    progressState: MutableStateFlow<Map<String, DiagnosticsHomeCompositeProgress>>,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
): Boolean {
    val auditSessionId =
        progressState.value[runId]
            ?.stages
            ?.firstOrNull { it.stageKey == HomeCompositeStageSpecs[0].key }
            ?.sessionId ?: return false
    return decodeReport(scanRecordStore, auditSessionId, json)?.resolverRecommendation != null
}
