package com.poyka.ripdpi.ui.logs

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticRelayAttemptStage
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TimestampTimeSeconds
import com.poyka.ripdpi.ui.screens.logs.LogEntry
import com.poyka.ripdpi.ui.screens.logs.LogSeverity
import com.poyka.ripdpi.ui.screens.logs.LogSubsystem
import com.poyka.ripdpi.ui.screens.logs.LogType
import java.util.Locale
import javax.inject.Inject

private const val RelayAttemptStageKind = "relay_attempt_stage"

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
            started: Boolean,
            severity: LogSeverity,
            createdAtMs: Long,
        ): LogEntry {
            val action = if (started) "started" else "stopped"
            return LogEntry(
                id = "service-$createdAtMs-${mode.name.lowercase(Locale.ROOT)}-$action",
                createdAtMs = createdAtMs,
                timestamp = formatTimestamp(createdAtMs),
                subsystem = LogSubsystem.Service,
                severity = severity,
                message =
                    stringResolver.getString(
                        if (started) R.string.logs_service_started_format else R.string.logs_service_stopped_format,
                        mode.displayName(stringResolver),
                    ),
                source = "service",
                isActiveSession = true,
            )
        }

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
            if (events.isEmpty()) return emptyList()
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
            val severity = severityFromLevel(event.level)
            val relayStage = event.relayAttemptStage()
            val message = relayStage?.logMessage() ?: event.message
            val relayStageKey = relayStage?.logKey(event.runtimeId, event.createdAt, message)
            val eventKey = "${event.createdAt}-${event.source}-${event.runtimeId}-${message.hashCode()}"
            return LogEntry(
                id = relayStageKey ?: "runtime-$subsystem-$severity-$eventKey",
                createdAtMs = event.createdAt,
                timestamp = timestampSnapshot.format(event.createdAt, TimestampTimeSeconds),
                subsystem = subsystem,
                severity = severity,
                message = message,
                source = event.source,
                runtimeId = event.runtimeId,
                diagnosticsSessionId = null,
                isActiveSession = isActiveSession,
                relayStageKey = relayStageKey,
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
            val relayStage = event.relayAttemptStage
            val message = relayStage?.logMessage() ?: event.message
            val relayStageKey = relayStage?.logKey(event.runtimeId, event.createdAt, message)
            return LogEntry(
                id = relayStageKey ?: event.id,
                createdAtMs = event.createdAt,
                timestamp = timestampSnapshot.format(event.createdAt, TimestampTimeSeconds),
                subsystem = normalizeLogSubsystem(event.subsystem, event.source, LogSubsystem.Diagnostics),
                severity = severityFromLevel(event.level),
                message = message,
                source = event.source,
                runtimeId = event.runtimeId,
                diagnosticsSessionId = diagnosticsSessionId,
                isActiveSession = isActive,
                relayStageKey = relayStageKey,
            )
        }

        private fun formatTimestamp(timestampMs: Long): String = timestamps.format(timestampMs, TimestampTimeSeconds)
    }

private fun NativeRuntimeEvent.relayAttemptStage(): DiagnosticRelayAttemptStage? =
    if (kind == RelayAttemptStageKind) {
        DiagnosticRelayAttemptStage(
            attemptId = attemptId,
            attemptSequence = attemptSequence,
            stage = stage,
            outcome = outcome,
            durationMs = durationMs,
            failureStage = failureStage,
            failureClass = failureClass,
            ioErrorKind = ioErrorKind,
            osErrorCode = osErrorCode,
            peerClosePhase = peerClosePhase,
            carrierDisposition = carrierDisposition,
        )
    } else {
        null
    }

private fun DiagnosticRelayAttemptStage.logKey(
    runtimeId: String?,
    createdAt: Long,
    message: String,
): String =
    if (!runtimeId.isNullOrBlank() && attemptId != null && attemptSequence != null) {
        "relay-stage:$runtimeId:$attemptId:$attemptSequence"
    } else {
        "relay-stage:$createdAt:$message"
    }

private fun DiagnosticRelayAttemptStage.logMessage(): String =
    buildString {
        append("event=").append(RelayAttemptStageKind)
        attemptId?.let { append(" attemptId=").append(it) }
        attemptSequence?.let { append(" attemptSequence=").append(it) }
        stage?.let { append(" stage=").append(it) }
        outcome?.let { append(" outcome=").append(it) }
        durationMs?.let { append(" durationMs=").append(it) }
        failureStage?.let { append(" failureStage=").append(it) }
        failureClass?.let { append(" failureClass=").append(it) }
        ioErrorKind?.let { append(" ioErrorKind=").append(it) }
        osErrorCode?.let { append(" osErrorCode=").append(it) }
        peerClosePhase?.let { append(" peerClosePhase=").append(it) }
        carrierDisposition?.let { append(" carrierDisposition=").append(it) }
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
        "relay" -> LogSubsystem.Relay
        "warp" -> LogSubsystem.Warp
        "amneziawg", "awg" -> LogSubsystem.AmneziaWg
        "diagnostics", "dns", "domain", "tcp", "quic", "telegram", "throughput", "strategy" -> LogSubsystem.Diagnostics
        else -> fallback
    }

private fun Mode.displayName(stringResolver: StringResolver): String =
    when (this) {
        Mode.Proxy -> stringResolver.getString(R.string.logs_mode_proxy)
        Mode.VPN -> stringResolver.getString(R.string.logs_mode_vpn)
    }
