package com.poyka.ripdpi.diagnostics.finalization

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeTaxonomy
import com.poyka.ripdpi.diagnostics.NativeSessionEvent
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition
import com.poyka.ripdpi.diagnostics.deriveProbeRetryCount
import com.poyka.ripdpi.diagnostics.hasAuthoritativeManualConflictCancellation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

// Must stay in sync with the same threshold used in DiagnosticsDao queries.
// CursorWindow caps a single row at ~2MB; reports above this never fit and
// previously caused SQLiteBlobTooBigException on read.
private const val MaxInlineReportJsonBytes = 1_500_000

internal object DiagnosticsReportPersister {
    suspend fun persistScanReport(
        report: EngineScanReportWire,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
        serviceStateStore: ServiceStateStore,
        json: Json,
        deferTerminal: Boolean,
    ) {
        val normalizedReport =
            report.copy(
                results = report.results.map { result -> result.withDerivedProbeRetryCount() },
            )
        val existing = scanRecordStore.getScanSession(report.sessionId)
        val manualConflictCancellation = existing?.takeIf { it.hasAuthoritativeManualConflictCancellation() }
        val encodedReport = json.encodeToString(EngineScanReportWire.serializer(), normalizedReport)
        val encodedReportBytes = encodedReport.toByteArray(Charsets.UTF_8).size
        val safeReportJson =
            if (encodedReportBytes <= MaxInlineReportJsonBytes) {
                encodedReport
            } else {
                Logger.withTag("DiagnosticsReportPersister").w {
                    "dropping oversized reportJson session=${normalizedReport.sessionId} bytes=$encodedReportBytes"
                }
                null
            }
        val resultEntities =
            normalizedReport.results.map { result ->
                ProbeResultEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = normalizedReport.sessionId,
                    probeType = result.probeType,
                    target = result.target,
                    outcome = result.outcome,
                    detailJson = json.encodeToString(ListSerializer(ProbeDetail.serializer()), result.details),
                    createdAt = normalizedReport.finishedAt,
                )
            }
        scanRecordStore.persistCompletedScan(
            ScanSessionEntity(
                id = normalizedReport.sessionId,
                profileId = normalizedReport.profileId,
                approachProfileId = existing?.approachProfileId,
                approachProfileName = existing?.approachProfileName,
                strategyId = existing?.strategyId,
                strategyLabel = existing?.strategyLabel,
                strategyJson = existing?.strategyJson,
                pathMode = normalizedReport.pathMode.name,
                serviceMode =
                    if (deferTerminal) {
                        existing?.serviceMode
                    } else {
                        serviceStateStore.status.value.second.name
                    },
                status = if (deferTerminal) "running" else manualConflictCancellation?.status ?: "completed",
                summary = manualConflictCancellation?.summary ?: normalizedReport.summary,
                reportJson = safeReportJson,
                reportCompletionKind =
                    normalizedReport.completionKind
                        .takeIf { it != com.poyka.ripdpi.diagnostics.contract.engine.ScanCompletionKind.NORMAL }
                        ?.name,
                reportTerminationReason = normalizedReport.terminationReason?.name,
                startedAt = normalizedReport.startedAt,
                finishedAt = normalizedReport.finishedAt.takeUnless { deferTerminal },
                launchOrigin = existing?.launchOrigin,
                triggerType = existing?.triggerType,
                triggerClassification = existing?.triggerClassification,
                triggerOccurredAt = existing?.triggerOccurredAt,
                triggerPreviousFingerprintHash = existing?.triggerPreviousFingerprintHash,
                triggerCurrentFingerprintHash = existing?.triggerCurrentFingerprintHash,
            ),
            resultEntities,
        )
        bridgeEventsToHistory(normalizedReport, artifactWriteStore)
    }

    suspend fun buildRawPathTerminalSession(
        sessionId: String,
        report: EngineScanReportWire?,
        result: RawPathExecutionResult,
        scanRecordStore: DiagnosticsScanRecordStore,
        finishedAt: Long,
    ): ScanSessionEntity {
        val existing =
            checkNotNull(scanRecordStore.getScanSession(sessionId)) {
                "Raw-path scan session disappeared before settlement: $sessionId"
            }
        val manualConflictCancellationRequested =
            existing.summary == BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary
        val evaluation = result.evaluateForHomeContinuation()
        val usableReport =
            evaluation.disposition == RawPathHomeSettlementDisposition.USABLE &&
                report?.reportDisposition == ScanReportDisposition.TERMINAL
        return existing.copy(
            status = if (!manualConflictCancellationRequested && usableReport) "completed" else "failed",
            summary =
                when {
                    manualConflictCancellationRequested -> {
                        BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary
                    }

                    evaluation.disposition == RawPathHomeSettlementDisposition.SUPERSEDED_BY_USER_STOP -> {
                        RawPathSettlementSupersededSummary
                    }

                    result.executionOutcome != RawPathExecutionOutcome.Completed -> {
                        result.executionFailure
                            ?: "$RawPathSettlementInconclusiveSummaryPrefix: ${evaluation.reason}"
                    }

                    !usableReport -> {
                        "$RawPathSettlementInconclusiveSummaryPrefix: ${evaluation.reason}"
                    }

                    else -> {
                        checkNotNull(report).summary
                    }
                },
            finishedAt = report?.finishedAt ?: finishedAt,
        )
    }

    suspend fun persistScanFailure(
        sessionId: String,
        summary: String,
        scanRecordStore: DiagnosticsScanRecordStore,
    ) {
        val existing = scanRecordStore.getScanSession(sessionId) ?: return
        val manualConflictCancellationWasPersisted =
            summary == BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary &&
                existing.summary == BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary
        if (existing.status != "running" && !manualConflictCancellationWasPersisted) {
            Logger.withTag("DiagnosticsReportPersister").w {
                "ignoring failure after terminal scan persistence session=$sessionId status=${existing.status}"
            }
            return
        }
        scanRecordStore.upsertScanSession(
            existing.copy(
                status = "failed",
                summary = summary,
                finishedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun persistScanCancellationCause(
        sessionId: String,
        summary: String,
        scanRecordStore: DiagnosticsScanRecordStore,
    ) {
        val existing = scanRecordStore.getScanSession(sessionId) ?: return
        scanRecordStore.upsertScanSession(existing.copy(summary = summary))
    }

    suspend fun persistNativeEvents(
        sessionId: String,
        payload: String?,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
        json: Json,
    ) {
        val events =
            payload
                ?.takeIf { it.isNotBlank() && it != "[]" }
                ?.let { json.decodeFromString(ListSerializer(NativeSessionEvent.serializer()), it) }
                .orEmpty()
        events.forEach { event ->
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    source = event.source,
                    level = event.level,
                    message = event.message,
                    createdAt = event.createdAt,
                    runtimeId = event.runtimeId,
                    mode = event.mode,
                    policySignature = event.policySignature,
                    fingerprintHash = event.fingerprintHash,
                    subsystem = event.subsystem,
                ),
            )
        }
    }

    suspend fun persistServiceNativeEvents(
        serviceTelemetry: com.poyka.ripdpi.data.ServiceTelemetrySnapshot,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) {
        (serviceTelemetry.proxyTelemetry.nativeEvents + serviceTelemetry.tunnelTelemetry.nativeEvents)
            .forEach { event ->
                artifactWriteStore.insertNativeSessionEvent(
                    NativeSessionEventEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = null,
                        source = event.source,
                        level = event.level,
                        message = event.message,
                        createdAt = event.createdAt,
                        runtimeId = event.runtimeId,
                        mode = event.mode,
                        policySignature = event.policySignature,
                        fingerprintHash = event.fingerprintHash,
                        subsystem = event.subsystem,
                    ),
                )
            }
    }

    private suspend fun bridgeEventsToHistory(
        report: EngineScanReportWire,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) {
        val reportResults = report.results.map { it.toProbeResult() }
        report.results.forEach { result ->
            val classification =
                DiagnosticsOutcomeTaxonomy.classifyProbeResult(
                    pathMode = report.pathMode,
                    result = result.toProbeResult(),
                    reportResults = reportResults,
                )
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = report.sessionId,
                    source = result.probeType,
                    level = classification.eventLevel,
                    message = "${result.target}: ${result.outcome}",
                    createdAt = report.finishedAt,
                    subsystem = "diagnostics",
                ),
            )
        }
    }

    private fun EngineScanReportWire.withDerivedProbeRetryCount(): EngineScanReportWire =
        copy(
            results =
                results.map { result ->
                    result.withDerivedProbeRetryCount()
                },
        )

    private fun com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire.withDerivedProbeRetryCount():
        com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire {
        val retryCount = deriveProbeRetryCount(details)
        return copy(probeRetryCount = probeRetryCount ?: retryCount)
    }

    private fun com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire.toProbeResult(): ProbeResult =
        ProbeResult(
            probeType = probeType,
            target = target,
            outcome = outcome,
            details = details,
            probeRetryCount = probeRetryCount,
        )
}
