@file:Suppress("detekt.InvalidPackageDeclaration")

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
    private val activeProbeSafetyPolicy: ActiveProbeSafetyPolicy,
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
        runDetectionStage: suspend (String, Int, HomeCompositeStageSpec) -> Unit,
        runPassiveVpnRouteEvidenceStage: (String, Int, HomeCompositeStageSpec) -> Unit,
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
        if (auditResult == null) {
            if (isAuditRunning()) {
                markStageFailure(runId, 0, "${auditSpec.label} timed out", "Audit did not complete in time.")
            }
            skipRemaining(runId, from = 1, reason = "audit stage failed", updateStage)
            finalizeRun(runId, null, null, false, false)
            return
        }
        val (auditSessionId, auditSession) = auditResult
        val auditSummary = buildCompletedStageSummary(auditSpec, auditSessionId, auditSession, scanRecordStore, json)
        updateStage(runId, 0) { auditSummary }
        if (auditSummary.status != DiagnosticsHomeCompositeStageStatus.COMPLETED) {
            skipRemaining(runId, from = 1, reason = "audit stage failed", updateStage)
            finalizeRun(runId, null, null, false, false)
            return
        }
        val audit = diagnosticsHomeWorkflowService.finalizeHomeAudit(auditSessionId)
        updateStage(runId, 0) { c ->
            c.copy(headline = audit.headline, summary = audit.summary, recommendationContributor = audit.actionable)
        }

        // Detection stage (index 1) runs after audit. Uses DetectionRunner, not a profile scan.
        val detectionSpec = QuickScanStageSpecs[1]
        if (detectionSpec.kind == HomeCompositeStageKind.DETECTION_SIGNALS) {
            runDetectionStage(runId, 1, detectionSpec)
        }

        val passiveSpecIndex =
            QuickScanStageSpecs.indexOfFirst {
                it.kind == HomeCompositeStageKind.PASSIVE_VPN_ROUTE_EVIDENCE
            }
        if (passiveSpecIndex >= 0) {
            runPassiveVpnRouteEvidenceStage(runId, passiveSpecIndex, QuickScanStageSpecs[passiveSpecIndex])
        }

        val sSpec = QuickScanStageSpecs.last()
        val sIndex = QuickScanStageSpecs.lastIndex
        val sResult =
            executeStrategyStageWithRetry(
                runId = runId,
                stageIndex = sIndex,
                spec = sSpec,
                executeStage = executeStage,
            )
        var auditOutcome: DiagnosticsHomeAuditOutcome? = audit
        if (sResult != null) {
            val (sId, sSession) = sResult
            val sSummary = buildCompletedStageSummary(sSpec, sId, sSession, scanRecordStore, json)
            updateStage(runId, sIndex) { sSummary }
            if (
                auditOutcome?.actionable != true &&
                sSummary.status == DiagnosticsHomeCompositeStageStatus.COMPLETED
            ) {
                val sa = diagnosticsHomeWorkflowService.finalizeHomeAudit(sId)
                if (sa.actionable) {
                    auditOutcome = sa
                    updateStage(runId, sIndex) { c ->
                        c.copy(headline = sa.headline, summary = sa.summary, recommendationContributor = true)
                    }
                }
            }
        }
        finalizeRun(runId, auditOutcome, null, false, false)
    }

    private suspend fun executeStrategyStageWithRetry(
        runId: String,
        stageIndex: Int,
        spec: HomeCompositeStageSpec,
        executeStage: suspend (
            String,
            Int,
            HomeCompositeStageSpec,
            Boolean,
            Int?,
        ) -> Pair<String, DiagnosticScanSession>?,
    ): Pair<String, DiagnosticScanSession>? {
        var result =
            executeStage(
                runId,
                stageIndex,
                spec,
                true,
                activeProbeSafetyPolicy.quickScanMaxCandidates,
            )
        repeat(activeProbeSafetyPolicy.stageRetryBudget) {
            if (result != null) return result
            delay(activeProbeSafetyPolicy.stageRetryDelayMs)
            result =
                executeStage(
                    runId,
                    stageIndex,
                    spec,
                    true,
                    activeProbeSafetyPolicy.quickScanMaxCandidates,
                )
        }
        return result
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
