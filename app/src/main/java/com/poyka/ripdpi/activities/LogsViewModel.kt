package com.poyka.ripdpi.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.AppStateManager
import com.poyka.ripdpi.services.ServiceEvent
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
) {
    val filteredLogs: List<LogEntry>
        get() = logs.filter { it.type in activeFilters }
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

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val logBuffer = MutableStateFlow<List<LogEntry>>(emptyList())
    private val activeFilters = MutableStateFlow(LogType.entries.toSet())
    private val autoScrollEnabled = MutableStateFlow(true)

    val uiState: StateFlow<LogsUiState> = combine(
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
            AppStateManager.status.collect { currentStatus ->
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
            AppStateManager.events.collect { event ->
                when (event) {
                    is ServiceEvent.Failed -> appendFailureLog(event.sender)
                }
            }
        }
    }

    private fun appendConnectionLog(mode: Mode, action: String) {
        appendEntry(
            LogEntry(
                id = System.currentTimeMillis(),
                timestamp = formatTimestamp(System.currentTimeMillis()),
                type = LogType.CONN,
                message = "${mode.displayName()} service $action",
            ),
        )
    }

    private fun appendFailureLog(sender: Sender) {
        appendEntry(
            LogEntry(
                id = System.currentTimeMillis(),
                timestamp = formatTimestamp(System.currentTimeMillis()),
                type = LogType.ERR,
                message = "${sender.senderName} service failed to start",
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

private fun Mode.displayName(): String = when (this) {
    Mode.Proxy -> "Proxy"
    Mode.VPN -> "VPN"
}
