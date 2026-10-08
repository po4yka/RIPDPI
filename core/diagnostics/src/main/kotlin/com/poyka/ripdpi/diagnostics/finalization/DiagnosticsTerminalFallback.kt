package com.poyka.ripdpi.diagnostics.finalization

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.ActiveScanRegistry
import com.poyka.ripdpi.diagnostics.ScanFinalizationService
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.TerminalReportAwaitOutcome
import com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan
import com.poyka.ripdpi.diagnostics.persistPartialScanSession
import com.poyka.ripdpi.diagnostics.withLocalNetworkDeferrals
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal suspend fun persistTerminalOrCheckpointFallback(
    prepared: PreparedDiagnosticsScan,
    outcome: TerminalReportAwaitOutcome,
    scanRecordStore: DiagnosticsScanRecordStore,
    activeScanRegistry: ActiveScanRegistry,
    scanFinalizationService: ScanFinalizationService,
    json: Json,
): Boolean =
    when (outcome) {
        is TerminalReportAwaitOutcome.Terminal -> {
            val runningSession =
                scanRecordStore.getScanSession(prepared.sessionId)?.takeIf { it.status == "running" }
                    ?: return false
            val finalizeFailure =
                runCatching {
                    scanFinalizationService.finalize(
                        prepared = prepared,
                        reportJson = outcome.reportJson,
                        ownedInPathRouteAtCompletion = outcome.ownedInPathRouteAtCompletion,
                    )
                }.exceptionOrNull()
            if (finalizeFailure is CancellationException) throw finalizeFailure
            if (finalizeFailure != null) {
                Logger.w(finalizeFailure) {
                    "Scan finalization failed; retained terminal report"
                }
                if (
                    prepared.pathMode == ScanPathMode.IN_PATH &&
                    activeScanRegistry.cancellationSummaryFor(prepared.sessionId) == null
                ) {
                    scanRecordStore.upsertScanSession(
                        runningSession.copy(
                            status = "failed",
                            summary = finalizeFailure.message ?: "Diagnostics scan finalization failed",
                            reportJson =
                                prepared.scopeReportJson(
                                    runningSession.reportJson
                                        ?: outcome.reportJson.withLocalNetworkDeferrals(prepared, json),
                                    json,
                                ),
                            finishedAt = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    persistPartialScanSession(
                        runningSession,
                        outcome.reportJson,
                        prepared,
                        scanRecordStore,
                        json,
                    )
                }
            }
            true
        }

        is TerminalReportAwaitOutcome.TerminalUnavailable -> {
            outcome.latestCheckpointJson?.let { checkpointJson ->
                val runningSession =
                    scanRecordStore.getScanSession(prepared.sessionId)?.takeIf { it.status == "running" }
                if (runningSession == null) {
                    false
                } else {
                    persistPartialScanSession(runningSession, checkpointJson, prepared, scanRecordStore, json)
                    true
                }
            } ?: false
        }
    }
