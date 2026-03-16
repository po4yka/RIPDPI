package com.poyka.ripdpi.activities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.diagnostics.stableId
import com.poyka.ripdpi.permissions.BatteryOptimizationGuidance
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionItemUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.permissions.BatteryOptimizationIntents
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.FailureReason
import com.poyka.ripdpi.services.displayMessage
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

sealed interface MainEffect {
    data class RequestPermission(
        val kind: PermissionKind,
        val payload: Intent? = null,
    ) : MainEffect

    data class OpenAppSettings(
        val intent: Intent,
    ) : MainEffect

    data object OpenVpnPermissionScreen : MainEffect

    data class ShowError(
        val message: String,
    ) : MainEffect
}

data class MainUiState(
    val appStatus: AppStatus = AppStatus.Halted,
    val activeMode: Mode = Mode.VPN,
    val configuredMode: Mode = Mode.VPN,
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val theme: String = "system",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectionDuration: Duration = ZERO,
    val dataTransferred: Long = 0L,
    val errorMessage: String? = null,
    val permissionSummary: PermissionSummaryUiState = PermissionSummaryUiState(),
    val approachSummary: HomeApproachSummaryUiState? = null,
) {
    val isConnected: Boolean
        get() = connectionState == ConnectionState.Connected

    val isConnecting: Boolean
        get() = connectionState == ConnectionState.Connecting
}

data class HomeApproachSummaryUiState(
    val title: String,
    val verification: String,
    val successRate: String,
    val supportingText: String,
)

private data class ConnectionRuntimeState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val errorMessage: String? = null,
    val connectionStartedAtMs: Long? = null,
    val baselineTransferredBytes: Long = 0L,
    val dataTransferred: Long = 0L,
    val connectionDuration: Duration = ZERO,
)

private data class PermissionRuntimeState(
    val snapshot: PermissionSnapshot = PermissionSnapshot(),
    val issue: PermissionIssueUiState? = null,
)

internal fun calculateTransferredBytes(
    totalBytes: Long,
    baselineBytes: Long,
): Long = (totalBytes - baselineBytes).coerceAtLeast(0L)

internal fun shouldPollConnectionMetrics(connectionState: ConnectionState): Boolean =
    connectionState == ConnectionState.Connected

data class MainStartupState(
    val isReady: Boolean = false,
    val theme: String = "system",
    val startDestination: String = Route.Home.route,
)

internal fun resolveStartupDestination(settings: AppSettings): String =
    when {
        !settings.onboardingComplete -> Route.Onboarding.route
        settings.biometricEnabled -> Route.BiometricPrompt.route
        else -> Route.Home.route
    }

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val appContext: Context,
        appSettingsRepository: AppSettingsRepository,
        private val serviceStateStore: ServiceStateStore,
        private val serviceController: ServiceController,
        private val diagnosticsManager: DiagnosticsManager,
        private val stringResolver: StringResolver,
        private val trafficStatsReader: TrafficStatsReader,
        private val permissionStatusProvider: PermissionStatusProvider,
        private val permissionCoordinator: PermissionCoordinator,
    ) : ViewModel() {
        private val deviceManufacturer = Build.MANUFACTURER.orEmpty()
        private val runtimeState = MutableStateFlow(ConnectionRuntimeState())
        private val permissionState = MutableStateFlow(PermissionRuntimeState())
        private val permissionOverrides = mutableMapOf<PermissionKind, PermissionStatus>()
        private var connectionMetricsJob: Job? = null
        private var pendingPermissionAction: PermissionAction? = null

        private val settingsState: StateFlow<AppSettings> =
            appSettingsRepository.settings.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = AppSettingsSerializer.defaultValue,
            )

        private val _effects = Channel<MainEffect>(Channel.BUFFERED)
        val effects: Flow<MainEffect> = _effects.receiveAsFlow()

        val startupState: StateFlow<MainStartupState> =
            settingsState
                .map { settings ->
                    MainStartupState(
                        isReady = true,
                        theme = settings.appTheme.ifEmpty { "system" },
                        startDestination = resolveStartupDestination(settings),
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = MainStartupState(),
                )

        val uiState: StateFlow<MainUiState> =
            combine(
                settingsState,
                serviceStateStore.status,
                runtimeState,
                permissionState,
                diagnosticsManager.approachStats,
            ) { settings, (status, activeMode), runtime, permissions, approachStats ->
                val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
                MainUiState(
                    appStatus = status,
                    activeMode = activeMode,
                    configuredMode = configuredMode,
                    proxyIp = settings.proxyIp.ifEmpty { "127.0.0.1" },
                    proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080",
                    theme = settings.appTheme.ifEmpty { "system" },
                    connectionState = runtime.connectionState,
                    connectionDuration = runtime.connectionDuration,
                    dataTransferred = runtime.dataTransferred,
                    errorMessage = runtime.errorMessage,
                    permissionSummary =
                        buildPermissionSummary(
                            snapshot = permissions.snapshot,
                            issue = permissions.issue,
                            configuredMode = configuredMode,
                        ),
                    approachSummary =
                        buildApproachSummary(
                            settings = settings,
                            activeMode = if (status == AppStatus.Running) activeMode else configuredMode,
                            approachStats = approachStats,
                        ),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MainUiState(),
            )

        init {
            refreshPermissionSnapshot()
            observeStatus()
            observeServiceEvents()
        }

        fun onPrimaryConnectionAction() {
            when (uiState.value.connectionState) {
                ConnectionState.Connecting -> return
                ConnectionState.Connected -> stop()
                ConnectionState.Disconnected,
                ConnectionState.Error,
                -> {
                    when (uiState.value.appStatus) {
                        AppStatus.Halted -> resolvePermissionAction(PermissionAction.StartConfiguredMode)
                        AppStatus.Running -> stop()
                    }
                }
            }
        }

        fun onVpnPermissionContinueRequested() {
            resolvePermissionAction(PermissionAction.StartVpnMode)
        }

        fun onOpenVpnPermissionRequested() {
            resolvePermissionAction(PermissionAction.OpenVpnPermissionScreen)
        }

        fun onRepairPermissionRequested(kind: PermissionKind) {
            resolvePermissionAction(PermissionAction.RepairPermission(kind))
        }

        fun onPermissionResult(
            kind: PermissionKind,
            result: PermissionResult,
        ) {
            when (kind) {
                PermissionKind.Notifications -> handleNotificationPermissionResult(result)
                PermissionKind.VpnConsent -> handleVpnPermissionResult(result)
                PermissionKind.BatteryOptimization -> handleBatteryOptimizationResult()
            }
        }

        fun refreshPermissionSnapshot() {
            val mergedSnapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
            permissionState.update { current ->
                val clearedIssue =
                    if (current.issue?.kind?.let { mergedSnapshot.statusFor(it) == PermissionStatus.Granted } == true) {
                        null
                    } else {
                        current.issue
                    }
                current.copy(snapshot = mergedSnapshot, issue = clearedIssue)
            }
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

        private fun resolvePermissionAction(action: PermissionAction) {
            if (action is PermissionAction.OpenVpnPermissionScreen) {
                permissionState.update { current -> current.copy(issue = null) }
                _effects.trySend(MainEffect.OpenVpnPermissionScreen)
                return
            }

            if (
                (action is PermissionAction.StartConfiguredMode || action is PermissionAction.StartVpnMode) &&
                uiState.value.connectionState == ConnectionState.Connecting
            ) {
                return
            }

            dismissError()
            val mergedSnapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
            permissionState.update { it.copy(snapshot = mergedSnapshot) }
            val resolution =
                permissionCoordinator.resolve(
                    action = action,
                    configuredMode = uiState.value.configuredMode,
                    snapshot = mergedSnapshot,
                )
            pendingPermissionAction = action
            val blockedBy = resolution.blockedBy
            if (blockedBy == null) {
                permissionState.update { it.copy(issue = null) }
                continueResolvedAction(action, resolution.recommended)
                return
            }

            requestPermissionFor(action = action, blockedBy = blockedBy, snapshot = mergedSnapshot)
        }

        private fun requestPermissionFor(
            action: PermissionAction,
            blockedBy: PermissionKind,
            snapshot: PermissionSnapshot,
        ) {
            when (blockedBy) {
                PermissionKind.Notifications -> {
                    when (snapshot.notifications) {
                        PermissionStatus.RequiresSettings -> {
                            val issue =
                                createPermissionIssue(
                                    kind = PermissionKind.Notifications,
                                    status = PermissionStatus.RequiresSettings,
                                    blocking = true,
                                )
                            permissionState.update { it.copy(issue = issue, snapshot = snapshot) }
                            _effects.trySend(MainEffect.OpenAppSettings(createAppSettingsIntent()))
                        }

                        PermissionStatus.Denied,
                        PermissionStatus.RequiresSystemPrompt,
                        -> {
                            permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                            _effects.trySend(MainEffect.RequestPermission(kind = PermissionKind.Notifications))
                        }

                        PermissionStatus.Granted,
                        PermissionStatus.NotApplicable,
                        -> continueResolvedAction(action, emptyList())
                    }
                }

                PermissionKind.VpnConsent -> {
                    when (action) {
                        PermissionAction.StartConfiguredMode,
                        PermissionAction.OpenVpnPermissionScreen,
                        -> _effects.trySend(MainEffect.OpenVpnPermissionScreen)

                        PermissionAction.StartVpnMode,
                        is PermissionAction.RepairPermission,
                        -> {
                            val prepareIntent = android.net.VpnService.prepare(appContext)
                            if (prepareIntent == null) {
                                onPermissionResult(PermissionKind.VpnConsent, PermissionResult.Granted)
                            } else {
                                permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                                _effects.trySend(
                                    MainEffect.RequestPermission(
                                        kind = PermissionKind.VpnConsent,
                                        payload = prepareIntent,
                                    ),
                                )
                            }
                        }
                    }
                }

                PermissionKind.BatteryOptimization -> {
                    permissionState.update { it.copy(issue = null, snapshot = snapshot) }
                    _effects.trySend(
                        MainEffect.RequestPermission(
                            kind = PermissionKind.BatteryOptimization,
                            payload = createBatteryOptimizationIntent(),
                        ),
                    )
                }
            }
        }

        private fun handleNotificationPermissionResult(result: PermissionResult) {
            when (result) {
                PermissionResult.Granted -> {
                    permissionOverrides.remove(PermissionKind.Notifications)
                    refreshPermissionSnapshot()
                    resumePendingAction()
                }

                PermissionResult.Denied -> {
                    permissionOverrides[PermissionKind.Notifications] = PermissionStatus.Denied
                    pendingPermissionAction = null
                    refreshPermissionSnapshot()
                    showPermissionIssue(
                        createPermissionIssue(
                            kind = PermissionKind.Notifications,
                            status = PermissionStatus.Denied,
                            blocking = true,
                        ),
                    )
                }

                PermissionResult.DeniedPermanently -> {
                    permissionOverrides[PermissionKind.Notifications] = PermissionStatus.RequiresSettings
                    pendingPermissionAction = null
                    refreshPermissionSnapshot()
                    showPermissionIssue(
                        createPermissionIssue(
                            kind = PermissionKind.Notifications,
                            status = PermissionStatus.RequiresSettings,
                            blocking = true,
                        ),
                    )
                }

                PermissionResult.ReturnedFromSettings -> {
                    refreshPermissionSnapshot()
                    resumePendingAction()
                }
            }
        }

        private fun handleVpnPermissionResult(result: PermissionResult) {
            when (result) {
                PermissionResult.Granted -> {
                    permissionOverrides.remove(PermissionKind.VpnConsent)
                    refreshPermissionSnapshot()
                    resumePendingAction()
                }

                PermissionResult.Denied,
                PermissionResult.DeniedPermanently,
                -> {
                    permissionOverrides[PermissionKind.VpnConsent] = PermissionStatus.Denied
                    pendingPermissionAction = null
                    refreshPermissionSnapshot()
                    showPermissionIssue(
                        createPermissionIssue(
                            kind = PermissionKind.VpnConsent,
                            status = PermissionStatus.Denied,
                            blocking = true,
                        ),
                    )
                }

                PermissionResult.ReturnedFromSettings -> {
                    refreshPermissionSnapshot()
                }
            }
        }

        private fun handleBatteryOptimizationResult() {
            permissionOverrides.remove(PermissionKind.BatteryOptimization)
            refreshPermissionSnapshot()
            if (permissionState.value.snapshot.batteryOptimization == PermissionStatus.Granted) {
                resumePendingAction()
            } else if (
                pendingPermissionAction ==
                PermissionAction.RepairPermission(PermissionKind.BatteryOptimization)
            ) {
                pendingPermissionAction = null
            }
        }

        private fun resumePendingAction() {
            val action = pendingPermissionAction ?: return
            val snapshot = mergeSnapshotWithOverrides(permissionStatusProvider.currentSnapshot())
            permissionState.update { it.copy(snapshot = snapshot) }
            val resolution =
                permissionCoordinator.resolve(
                    action = action,
                    configuredMode = uiState.value.configuredMode,
                    snapshot = snapshot,
                )
            if (resolution.blockedBy == null) {
                permissionState.update { it.copy(issue = null) }
                continueResolvedAction(action, resolution.recommended)
            } else {
                requestPermissionFor(action = action, blockedBy = resolution.blockedBy, snapshot = snapshot)
            }
        }

        private fun continueResolvedAction(
            action: PermissionAction,
            recommended: List<PermissionKind>,
        ) {
            pendingPermissionAction = null
            when (action) {
                PermissionAction.StartConfiguredMode -> startMode(uiState.value.configuredMode)
                PermissionAction.StartVpnMode -> startMode(Mode.VPN)
                PermissionAction.OpenVpnPermissionScreen -> _effects.trySend(MainEffect.OpenVpnPermissionScreen)
                is PermissionAction.RepairPermission -> {
                    if (action.kind == PermissionKind.BatteryOptimization && recommended.isEmpty()) {
                        refreshPermissionSnapshot()
                    }
                }
            }
        }

        private fun startMode(mode: Mode) {
            setConnectingState()
            serviceController.start(mode)
        }

        private fun stop() {
            serviceController.stop()
        }

        private fun observeStatus() {
            viewModelScope.launch {
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
            viewModelScope.launch {
                serviceStateStore.events.collect { event ->
                    when (event) {
                        is ServiceEvent.Failed -> onServiceFailed(event.sender, event.reason)
                    }
                }
            }
        }

        private fun onConnected() {
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

        private fun onServiceFailed(sender: Sender, reason: FailureReason) {
            val detail = reason.displayMessage
            val message = stringResolver.getString(R.string.failed_to_start, sender.senderName) + ": $detail"
            showError(message)
        }

        private fun showError(message: String) {
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
            _effects.trySend(MainEffect.ShowError(message))
        }

        private fun showPermissionIssue(issue: PermissionIssueUiState) {
            permissionState.update { it.copy(issue = issue) }
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
            _effects.trySend(MainEffect.ShowError(issue.message))
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
                viewModelScope.launch {
                    while (isActive) {
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
            val startedAtMs = state.connectionStartedAtMs ?: return
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

        private fun mergeSnapshotWithOverrides(providerSnapshot: PermissionSnapshot): PermissionSnapshot {
            val notificationsStatus =
                when {
                    providerSnapshot.notifications == PermissionStatus.Granted -> {
                        permissionOverrides.remove(PermissionKind.Notifications)
                        PermissionStatus.Granted
                    }
                    else -> permissionOverrides[PermissionKind.Notifications] ?: providerSnapshot.notifications
                }
            val vpnStatus =
                when {
                    providerSnapshot.vpnConsent == PermissionStatus.Granted -> {
                        permissionOverrides.remove(PermissionKind.VpnConsent)
                        PermissionStatus.Granted
                    }
                    else -> permissionOverrides[PermissionKind.VpnConsent] ?: providerSnapshot.vpnConsent
                }

            return providerSnapshot.copy(
                notifications = notificationsStatus,
                vpnConsent = vpnStatus,
            )
        }

        private fun buildPermissionSummary(
            snapshot: PermissionSnapshot,
            issue: PermissionIssueUiState?,
            configuredMode: Mode,
        ): PermissionSummaryUiState {
            val recommendedIssue =
                if (
                    issue == null &&
                    snapshot.batteryOptimization != PermissionStatus.Granted &&
                    snapshot.batteryOptimization != PermissionStatus.NotApplicable
                ) {
                    createPermissionIssue(
                        kind = PermissionKind.BatteryOptimization,
                        status = snapshot.batteryOptimization,
                        blocking = false,
                    )
                } else {
                    null
                }

            return PermissionSummaryUiState(
                snapshot = snapshot,
                issue = issue,
                recommendedIssue = recommendedIssue,
                items =
                    listOf(
                        buildNotificationPermissionItem(snapshot.notifications),
                        buildVpnPermissionItem(snapshot.vpnConsent, configuredMode),
                        buildBatteryPermissionItem(snapshot.batteryOptimization),
                ),
            )
        }

        private fun buildApproachSummary(
            settings: AppSettings,
            activeMode: Mode,
            approachStats: List<BypassApproachSummary>,
        ): HomeApproachSummaryUiState? {
            val strategyId =
                deriveBypassStrategySignature(
                    settings = settings,
                    routeGroup = serviceStateStore.telemetry.value.proxyTelemetry.lastRouteGroup?.toString(),
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
                successRate = summary.validatedSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "Unverified",
                supportingText =
                    buildString {
                        append(summary.lastValidatedResult ?: "No validated diagnostics run yet")
                        append(" · ")
                        append("${summary.usageCount} runtime session(s)")
                    },
            )
        }

        private fun buildNotificationPermissionItem(status: PermissionStatus): PermissionItemUiState =
            when (status) {
                PermissionStatus.Granted,
                PermissionStatus.NotApplicable,
                -> PermissionItemUiState(
                    kind = PermissionKind.Notifications,
                    title = stringResolver.getString(R.string.permissions_notifications_title),
                    subtitle = stringResolver.getString(R.string.settings_permissions_notifications_ready),
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_granted),
                )

                PermissionStatus.RequiresSettings -> PermissionItemUiState(
                    kind = PermissionKind.Notifications,
                    title = stringResolver.getString(R.string.permissions_notifications_title),
                    subtitle = stringResolver.getString(R.string.settings_permissions_notifications_needed),
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_required),
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_open_settings),
                )

                PermissionStatus.Denied,
                PermissionStatus.RequiresSystemPrompt,
                -> PermissionItemUiState(
                    kind = PermissionKind.Notifications,
                    title = stringResolver.getString(R.string.permissions_notifications_title),
                    subtitle = stringResolver.getString(R.string.settings_permissions_notifications_needed),
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_required),
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_allow),
                )
            }

        private fun buildVpnPermissionItem(
            status: PermissionStatus,
            configuredMode: Mode,
        ): PermissionItemUiState =
            when (status) {
                PermissionStatus.Granted -> PermissionItemUiState(
                    kind = PermissionKind.VpnConsent,
                    title = stringResolver.getString(R.string.permissions_vpn_title),
                    subtitle =
                        if (configuredMode == Mode.VPN) {
                            stringResolver.getString(R.string.settings_permissions_vpn_active)
                        } else {
                            stringResolver.getString(R.string.settings_permissions_vpn_optional)
                        },
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_granted),
                )

                PermissionStatus.NotApplicable -> PermissionItemUiState(
                    kind = PermissionKind.VpnConsent,
                    title = stringResolver.getString(R.string.permissions_vpn_title),
                    subtitle = stringResolver.getString(R.string.settings_permissions_vpn_optional),
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_not_needed),
                )

                PermissionStatus.Denied,
                PermissionStatus.RequiresSettings,
                PermissionStatus.RequiresSystemPrompt,
                -> PermissionItemUiState(
                    kind = PermissionKind.VpnConsent,
                    title = stringResolver.getString(R.string.permissions_vpn_title),
                    subtitle =
                        if (configuredMode == Mode.VPN) {
                            stringResolver.getString(R.string.settings_permissions_vpn_needed)
                        } else {
                            stringResolver.getString(R.string.settings_permissions_vpn_optional)
                        },
                    statusLabel =
                        if (configuredMode == Mode.VPN) {
                            stringResolver.getString(R.string.settings_permission_status_required)
                        } else {
                            stringResolver.getString(R.string.settings_permission_status_optional)
                        },
                    actionLabel = stringResolver.getString(R.string.permissions_vpn_continue),
                )
            }

        private fun buildBatteryPermissionItem(status: PermissionStatus): PermissionItemUiState =
            when (status) {
                PermissionStatus.Granted,
                PermissionStatus.NotApplicable,
                -> PermissionItemUiState(
                    kind = PermissionKind.BatteryOptimization,
                    title = stringResolver.getString(R.string.permissions_battery_title),
                    subtitle =
                        stringResolver.getString(
                            if (status == PermissionStatus.Granted) {
                                BatteryOptimizationGuidance.readySubtitleRes(deviceManufacturer)
                            } else {
                                R.string.settings_permissions_battery_ready
                            },
                        ),
                    statusLabel =
                        if (status == PermissionStatus.NotApplicable) {
                            stringResolver.getString(R.string.settings_permission_status_not_needed)
                        } else {
                            stringResolver.getString(R.string.settings_permission_status_granted)
                        },
                )

                PermissionStatus.Denied,
                PermissionStatus.RequiresSystemPrompt,
                PermissionStatus.RequiresSettings,
                -> PermissionItemUiState(
                    kind = PermissionKind.BatteryOptimization,
                    title = stringResolver.getString(R.string.permissions_battery_title),
                    subtitle =
                        stringResolver.getString(
                            BatteryOptimizationGuidance.recommendedSubtitleRes(deviceManufacturer),
                        ),
                    statusLabel = stringResolver.getString(R.string.settings_permission_status_recommended),
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_review),
                )
            }

        private fun createPermissionIssue(
            kind: PermissionKind,
            status: PermissionStatus,
            blocking: Boolean,
        ): PermissionIssueUiState =
            when (kind) {
                PermissionKind.Notifications ->
                    if (status == PermissionStatus.RequiresSettings) {
                        PermissionIssueUiState(
                            kind = kind,
                            title = stringResolver.getString(R.string.permissions_notifications_title),
                            message = stringResolver.getString(R.string.permissions_notifications_open_settings),
                            recovery = PermissionRecovery.OpenSettings,
                            actionLabel = stringResolver.getString(R.string.settings_permission_action_open_settings),
                            blocking = blocking,
                        )
                    } else {
                        PermissionIssueUiState(
                            kind = kind,
                            title = stringResolver.getString(R.string.permissions_notifications_title),
                            message = stringResolver.getString(R.string.permissions_notifications_denied),
                            recovery = PermissionRecovery.RetryPrompt,
                            actionLabel = stringResolver.getString(R.string.settings_permission_action_allow),
                            blocking = blocking,
                        )
                    }

                PermissionKind.VpnConsent -> PermissionIssueUiState(
                    kind = kind,
                    title = stringResolver.getString(R.string.permissions_vpn_error_title),
                    message = stringResolver.getString(R.string.permissions_vpn_error_body),
                    recovery = PermissionRecovery.OpenVpnPermissionScreen,
                    actionLabel = stringResolver.getString(R.string.permissions_vpn_continue),
                    blocking = blocking,
                )

                PermissionKind.BatteryOptimization -> PermissionIssueUiState(
                    kind = kind,
                    title = stringResolver.getString(R.string.permissions_battery_title),
                    message =
                        stringResolver.getString(
                            BatteryOptimizationGuidance.issueMessageRes(deviceManufacturer),
                        ),
                    recovery = PermissionRecovery.OpenBatteryOptimizationSettings,
                    actionLabel = stringResolver.getString(R.string.settings_permission_action_review),
                    blocking = blocking,
                )
            }

        private fun createAppSettingsIntent(): Intent =
            BatteryOptimizationIntents
                .createAppDetailsIntent(appContext.packageName)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        private fun createBatteryOptimizationIntent(): Intent =
            BatteryOptimizationIntents.create(packageName = appContext.packageName) { intent ->
                intent.resolveActivity(appContext.packageManager) != null
            }
    }
