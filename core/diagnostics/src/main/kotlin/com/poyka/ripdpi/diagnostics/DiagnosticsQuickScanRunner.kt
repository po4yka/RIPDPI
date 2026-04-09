package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Extracted quick-scan execution logic to keep [DefaultDiagnosticsHomeCompositeRunService]
 * within its LoC budget. This is an internal helper, not a standalone service.
 */
internal class DiagnosticsQuickScanRunner(
    private val scanRecordStore: DiagnosticsScanRecordStore,
    private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
    private val json: Json,
) {
    suspend fun execute(
        runId: String,
        executeStage: suspend (
            String,
            Int,
            HomeCompositeStageSpec,
            Boolean,
            Int?,
        ) -> Pair<String, DiagnosticScanSession>?,
        markStageFailure: (String, Int, String, String) -> Unit,
        updateStage: (
            String,
            Int,
            (DiagnosticsHomeCompositeStageSummary) -> DiagnosticsHomeCompositeStageSummary,
        ) -> Unit,
        isAuditRunning: () -> Boolean,
        finalizeRun: suspend (String, DiagnosticsHomeAuditOutcome?, String?, Boolean, Boolean) -> Unit,
    ) {
        val auditSpec = QuickScanStageSpecs[0]
        val auditResult = executeStage(runId, 0, auditSpec, false, null)
        if (auditResult == null || auditResult.second.status != "completed") {
            if (auditResult == null && isAuditRunning()) {
                markStageFailure(runId, 0, "${auditSpec.label} timed out", "Audit did not complete in time.")
            }
            skipRemaining(runId, from = 1, reason = "audit stage failed", updateStage)
            finalizeRun(runId, null, null, false, false)
            return
        }
        val (auditSessionId, auditSession) = auditResult
        val auditSummary = buildCompletedStageSummary(auditSpec, auditSessionId, auditSession, scanRecordStore, json)
        updateStage(runId, 0) { auditSummary }
        val audit = diagnosticsHomeWorkflowService.finalizeHomeAudit(auditSessionId)
        updateStage(runId, 0) { c ->
            c.copy(headline = audit.headline, summary = audit.summary, recommendationContributor = audit.actionable)
        }

        val sSpec = QuickScanStageSpecs[1]
        var sResult = executeStage(runId, 1, sSpec, true, QuickScanMaxCandidates)
        if (sResult == null) {
            delay(StageRetryDelayMs)
            sResult = executeStage(runId, 1, sSpec, true, QuickScanMaxCandidates)
        }
        var auditOutcome: DiagnosticsHomeAuditOutcome? = audit
        if (sResult != null) {
            val (sId, sSession) = sResult
            val sSummary = buildCompletedStageSummary(sSpec, sId, sSession, scanRecordStore, json)
            updateStage(runId, 1) { sSummary }
            if (auditOutcome?.actionable != true && sSession.status == "completed") {
                val sa = diagnosticsHomeWorkflowService.finalizeHomeAudit(sId)
                if (sa.actionable) {
                    auditOutcome = sa
                    updateStage(runId, 1) { c ->
                        c.copy(headline = sa.headline, summary = sa.summary, recommendationContributor = true)
                    }
                }
            }
        }
        finalizeRun(runId, auditOutcome, null, false, false)
    }

    private fun skipRemaining(
        runId: String,
        from: Int,
        reason: String,
        updateStage: (
            String,
            Int,
            (DiagnosticsHomeCompositeStageSummary) -> DiagnosticsHomeCompositeStageSummary,
        ) -> Unit,
    ) {
        QuickScanStageSpecs.drop(from).forEachIndexed { i, spec ->
            updateStage(runId, from + i) { c ->
                c.copy(
                    status = DiagnosticsHomeCompositeStageStatus.SKIPPED,
                    headline = "${spec.label} skipped",
                    summary = "Skipped: $reason.",
                )
            }
        }
    }
}
