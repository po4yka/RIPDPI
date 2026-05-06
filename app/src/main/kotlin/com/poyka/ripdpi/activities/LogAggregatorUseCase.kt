package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.ScanProgress
import javax.inject.Inject

internal const val MaxLogEntries = 250

class LogAggregatorUseCase
    @Inject
    constructor(
        private val logEntryMapper: LogEntryMapper,
    ) {
        fun runtimeLogs(telemetry: ServiceTelemetrySnapshot): List<LogEntry> =
            (
                telemetry.proxyTelemetry.nativeEvents.map { event ->
                    logEntryMapper.runtimeEvent(
                        event = event,
                        defaultSubsystem = LogSubsystem.Proxy,
                        isActiveSession = true,
                    )
                } +
                    telemetry.tunnelTelemetry.nativeEvents.map { event ->
                        logEntryMapper.runtimeEvent(
                            event = event,
                            defaultSubsystem = LogSubsystem.Tunnel,
                            isActiveSession = true,
                        )
                    }
            ).dedupeAndSort()

        fun diagnosticLogs(
            persisted: List<DiagnosticEvent>,
            live: List<DiagnosticEvent>,
            activeProgress: ScanProgress?,
            activeConnection: DiagnosticConnectionSession?,
        ): List<LogEntry> {
            val activeSessionIds = setOfNotNull(activeProgress?.sessionId, activeConnection?.id)
            return (
                persisted.map { event ->
                    logEntryMapper.diagnosticEvent(
                        event = event,
                        activeSessionIds = activeSessionIds,
                        isLiveEvent = false,
                    )
                } +
                    live.map { event ->
                        logEntryMapper.diagnosticEvent(
                            event = event,
                            activeSessionIds = activeSessionIds,
                            isLiveEvent = true,
                        )
                    }
            ).dedupeAndSort()
        }

        fun merge(
            manualLogs: List<LogEntry>,
            serviceLogs: List<LogEntry>,
            runtimeLogs: List<LogEntry>,
            diagnosticsLogs: List<LogEntry>,
            clearedAfter: Long?,
        ): List<LogEntry> =
            (manualLogs + serviceLogs + runtimeLogs + diagnosticsLogs)
                .asSequence()
                .filter { entry -> clearedAfter == null || entry.createdAtMs >= clearedAfter }
                .distinctBy(LogEntry::dedupeKey)
                .sortedBy(LogEntry::createdAtMs)
                .toList()
                .takeLast(MaxLogEntries)

        private fun List<LogEntry>.dedupeAndSort(): List<LogEntry> =
            distinctBy(LogEntry::dedupeKey).sortedBy(LogEntry::createdAtMs)
    }
