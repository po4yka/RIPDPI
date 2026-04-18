package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.platform.StringResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class LogType {
    DNS,
    CONN,
    ERR,
    WARN,
}

enum class LogSubsystem {
    Service,
    Proxy,
    Tunnel,
    Diagnostics,
}

enum class LogSeverity {
    Debug,
    Info,
    Warn,
    Error,
}

data class LogEntry(
    val id: String,
    val createdAtMs: Long,
    val timestamp: String,
    val subsystem: LogSubsystem,
    val severity: LogSeverity,
    val message: String,
    val source: String,
    val runtimeId: String? = null,
    val diagnosticsSessionId: String? = null,
    val isActiveSession: Boolean = false,
) {
    val dedupeKey: String =
        listOf(
            subsystem.name,
            severity.name,
            source,
            runtimeId.orEmpty(),
            diagnosticsSessionId.orEmpty(),
            createdAtMs.toString(),
            message,
        ).joinToString("|")
}

data class LogsUiState(
    val logs: ImmutableList<LogEntry> = persistentListOf(),
    val activeSubsystems: ImmutableSet<LogSubsystem> = LogSubsystem.entries.toImmutableSet(),
    val activeSeverities: ImmutableSet<LogSeverity> = LogSeverity.entries.toImmutableSet(),
    val showActiveSessionOnly: Boolean = false,
    val isAutoScroll: Boolean = true,
    val bufferCapacity: Int = MaxLogEntries,
    val isRefreshing: Boolean = false,
) {
    val filteredLogs: ImmutableList<LogEntry>
        get() =
            filterLogs(
                logs = logs,
                subsystems = activeSubsystems,
                severities = activeSeverities,
                activeSessionOnly = showActiveSessionOnly,
            ).toImmutableList()

    val latestLog: LogEntry?
        get() = logs.lastOrNull()
}

private const val MaxLogEntries = 250
private const val LogsStateSubscriptionMillis = 5_000L

internal fun filterLogs(
    logs: List<LogEntry>,
    subsystems: Set<LogSubsystem>,
    severities: Set<LogSeverity>,
    activeSessionOnly: Boolean,
): List<LogEntry> =
    logs
        .asSequence()
        .filter { entry ->
            entry.subsystem in subsystems &&
                entry.severity in severities &&
                (!activeSessionOnly || entry.isActiveSession)
        }.toList()

internal fun classifyLogType(message: String): LogType {
    val normalized = message.lowercase(Locale.ROOT)
    return when {
        "dns" in normalized -> LogType.DNS
        "warn" in normalized || "warning" in normalized -> LogType.WARN
        "error" in normalized || "fail" in normalized -> LogType.ERR
        else -> LogType.CONN
    }
}

@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val manualLogBuffer = MutableStateFlow<List<LogEntry>>(emptyList())
        private val serviceLifecycleBuffer = MutableStateFlow<List<LogEntry>>(emptyList())
        private val activeSubsystems = MutableStateFlow(LogSubsystem.entries.toSet())
        private val activeSeverities = MutableStateFlow(LogSeverity.entries.toSet())
        private val activeSessionOnly = MutableStateFlow(false)
        private val autoScrollEnabled = MutableStateFlow(true)
        private val clearedAfterMs = MutableStateFlow<Long?>(null)
        private val refreshing = MutableStateFlow(false)

        private val runtimeEventLogs =
            serviceStateStore.telemetry.map { telemetry ->
                (
                    telemetry.proxyTelemetry.nativeEvents.map { event ->
                        event.toRuntimeLogEntry(defaultSubsystem = LogSubsystem.Proxy, isActiveSession = true)
                    } +
                        telemetry.tunnelTelemetry.nativeEvents.map { event ->
                            event.toRuntimeLogEntry(defaultSubsystem = LogSubsystem.Tunnel, isActiveSession = true)
                        }
                ).distinctBy(LogEntry::dedupeKey)
                    .sortedBy(LogEntry::createdAtMs)
            }

        private val diagnosticsEventLogs =
            combine(
                diagnosticsTimelineSource.nativeEvents,
                diagnosticsTimelineSource.liveNativeEvents,
                diagnosticsTimelineSource.activeScanProgress,
                diagnosticsTimelineSource.activeConnectionSession,
            ) { persisted, live, activeProgress, activeConnection ->
                val activeSessionIds = activeDiagnosticsSessionIds(activeProgress, activeConnection)
                (
                    persisted.map { event ->
                        event.toDiagnosticLogEntry(activeSessionIds = activeSessionIds, isLiveEvent = false)
                    } +
                        live.map { event ->
                            event.toDiagnosticLogEntry(activeSessionIds = activeSessionIds, isLiveEvent = true)
                        }
                ).distinctBy(LogEntry::dedupeKey)
                    .sortedBy(LogEntry::createdAtMs)
            }

        private val mergedLogs =
            combine(
                manualLogBuffer,
                serviceLifecycleBuffer,
                runtimeEventLogs,
                diagnosticsEventLogs,
                clearedAfterMs,
            ) {
                manualLogs: List<LogEntry>,
                serviceLogs: List<LogEntry>,
                runtimeLogs: List<LogEntry>,
                diagnosticsLogs: List<LogEntry>,
                clearedAfter: Long?,
                ->
                (manualLogs + serviceLogs + runtimeLogs + diagnosticsLogs)
                    .asSequence()
                    .filter { entry -> clearedAfter == null || entry.createdAtMs >= clearedAfter }
                    .distinctBy(LogEntry::dedupeKey)
                    .sortedBy(LogEntry::createdAtMs)
                    .toList()
                    .takeLast(MaxLogEntries)
            }

        val uiState: StateFlow<LogsUiState> =
            combine(
                mergedLogs,
                activeSubsystems,
                activeSeverities,
                activeSessionOnly,
                autoScrollEnabled,
                refreshing,
            ) { logs, subsystems, severities, activeSessionOnly, isAutoScroll, isRefreshing ->
                LogsUiState(
                    logs = logs.toImmutableList(),
                    activeSubsystems = subsystems.toImmutableSet(),
                    activeSeverities = severities.toImmutableSet(),
                    showActiveSessionOnly = activeSessionOnly,
                    isAutoScroll = isAutoScroll,
                    isRefreshing = isRefreshing,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(LogsStateSubscriptionMillis),
                initialValue = LogsUiState(),
            )

        init {
            observeStatusTransitions()
            observeFailures()
        }

        fun toggleSubsystemFilter(subsystem: LogSubsystem) {
            activeSubsystems.update { filters ->
                if (subsystem in filters) {
                    filters - subsystem
                } else {
                    filters + subsystem
                }
            }
        }

        fun toggleSeverityFilter(severity: LogSeverity) {
            activeSeverities.update { filters ->
                if (severity in filters) {
                    filters - severity
                } else {
                    filters + severity
                }
            }
        }

        fun setActiveSessionOnly(enabled: Boolean) {
            activeSessionOnly.value = enabled
        }

        fun clearLogs() {
            manualLogBuffer.value = emptyList()
            serviceLifecycleBuffer.value = emptyList()
            clearedAfterMs.value = System.currentTimeMillis()
        }

        fun setAutoScroll(enabled: Boolean) {
            autoScrollEnabled.value = enabled
        }

        fun refresh() {
            viewModelScope.launch {
                refreshing.value = true
                delay(REFRESH_SETTLE_DELAY_MS)
                refreshing.value = false
            }
        }

        fun appendLog(
            message: String,
            type: LogType = classifyLogType(message),
        ) {
            val now = System.currentTimeMillis()
            appendManualEntry(
                LogEntry(
                    id = "manual-$now-${message.hashCode()}",
                    createdAtMs = now,
                    timestamp = formatTimestamp(now),
                    subsystem = type.defaultSubsystem(),
                    severity = type.defaultSeverity(),
                    message = message,
                    source = "manual",
                    isActiveSession = true,
                ),
            )
        }

        private fun observeStatusTransitions() {
            viewModelScope.launch {
                var previousStatus: Pair<AppStatus, Mode>? = null
                serviceStateStore.status.collect { currentStatus ->
                    val lastStatus = previousStatus
                    previousStatus = currentStatus
                    if (lastStatus == null || lastStatus == currentStatus) {
                        return@collect
                    }

                    when {
                        currentStatus.first == AppStatus.Running -> {
                            appendServiceLifecycleEntry(
                                mode = currentStatus.second,
                                action = "started",
                                severity = LogSeverity.Info,
                            )
                        }

                        lastStatus.first == AppStatus.Running && currentStatus.first == AppStatus.Halted -> {
                            appendServiceLifecycleEntry(
                                mode = lastStatus.second,
                                action = "stopped",
                                severity = LogSeverity.Info,
                            )
                        }
                    }
                }
            }
        }

        private fun observeFailures() {
            viewModelScope.launch {
                serviceStateStore.events.collect { event ->
                    when (event) {
                        is ServiceEvent.Failed -> appendFailureLog(event.sender, event.reason)
                        is ServiceEvent.PermissionRevoked -> Unit
                    }
                }
            }
        }

        private fun appendServiceLifecycleEntry(
            mode: Mode,
            action: String,
            severity: LogSeverity,
        ) {
            val createdAt = System.currentTimeMillis()
            appendServiceEntry(
                LogEntry(
                    id = "service-$createdAt-${mode.name.lowercase(Locale.ROOT)}-$action",
                    createdAtMs = createdAt,
                    timestamp = formatTimestamp(createdAt),
                    subsystem = LogSubsystem.Service,
                    severity = severity,
                    message = stringResolver.getString(R.string.logs_service_action_format, mode.displayName(), action),
                    source = "service",
                    isActiveSession = true,
                ),
            )
        }

        private fun appendFailureLog(
            sender: Sender,
            reason: FailureReason,
        ) {
            val createdAt = System.currentTimeMillis()
            val detail = reason.displayMessage
            appendServiceEntry(
                LogEntry(
                    id = "service-failure-$createdAt-${sender.senderName.hashCode()}",
                    createdAtMs = createdAt,
                    timestamp = formatTimestamp(createdAt),
                    subsystem = LogSubsystem.Service,
                    severity = LogSeverity.Error,
                    message = stringResolver.getString(R.string.logs_service_failure_format, sender.senderName, detail),
                    source = "service",
                    isActiveSession = true,
                ),
            )
        }

        private fun appendManualEntry(entry: LogEntry) {
            manualLogBuffer.update { currentLogs ->
                (currentLogs + entry).takeLast(MaxLogEntries)
            }
        }

        private fun appendServiceEntry(entry: LogEntry) {
            serviceLifecycleBuffer.update { currentLogs ->
                (currentLogs + entry).takeLast(MaxLogEntries)
            }
        }

        private fun formatTimestamp(timestampMs: Long): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))

        private companion object {
            const val REFRESH_SETTLE_DELAY_MS = 300L
        }
    }

private fun activeDiagnosticsSessionIds(
    progress: ScanProgress?,
    connection: DiagnosticConnectionSession?,
): Set<String> = setOfNotNull(progress?.sessionId, connection?.id)

private fun NativeRuntimeEvent.toRuntimeLogEntry(
    defaultSubsystem: LogSubsystem,
    isActiveSession: Boolean,
): LogEntry {
    val subsystem = normalizeLogSubsystem(subsystem, source, defaultSubsystem)
    return LogEntry(
        id = "runtime-$createdAt-$source-${message.hashCode()}",
        createdAtMs = createdAt,
        timestamp = formatTimestamp(createdAt),
        subsystem = subsystem,
        severity = severityFromLevel(level),
        message = message,
        source = source,
        runtimeId = runtimeId,
        diagnosticsSessionId = null,
        isActiveSession = isActiveSession,
    )
}

private fun DiagnosticEvent.toDiagnosticLogEntry(
    activeSessionIds: Set<String>,
    isLiveEvent: Boolean,
): LogEntry {
    val diagnosticsSessionId = sessionId ?: connectionSessionId
    val isActive =
        isLiveEvent ||
            (diagnosticsSessionId != null && diagnosticsSessionId in activeSessionIds)
    return LogEntry(
        id = id,
        createdAtMs = createdAt,
        timestamp = formatTimestamp(createdAt),
        subsystem = normalizeLogSubsystem(subsystem, source, LogSubsystem.Diagnostics),
        severity = severityFromLevel(level),
        message = message,
        source = source,
        runtimeId = runtimeId,
        diagnosticsSessionId = diagnosticsSessionId,
        isActiveSession = isActive,
    )
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

private fun Mode.displayName(): String =
    when (this) {
        Mode.Proxy -> "Proxy"
        Mode.VPN -> "VPN"
    }

private fun formatTimestamp(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
