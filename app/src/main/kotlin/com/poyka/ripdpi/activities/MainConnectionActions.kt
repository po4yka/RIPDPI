package com.poyka.ripdpi.activities

import android.os.SystemClock
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.diagnostics.stableId
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class MainConnectionActions(
    private val mutations: MainMutationRunner,
    private val serviceController: ServiceController,
    private val serviceStateStore: ServiceStateStore,
    private val trafficStatsReader: TrafficStatsReader,
    private val stringResolver: StringResolver,
    val runtimeState: MutableStateFlow<ConnectionRuntimeState>,
    private val refreshPermissionSnapshot: () -> Unit,
) {
    private companion object {
        private const val PercentScale = 100
        private val CONNECTING_TIMEOUT = 30.seconds
    }

    private var connectionMetricsJob: Job? = null
    private var connectingTimeoutJob: Job? = null

    fun initialize() {
        observeStatus()
        observeServiceEvents()
    }

    fun startMode(mode: Mode) {
        setConnectingState()
        serviceController.start(mode)
    }

    fun stop() {
        serviceController.stop()
    }

    fun dismissError() {
        runtimeState.update { current ->
            if (current.connectionState != ConnectionState.Error) {
                current
            } else {
                current.copy(
                    connectionState = ConnectionState.Disconnected,
                    errorMessage = null,
                )
            }
        }
    }

    fun showPermissionIssue(issue: PermissionIssueUiState) {
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Disconnected,
                errorMessage = null,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
        mutations.trySend(MainEffect.ShowError(issue.message))
    }

    fun buildApproachSummary(
        settings: AppSettings,
        activeMode: Mode,
        approachStats: List<BypassApproachSummary>,
    ): HomeApproachSummaryUiState? {
        val strategyId =
            deriveBypassStrategySignature(
                settings = settings,
                routeGroup =
                    serviceStateStore.telemetry.value.proxyTelemetry.lastRouteGroup
                        ?.toString(),
                modeOverride = activeMode,
            ).stableId()
        val strategySummary =
            approachStats.firstOrNull {
                it.approachId.kind == BypassApproachKind.Strategy && it.approachId.value == strategyId
            }
        val profileSummary =
            settings.diagnosticsActiveProfileId
                .takeIf { it.isNotBlank() }
                ?.let { profileId ->
                    approachStats.firstOrNull {
                        it.approachId.kind == BypassApproachKind.Profile && it.approachId.value == profileId
                    }
                }
        val summary = strategySummary ?: profileSummary ?: return null
        return HomeApproachSummaryUiState(
            title = summary.displayName,
            verification = summary.verificationState.replaceFirstChar { it.uppercase() },
            successRate =
                summary.validatedSuccessRate?.let { "${(it * PercentScale).toInt()}%" } ?: "Unverified",
            supportingText =
                buildString {
                    append(summary.lastValidatedResult ?: "No validated diagnostics run yet")
                    append(" · ")
                    append("${summary.usageCount} runtime session(s)")
                },
        )
    }

    private fun observeStatus() {
        mutations.launch {
            serviceStateStore.status.collect { (status, _) ->
                when (status) {
                    AppStatus.Running -> onConnected()
                    AppStatus.Halted -> onHalted()
                }
                refreshPermissionSnapshot()
            }
        }
    }

    private fun observeServiceEvents() {
        mutations.launch {
            serviceStateStore.events.collect { event ->
                when (event) {
                    is ServiceEvent.Failed -> onServiceFailed(event.sender, event.reason)
                    is ServiceEvent.PermissionRevoked -> refreshPermissionSnapshot()
                }
            }
        }
    }

    private fun startConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob =
            mutations.launch {
                delay(CONNECTING_TIMEOUT)
                if (runtimeState.value.connectionState == ConnectionState.Connecting) {
                    showError(stringResolver.getString(R.string.connection_timed_out))
                }
            }
    }

    private fun cancelConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    private fun onConnected() {
        cancelConnectingTimeout()
        val baselineBytes = currentTransferredBytes()
        val connectedAtMs = SystemClock.elapsedRealtime()

        runtimeState.update { current ->
            if (current.connectionState == ConnectionState.Connected && current.connectionStartedAtMs != null) {
                current
            } else {
                current.copy(
                    connectionState = ConnectionState.Connected,
                    errorMessage = null,
                    connectionStartedAtMs = connectedAtMs,
                    baselineTransferredBytes = baselineBytes,
                    dataTransferred = 0L,
                    connectionDuration = ZERO,
                )
            }
        }
        startConnectionMetricsPolling()
    }

    private fun onHalted() {
        cancelConnectingTimeout()
        stopConnectionMetricsPolling()
        runtimeState.update { current ->
            if (current.connectionState == ConnectionState.Error) {
                current.copy(
                    connectionStartedAtMs = null,
                    baselineTransferredBytes = 0L,
                    dataTransferred = 0L,
                    connectionDuration = ZERO,
                )
            } else {
                ConnectionRuntimeState()
            }
        }
    }

    private fun onServiceFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val detail = reason.displayMessage
        val message = stringResolver.getString(R.string.failed_to_start, sender.senderName) + ": $detail"
        showError(message)
    }

    private fun showError(message: String) {
        cancelConnectingTimeout()
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Error,
                errorMessage = message,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
        mutations.trySend(MainEffect.ShowError(message))
    }

    private fun setConnectingState() {
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Connecting,
                errorMessage = null,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
        startConnectingTimeout()
    }

    private fun startConnectionMetricsPolling() {
        if (!shouldPollConnectionMetrics(runtimeState.value.connectionState)) {
            stopConnectionMetricsPolling()
            return
        }
        if (connectionMetricsJob?.isActive == true) {
            return
        }

        refreshConnectionMetrics()
        connectionMetricsJob =
            mutations.launch {
                while (true) {
                    delay(1.seconds)
                    refreshConnectionMetrics()
                }
            }
    }

    private fun stopConnectionMetricsPolling() {
        connectionMetricsJob?.cancel()
        connectionMetricsJob = null
    }

    private fun refreshConnectionMetrics() {
        val state = runtimeState.value
        state.connectionStartedAtMs ?: return
        if (!shouldPollConnectionMetrics(state.connectionState)) {
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val totalBytes = currentTransferredBytes()
        runtimeState.update { current ->
            val currentStartedAtMs = current.connectionStartedAtMs ?: return@update current
            if (current.connectionState != ConnectionState.Connected) {
                current
            } else {
                current.copy(
                    connectionDuration = (nowMs - currentStartedAtMs).coerceAtLeast(0L).milliseconds,
                    dataTransferred =
                        calculateTransferredBytes(
                            totalBytes = totalBytes,
                            baselineBytes = current.baselineTransferredBytes,
                        ),
                )
            }
        }
    }

    private fun currentTransferredBytes(): Long = trafficStatsReader.currentTransferredBytes()
}
