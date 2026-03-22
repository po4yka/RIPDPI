package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

internal object DiagnosticsReportPersister {
    suspend fun persistScanReport(
        report: EngineScanReportWire,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
        serviceStateStore: ServiceStateStore,
        json: Json,
    ) {
        val normalizedReport =
            report.copy(
                results = report.results.map { result -> result.withDerivedProbeRetryCount() },
            )
        val existing = scanRecordStore.getScanSession(report.sessionId)
        scanRecordStore.upsertScanSession(
            ScanSessionEntity(
                id = normalizedReport.sessionId,
                profileId = normalizedReport.profileId,
                approachProfileId = existing?.approachProfileId,
                approachProfileName = existing?.approachProfileName,
                strategyId = existing?.strategyId,
                strategyLabel = existing?.strategyLabel,
                strategyJson = existing?.strategyJson,
                pathMode = normalizedReport.pathMode.name,
                serviceMode = serviceStateStore.status.value.second.name,
                status = "completed",
                summary = normalizedReport.summary,
                reportJson = json.encodeToString(EngineScanReportWire.serializer(), normalizedReport),
                startedAt = normalizedReport.startedAt,
                finishedAt = normalizedReport.finishedAt,
            ),
        )
        scanRecordStore.replaceProbeResults(
            normalizedReport.sessionId,
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
            },
        )
        bridgeEventsToHistory(normalizedReport, artifactWriteStore)
    }

    suspend fun persistScanFailure(
        sessionId: String,
        summary: String,
        scanRecordStore: DiagnosticsScanRecordStore,
    ) {
        val existing = scanRecordStore.getScanSession(sessionId) ?: return
        scanRecordStore.upsertScanSession(
            existing.copy(
                status = "failed",
                summary = summary,
                finishedAt = System.currentTimeMillis(),
            ),
        )
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
                    ),
                )
            }
    }

    private suspend fun bridgeEventsToHistory(
        report: EngineScanReportWire,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) {
        report.results.forEach { result ->
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = report.sessionId,
                    source = result.probeType,
                    level = if (result.outcome.contains("ok", ignoreCase = true)) "info" else "warn",
                    message = "${result.target}: ${result.outcome}",
                    createdAt = report.finishedAt,
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
}
