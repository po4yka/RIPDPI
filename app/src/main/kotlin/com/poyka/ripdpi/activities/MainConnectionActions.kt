package com.poyka.ripdpi.activities

import android.os.SystemClock
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.RuntimeCommandOrigin
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
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.ui.diagnostics.uiEvidenceNote
import com.poyka.ripdpi.ui.diagnostics.verificationLabelRes
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val currentSettings: () -> AppSettings,
    private val runtimeState: ConnectionRuntimeStateReducer,
    private val refreshPermissionSnapshot: () -> Unit,
    private val onLocalNetworkRequired: (Mode) -> Unit = {},
) {
    private companion object {
        private const val PercentScale = 100
        private val CONNECTING_TIMEOUT = 30.seconds
    }

    private var connectionMetricsJob: Job? = null
    private var connectingTimeoutJob: Job? = null
    private var lastNotifiedFailure: FailureFeedbackIdentity? = null
    private var failureFeedbackEpoch = 0L
    private val resultFeedback =
        ConnectionResultFeedback(
            controller = serviceController,
            service = serviceStateStore,
            hapticsEnabled = { !currentSettings().hapticFeedbackDisabled },
            onConnected = { mutations.trySend(MainEffect.ConnectionEstablished) },
        )

    fun initialize() {
        observeStatus()
        observeServiceEvents()
    }

    fun startMode(mode: Mode) =
        mutations.launch {
            failureFeedbackEpoch++
            resultFeedback.clear()
            if (mode == Mode.VPN && hasUnsupportedVpnDoq(currentSettings())) {
                mutations.trySend(
                    MainEffect.ShowError(
                        stringResolver.getString(R.string.dns_custom_doq_unavailable),
                        hapticFeedbackEnabled = !currentSettings().hapticFeedbackDisabled,
                    ),
                )
                return@launch
            }
            val attempt = resultFeedback.begin(mode)
            setConnectingState()
            var dispatchCompleted = false
            try {
                when (val result = serviceController.start(mode)) {
                    is ServiceStartResult.Accepted -> {
                        resultFeedback.accepted(attempt, result.receipt)
                    }

                    is ServiceStartResult.MaintenanceAccepted -> {
                        error("Expected a mode-bound user start")
                    }

                    is ServiceStartResult.Rejected -> {
                        refreshPermissionSnapshot()
                        val message =
                            stringResolver.getString(R.string.failed_to_start, result.mode.startSenderName) +
                                ": " +
                                result.reason.displayMessage(stringResolver)
                        showError(message)
                    }
                }
                dispatchCompleted = true
            } finally {
                if (!dispatchCompleted) resultFeedback.clear(attempt)
            }
        }

    fun stop() =
        mutations.launch {
            failureFeedbackEpoch++
            resultFeedback.clear()
            val wasConnecting = runtimeState.value.connectionState == ConnectionState.Connecting
            serviceController.stop()
            // An accepted start can still be suspended before the service publishes a
            // non-Halted status. In that window there is no status edge for observeStatus()
            // to consume after Stop, so clear the optimistic Connecting state locally.
            if (wasConnecting) {
                onHalted()
            }
        }

    fun dismissError() {
        failureFeedbackEpoch++
        runtimeState.update { current ->
            if (current.connectionState != ConnectionState.Error) {
                current
            } else {
                current.copy(
                    connectionState = ConnectionState.Disconnected,
                    errorMessage = null,
                    connectingStartedAtMs = null,
                )
            }
        }
    }

    fun showPermissionIssue(issue: PermissionIssueUiState) {
        failureFeedbackEpoch++
        resultFeedback.clear()
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Disconnected,
                errorMessage = null,
                connectingStartedAtMs = null,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
        mutations.trySend(
            MainEffect.ShowError(issue.message, hapticFeedbackEnabled = !currentSettings().hapticFeedbackDisabled),
        )
    }

    fun buildApproachSummary(
        settings: AppSettings,
        activeMode: Mode,
        approachStats: List<BypassApproachSummary>,
    ): HomeApproachSummaryUiState? {
        val strategySignature =
            deriveBypassStrategySignature(
                settings = settings,
                routeGroup =
                    serviceStateStore.telemetry.value.proxyTelemetry.lastRouteGroup
                        ?.toString(),
                modeOverride = activeMode,
            )
        val strategyId = strategySignature.stableId()
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
            // The mode is already the actuator's route label, so the title is only the step chain.
            title = if (strategySummary != null) strategySignature.chainSummary else summary.displayName,
            facets =
                strategySignature
                    .localizedHomeStrategyFacets(stringResolver)
                    .takeIf { strategySummary != null }
                    ?: persistentListOf(),
            verification = stringResolver.getString(summary.verificationLabelRes()),
            successRate =
                summary.validatedSuccessRate?.let { "${(it * PercentScale).toInt()}%" } ?: "Not evaluated",
            supportingText =
                buildString {
                    append(
                        if (summary.currentStrategyAssessment != null) {
                            summary.uiEvidenceNote(stringResolver)
                        } else {
                            summary.lastValidatedResult ?: "No validated diagnostics run yet"
                        },
                    )
                    append(" · ")
                    append("${summary.usageCount} runtime session(s)")
                },
        )
    }

    private fun observeStatus() {
        mutations.launch {
            serviceStateStore.status.collect { (status, mode) ->
                when (status) {
                    AppStatus.Running -> {
                        onConnected()
                        resultFeedback.observedRunning(mode)
                    }

                    AppStatus.Halted -> {
                        onHalted()
                    }

                    // Reconnecting is surfaced as a Connecting actuator purely by
                    // resolveEffectiveConnectionState (single source of truth); no
                    // runtime-state mutation is needed here, and the terminal
                    // Running/Halted edge resolves the state.
                    AppStatus.Reconnecting -> {}
                }
                refreshPermissionSnapshot()
            }
        }
    }

    private fun observeServiceEvents() {
        mutations.launch {
            serviceStateStore.events.collect { event ->
                when (event) {
                    is ServiceEvent.Failed -> {
                        onServiceFailed(event.sender, event.reason)
                    }

                    is ServiceEvent.PermissionRevoked -> {
                        resultFeedback.clear()
                        refreshPermissionSnapshot()
                    }
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
                    connectingTimeoutJob = null
                    showError(stringResolver.getString(R.string.connection_timed_out))
                }
            }
    }

    private fun cancelConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    private fun onConnected() {
        failureFeedbackEpoch++
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
                    connectingStartedAtMs = null,
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
        resultFeedback.clear()
        cancelConnectingTimeout()
        stopConnectionMetricsPolling()
        runtimeState.update { current ->
            if (current.connectionState == ConnectionState.Error) {
                current.copy(
                    connectionStartedAtMs = null,
                    connectingStartedAtMs = null,
                    baselineTransferredBytes = 0L,
                    dataTransferred = 0L,
                    connectionDuration = ZERO,
                )
            } else {
                ConnectionRuntimeState()
            }
        }
    }

    private suspend fun onServiceFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        if (reason == FailureReason.PermissionLost(com.poyka.ripdpi.data.LocalNetworkPermission)) {
            if (showError(stringResolver.getString(R.string.permissions_local_network_needed))) {
                onLocalNetworkRequired(if (sender == Sender.VPN) Mode.VPN else Mode.Proxy)
            }
            return
        }
        val detail = reason.displayMessage
        val message = stringResolver.getString(R.string.failed_to_start, sender.senderName) + ": $detail"
        showError(message)
    }

    private suspend fun showError(message: String): Boolean {
        val epoch = ++failureFeedbackEpoch
        val hapticFeedbackEnabled = !currentSettings().hapticFeedbackDisabled
        resultFeedback.clear()
        val repeatedMessage =
            runtimeState.value.let { it.connectionState == ConnectionState.Error && it.errorMessage == message }
        cancelConnectingTimeout()
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Error,
                errorMessage = message,
                connectingStartedAtMs = null,
                connectionStartedAtMs = null,
                baselineTransferredBytes = 0L,
                dataTransferred = 0L,
                connectionDuration = ZERO,
            )
        }
        val identity = serviceController.failureFeedbackIdentity()
        if (epoch != failureFeedbackEpoch) return false
        val newUserAttempt = identity != null && lastNotifiedFailure != null && identity != lastNotifiedFailure
        if (!repeatedMessage || newUserAttempt) {
            lastNotifiedFailure = identity
            mutations.trySend(MainEffect.ShowError(message, hapticFeedbackEnabled = hapticFeedbackEnabled))
        }
        return true
    }

    private fun setConnectingState() {
        stopConnectionMetricsPolling()
        runtimeState.update {
            it.copy(
                connectionState = ConnectionState.Connecting,
                errorMessage = null,
                connectingStartedAtMs = SystemClock.elapsedRealtime(),
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

private val Mode.startSenderName: String
    get() =
        when (this) {
            Mode.VPN -> Sender.VPN.senderName
            Mode.Proxy -> Sender.Proxy.senderName
        }

internal class ConnectionRuntimeStateReducer(
    private val state: MutableStateFlow<ConnectionRuntimeState>,
) {
    val value: ConnectionRuntimeState
        get() = state.value

    fun update(reducer: (ConnectionRuntimeState) -> ConnectionRuntimeState) {
        state.update(reducer)
    }
}

private data class FailureFeedbackIdentity(
    val authority: PauseAuthorityRef,
    val commandId: String,
)

private suspend fun ServiceController.failureFeedbackIdentity(): FailureFeedbackIdentity? {
    val snapshot =
        try {
            captureRuntimeSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    currentCoroutineContext().ensureActive()
    // Terminated starts retain their command ID, but no longer retain desiredMode.
    return snapshot?.takeIf { it.command?.origin == RuntimeCommandOrigin.UserStart }?.let {
        FailureFeedbackIdentity(it.reference, checkNotNull(it.command).commandId)
    }
}
