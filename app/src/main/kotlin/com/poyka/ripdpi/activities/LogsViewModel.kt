package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.platform.StringResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val type: LogType,
    val message: String,
)

data class LogsUiState(
    val logs: List<LogEntry> = emptyList(),
    val activeFilters: Set<LogType> = LogType.entries.toSet(),
    val isAutoScroll: Boolean = true,
    val bufferCapacity: Int = MaxLogEntries,
) {
    val filteredLogs: List<LogEntry>
        get() = logs.filter { it.type in activeFilters }

    val latestLog: LogEntry?
        get() = logs.lastOrNull()
}

private const val MaxLogEntries = 250

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
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val logBuffer = MutableStateFlow<List<LogEntry>>(emptyList())
        private val activeFilters = MutableStateFlow(LogType.entries.toSet())
        private val autoScrollEnabled = MutableStateFlow(true)

        val uiState: StateFlow<LogsUiState> =
            combine(
                logBuffer,
                activeFilters,
                autoScrollEnabled,
            ) { logs, filters, autoScroll ->
                LogsUiState(
                    logs = logs,
                    activeFilters = filters,
                    isAutoScroll = autoScroll,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LogsUiState(),
            )

        init {
            observeStatusTransitions()
            observeFailures()
        }

        fun toggleFilter(type: LogType) {
            activeFilters.update { filters ->
                if (type in filters) {
                    filters - type
                } else {
                    filters + type
                }
            }
        }

        fun clearLogs() {
            logBuffer.value = emptyList()
        }

        fun setAutoScroll(enabled: Boolean) {
            autoScrollEnabled.value = enabled
        }

        fun appendLog(
            message: String,
            type: LogType = classifyLogType(message),
        ) {
            appendEntry(
                LogEntry(
                    id = System.currentTimeMillis(),
                    timestamp = formatTimestamp(System.currentTimeMillis()),
                    type = type,
                    message = message,
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
                            appendConnectionLog(
                                mode = currentStatus.second,
                                action = "started",
                            )
                        }

                        lastStatus.first == AppStatus.Running && currentStatus.first == AppStatus.Halted -> {
                            appendConnectionLog(
                                mode = lastStatus.second,
                                action = "stopped",
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
                    }
                }
            }
        }

        private fun appendConnectionLog(
            mode: Mode,
            action: String,
        ) {
            appendEntry(
                LogEntry(
                    id = System.currentTimeMillis(),
                    timestamp = formatTimestamp(System.currentTimeMillis()),
                    type = LogType.CONN,
                    message = stringResolver.getString(R.string.logs_service_action_format, mode.displayName(), action),
                ),
            )
        }

        private fun appendFailureLog(
            sender: Sender,
            reason: FailureReason,
        ) {
            val detail = reason.displayMessage
            appendEntry(
                LogEntry(
                    id = System.currentTimeMillis(),
                    timestamp = formatTimestamp(System.currentTimeMillis()),
                    type = LogType.ERR,
                    message = stringResolver.getString(R.string.logs_service_failure_format, sender.senderName, detail),
                ),
            )
        }

        private fun appendEntry(entry: LogEntry) {
            logBuffer.update { currentLogs ->
                (currentLogs + entry).takeLast(MaxLogEntries)
            }
        }

        private fun formatTimestamp(timestampMs: Long): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

private fun Mode.displayName(): String =
    when (this) {
        Mode.Proxy -> "Proxy"
        Mode.VPN -> "VPN"
    }
