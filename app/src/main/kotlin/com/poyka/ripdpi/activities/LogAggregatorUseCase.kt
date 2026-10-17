package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.ui.logs.LogEntryMapper
import javax.inject.Inject

internal const val MaxLogEntries = 250

class LogAggregatorUseCase
    @Inject
    constructor(
        private val logEntryMapper: LogEntryMapper,
    ) {
        fun runtimeLogs(telemetry: ServiceTelemetrySnapshot): List<LogEntry> =
            runtimeLogs(telemetry.proxyTelemetry.nativeEvents, telemetry.tunnelTelemetry.nativeEvents)

        internal fun runtimeLogs(
            proxyEvents: List<NativeRuntimeEvent>,
            tunnelEvents: List<NativeRuntimeEvent>,
        ): List<LogEntry> =
            (
                proxyEvents.map { event ->
                    logEntryMapper.runtimeEvent(
                        event = event,
                        defaultSubsystem = LogSubsystem.Proxy,
                        isActiveSession = true,
                    )
                } +
                    tunnelEvents.map { event ->
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
        ): List<LogEntry> =
            diagnosticLogs(
                persisted = persisted,
                live = live,
                activeSessionIds = setOfNotNull(activeProgress?.sessionId, activeConnection?.id),
            )

        internal fun diagnosticLogs(
            persisted: List<DiagnosticEvent>,
            live: List<DiagnosticEvent>,
            activeSessionIds: Set<String>,
        ): List<LogEntry> =
            (
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
