package com.poyka.ripdpi.ui.logs

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogType
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TimestampTimeSeconds
import java.util.Locale
import javax.inject.Inject

class LogEntryMapper
    @Inject
    constructor(
        private val stringResolver: StringResolver,
        private val timestamps: LocalizedTimestampFormatter,
    ) {
        fun manualEntry(
            message: String,
            type: LogType,
            createdAtMs: Long,
        ): LogEntry =
            LogEntry(
                id = "manual-$createdAtMs-${message.hashCode()}",
                createdAtMs = createdAtMs,
                timestamp = formatTimestamp(createdAtMs),
                subsystem = type.defaultSubsystem(),
                severity = type.defaultSeverity(),
                message = message,
                source = "manual",
                isActiveSession = true,
            )

        fun serviceLifecycleEntry(
            mode: Mode,
            action: String,
            severity: LogSeverity,
            createdAtMs: Long,
        ): LogEntry =
            LogEntry(
                id = "service-$createdAtMs-${mode.name.lowercase(Locale.ROOT)}-$action",
                createdAtMs = createdAtMs,
                timestamp = formatTimestamp(createdAtMs),
                subsystem = LogSubsystem.Service,
                severity = severity,
                message =
                    stringResolver.getString(
                        R.string.logs_service_action_format,
                        mode.displayName(stringResolver),
                        action,
                    ),
                source = "service",
                isActiveSession = true,
            )

        fun failureEntry(
            sender: Sender,
            reason: FailureReason,
            createdAtMs: Long,
        ): LogEntry =
            LogEntry(
                id = "service-failure-$createdAtMs-${sender.senderName.hashCode()}",
                createdAtMs = createdAtMs,
                timestamp = formatTimestamp(createdAtMs),
                subsystem = LogSubsystem.Service,
                severity = LogSeverity.Error,
                message =
                    stringResolver.getString(
                        R.string.logs_service_failure_format,
                        sender.senderName,
                        reason.displayMessage,
                    ),
                source = "service",
                isActiveSession = true,
            )

        fun runtimeEvents(
            events: List<NativeRuntimeEvent>,
            defaultSubsystem: LogSubsystem,
            isActiveSession: Boolean,
        ): List<LogEntry> {
            val timestampSnapshot = timestamps.snapshot()
            return events.map { event -> runtimeEvent(event, defaultSubsystem, isActiveSession, timestampSnapshot) }
        }

        fun diagnosticEvents(
            events: List<DiagnosticEvent>,
            activeSessionIds: Set<String>,
            isLiveEvent: Boolean,
        ): List<LogEntry> {
            val timestampSnapshot = timestamps.snapshot()
            return events.map { event -> diagnosticEvent(event, activeSessionIds, isLiveEvent, timestampSnapshot) }
        }

        private fun runtimeEvent(
            event: NativeRuntimeEvent,
            defaultSubsystem: LogSubsystem,
            isActiveSession: Boolean,
            timestampSnapshot: LocalizedTimestampFormatter.Snapshot,
        ): LogEntry {
            val subsystem = normalizeLogSubsystem(event.subsystem, event.source, defaultSubsystem)
            return LogEntry(
                id = "runtime-${event.createdAt}-${event.source}-${event.message.hashCode()}",
                createdAtMs = event.createdAt,
                timestamp = timestampSnapshot.format(event.createdAt, TimestampTimeSeconds),
                subsystem = subsystem,
                severity = severityFromLevel(event.level),
                message = event.message,
                source = event.source,
                runtimeId = event.runtimeId,
                diagnosticsSessionId = null,
                isActiveSession = isActiveSession,
            )
        }

        private fun diagnosticEvent(
            event: DiagnosticEvent,
            activeSessionIds: Set<String>,
            isLiveEvent: Boolean,
            timestampSnapshot: LocalizedTimestampFormatter.Snapshot,
        ): LogEntry {
            val diagnosticsSessionId = event.sessionId ?: event.connectionSessionId
            val isActive =
                isLiveEvent ||
                    (diagnosticsSessionId != null && diagnosticsSessionId in activeSessionIds)
            return LogEntry(
                id = event.id,
                createdAtMs = event.createdAt,
                timestamp = timestampSnapshot.format(event.createdAt, TimestampTimeSeconds),
                subsystem = normalizeLogSubsystem(event.subsystem, event.source, LogSubsystem.Diagnostics),
                severity = severityFromLevel(event.level),
                message = event.message,
                source = event.source,
                runtimeId = event.runtimeId,
                diagnosticsSessionId = diagnosticsSessionId,
                isActiveSession = isActive,
            )
        }

        private fun formatTimestamp(timestampMs: Long): String = timestamps.format(timestampMs, TimestampTimeSeconds)
    }

private fun LogType.defaultSubsystem(): LogSubsystem =
    when (this) {
        LogType.DNS -> LogSubsystem.Diagnostics
        LogType.CONN, LogType.ERR, LogType.WARN -> LogSubsystem.Service
    }

private fun LogType.defaultSeverity(): LogSeverity =
    when (this) {
        LogType.DNS, LogType.CONN -> LogSeverity.Info
        LogType.ERR -> LogSeverity.Error
        LogType.WARN -> LogSeverity.Warn
    }

private fun severityFromLevel(level: String): LogSeverity =
    when (level.lowercase(Locale.ROOT)) {
        "trace", "debug" -> LogSeverity.Debug
        "warn", "warning" -> LogSeverity.Warn
        "error" -> LogSeverity.Error
        else -> LogSeverity.Info
    }

private fun normalizeLogSubsystem(
    subsystem: String?,
    source: String,
    fallback: LogSubsystem,
): LogSubsystem =
    when ((subsystem ?: source).lowercase(Locale.ROOT)) {
        "service" -> LogSubsystem.Service
        "proxy", "autolearn" -> LogSubsystem.Proxy
        "tunnel", "worker" -> LogSubsystem.Tunnel
        "diagnostics", "dns", "domain", "tcp", "quic", "telegram", "throughput", "strategy" -> LogSubsystem.Diagnostics
        else -> fallback
    }

private fun Mode.displayName(stringResolver: StringResolver): String =
    when (this) {
        Mode.Proxy -> stringResolver.getString(R.string.logs_mode_proxy)
        Mode.VPN -> stringResolver.getString(R.string.logs_mode_vpn)
    }
