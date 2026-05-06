package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

private const val LogsStateSubscriptionMillis = 5_000L

@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val dependencies: LogsViewModelDependencies,
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
            dependencies.serviceStateStore.telemetry.map { telemetry ->
                dependencies.logAggregatorUseCase.runtimeLogs(telemetry)
            }

        private val diagnosticsEventLogs =
            combine(
                dependencies.diagnosticsTimelineSource.nativeEvents,
                dependencies.diagnosticsTimelineSource.liveNativeEvents,
                dependencies.diagnosticsTimelineSource.activeScanProgress,
                dependencies.diagnosticsTimelineSource.activeConnectionSession,
            ) { persisted, live, activeProgress, activeConnection ->
                dependencies.logAggregatorUseCase.diagnosticLogs(
                    persisted = persisted,
                    live = live,
                    activeProgress = activeProgress,
                    activeConnection = activeConnection,
                )
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
                dependencies.logAggregatorUseCase.merge(
                    manualLogs = manualLogs,
                    serviceLogs = serviceLogs,
                    runtimeLogs = runtimeLogs,
                    diagnosticsLogs = diagnosticsLogs,
                    clearedAfter = clearedAfter,
                )
            }

        val uiState: StateFlow<LogsUiState> =
            combine(
                mergedLogs,
                activeSubsystems,
                activeSeverities,
                activeSessionOnly,
                autoScrollEnabled,
            ) { logs, subsystems, severities, activeSessionOnly, isAutoScroll ->
                LogsUiState(
                    logs = logs.toImmutableList(),
                    activeSubsystems = subsystems.toImmutableSet(),
                    activeSeverities = severities.toImmutableSet(),
                    showActiveSessionOnly = activeSessionOnly,
                    isAutoScroll = isAutoScroll,
                )
            }.combine(refreshing) { state, isRefreshing ->
                state.copy(isRefreshing = isRefreshing)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(LogsStateSubscriptionMillis),
                initialValue = LogsUiState(),
            )

        init {
            dependencies.serviceEventObserver.observe(
                scope = viewModelScope,
                serviceStateStore = dependencies.serviceStateStore,
                appendServiceEntry = ::appendServiceEntry,
            )
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
                dependencies.logEntryMapper.manualEntry(
                    message = message,
                    type = type,
                    createdAtMs = now,
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

        private companion object {
            const val REFRESH_SETTLE_DELAY_MS = 300L
        }
    }
