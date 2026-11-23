package com.poyka.ripdpi.ui.screens.logs

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
        internal fun runtimeLogs(eventsBySubsystem: Map<LogSubsystem, List<NativeRuntimeEvent>>): List<LogEntry> =
            eventsBySubsystem
                .flatMap { (subsystem, events) ->
                    logEntryMapper.runtimeEvents(events, subsystem, isActiveSession = true)
                }.dedupeAndSort()

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
                logEntryMapper.diagnosticEvents(persisted, activeSessionIds, isLiveEvent = false) +
                    logEntryMapper.diagnosticEvents(live, activeSessionIds, isLiveEvent = true)
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

internal fun ServiceTelemetrySnapshot.runtimeLogEvents(): Map<LogSubsystem, List<NativeRuntimeEvent>> =
    mapOf(
        LogSubsystem.Proxy to proxyTelemetry.nativeEvents,
        LogSubsystem.Tunnel to tunnelTelemetry.nativeEvents,
        LogSubsystem.Relay to relayTelemetry.nativeEvents,
        LogSubsystem.Warp to warpTelemetry.nativeEvents,
        LogSubsystem.AmneziaWg to awgTelemetry.nativeEvents,
    )
