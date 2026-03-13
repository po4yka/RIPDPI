package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.services.ServiceStateStore
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal object DiagnosticsReportPersister {

    suspend fun persistScanReport(
        report: ScanReport,
        historyRepository: DiagnosticsHistoryRepository,
        serviceStateStore: ServiceStateStore,
        json: Json,
    ) {
        val normalizedReport =
            report.copy(
                results = report.results.map { result -> result.withDerivedProbeRetryCount() },
            )
        val existing = historyRepository.getScanSession(report.sessionId)
        historyRepository.upsertScanSession(
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
                reportJson = json.encodeToString(ScanReport.serializer(), normalizedReport),
                startedAt = normalizedReport.startedAt,
                finishedAt = normalizedReport.finishedAt,
            ),
        )
        historyRepository.replaceProbeResults(
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
        bridgeEventsToHistory(normalizedReport, historyRepository)
    }

    suspend fun persistScanFailure(
        sessionId: String,
        summary: String,
        historyRepository: DiagnosticsHistoryRepository,
    ) {
        val existing = historyRepository.getScanSession(sessionId) ?: return
        historyRepository.upsertScanSession(
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
        historyRepository: DiagnosticsHistoryRepository,
        json: Json,
    ) {
        val events =
            payload
                ?.takeIf { it.isNotBlank() && it != "[]" }
                ?.let { json.decodeFromString(ListSerializer(NativeSessionEvent.serializer()), it) }
                .orEmpty()
        events.forEach { event ->
            historyRepository.insertNativeSessionEvent(
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
        serviceTelemetry: com.poyka.ripdpi.services.ServiceTelemetrySnapshot,
        historyRepository: DiagnosticsHistoryRepository,
    ) {
        (serviceTelemetry.proxyTelemetry.nativeEvents + serviceTelemetry.tunnelTelemetry.nativeEvents)
            .forEach { event ->
                historyRepository.insertNativeSessionEvent(
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
        report: ScanReport,
        historyRepository: DiagnosticsHistoryRepository,
    ) {
        report.results.forEach { result ->
            historyRepository.insertNativeSessionEvent(
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
}
