package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.ui.logs.LogEntryMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class LogsViewModelDependencies
    @Inject
    constructor(
        val serviceStateStore: ServiceStateStore,
        val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        val logEntryMapper: LogEntryMapper,
        val logAggregatorUseCase: LogAggregatorUseCase,
        val serviceEventObserver: LogsServiceEventObserver,
    )

class LogsServiceEventObserver
    @Inject
    constructor(
        private val logEntryMapper: LogEntryMapper,
    ) {
        fun observe(
            scope: CoroutineScope,
            serviceStateStore: ServiceStateStore,
            appendServiceEntry: (LogEntry) -> Unit,
        ) {
            observeStatusTransitions(scope, serviceStateStore, appendServiceEntry)
            observeFailures(scope, serviceStateStore, appendServiceEntry)
        }

        private fun observeStatusTransitions(
            scope: CoroutineScope,
            serviceStateStore: ServiceStateStore,
            appendServiceEntry: (LogEntry) -> Unit,
        ) {
            scope.launch {
                var previousStatus: Pair<AppStatus, Mode>? = null
                serviceStateStore.status.collect { currentStatus ->
                    val lastStatus = previousStatus
                    previousStatus = currentStatus
                    if (lastStatus == null || lastStatus == currentStatus) return@collect

                    when {
                        currentStatus.first == AppStatus.Running -> {
                            appendServiceEntry(serviceLifecycleEntry(currentStatus.second, "started", LogSeverity.Info))
                        }

                        lastStatus.first == AppStatus.Running && currentStatus.first == AppStatus.Halted -> {
                            appendServiceEntry(serviceLifecycleEntry(lastStatus.second, "stopped", LogSeverity.Info))
                        }
                    }
                }
            }
        }

        private fun observeFailures(
            scope: CoroutineScope,
            serviceStateStore: ServiceStateStore,
            appendServiceEntry: (LogEntry) -> Unit,
        ) {
            scope.launch {
                serviceStateStore.events.collect { event ->
                    when (event) {
                        is ServiceEvent.Failed -> appendServiceEntry(failureEntry(event.sender, event.reason))
                        is ServiceEvent.PermissionRevoked -> Unit
                    }
                }
            }
        }

        private fun serviceLifecycleEntry(
            mode: Mode,
            action: String,
            severity: LogSeverity,
        ): LogEntry =
            logEntryMapper.serviceLifecycleEntry(
                mode = mode,
                action = action,
                severity = severity,
                createdAtMs = System.currentTimeMillis(),
            )

        private fun failureEntry(
            sender: Sender,
            reason: FailureReason,
        ): LogEntry =
            logEntryMapper.failureEntry(
                sender = sender,
                reason = reason,
                createdAtMs = System.currentTimeMillis(),
            )
    }
