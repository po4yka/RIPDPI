package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import com.poyka.ripdpi.failover.ActiveTransportProvider
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val mainServiceDependencies: MainServiceDependencies,
        private val mainPermissionDependencies: MainPermissionDependencies,
        private val mainDiagnosticsDependencies: MainDiagnosticsDependencies,
        private val mainControlPlaneDependencies: MainControlPlaneDependencies,
        private val mainLifecycleDependencies: MainLifecycleDependencies,
        private val stringResolver: StringResolver,
        private val activeTransportProvider: Optional<ActiveTransportProvider>,
    ) : ViewModel() {
        private var initialized = false
        private val runtimeState = MutableStateFlow(ConnectionRuntimeState())
        private val permissionState = MutableStateFlow(PermissionRuntimeState())
        private val _effects =
            MutableSharedFlow<MainEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val effects = _effects.bufferForUiLifecycle(viewModelScope)

        private val strategyConfigActions =
            MainStrategyConfigApplyActions(
                scope = viewModelScope,
                serviceStateStore = mainServiceDependencies.serviceStateStore,
                serviceController = mainServiceDependencies.serviceController,
            )

        private val settingsState: StateFlow<AppSettings> =
            appSettingsRepository.settings.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
            )

        private val mutations =
            MainMutationRunner(
                scope = viewModelScope,
                effects = _effects,
                currentUiState = { uiState.value },
            )
        private val lifecycleOwner =
            MainLifecycleStateOwner(
                scope = viewModelScope,
                settings = appSettingsRepository.settings,
                settingsState = settingsState,
                permissionState = permissionState,
                dependencies = mainLifecycleDependencies,
                effects = _effects,
            )
        val pendingCrashReport = lifecycleOwner.pendingCrashReport
        val crashReports = lifecycleOwner.crashReports
        val appLock = lifecycleOwner.appLock

        private val connectionActions: MainConnectionActions by lazy {
            MainConnectionActions(
                mutations = mutations,
                serviceController = mainServiceDependencies.serviceController,
                serviceStateStore = mainServiceDependencies.serviceStateStore,
                trafficStatsReader = mainServiceDependencies.trafficStatsReader,
                stringResolver = stringResolver,
                runtimeState = ConnectionRuntimeStateReducer(runtimeState),
                refreshPermissionSnapshot = { permissionActions.refreshPermissionSnapshot() },
            )
        }

        private val permissionActions: MainPermissionActions by lazy {
            MainPermissionActions(
                mutations = mutations,
                permissionCoordinator = mainPermissionDependencies.permissionCoordinator,
                permissionStatusProvider = mainPermissionDependencies.permissionStatusProvider,
                permissionPlatformBridge = mainPermissionDependencies.permissionPlatformBridge,
                stringResolver = stringResolver,
                permissionState = permissionState,
                onStartMode = { mode -> connectionActions.startMode(mode) },
                onRunHomeAnalysis = { homeDiagnostics.actions.runFullAnalysis() },
                onShowPermissionIssue = { issue ->
                    permissionState.update { it.copy(issue = issue) }
                    connectionActions.showPermissionIssue(issue)
                },
                onDismissError = { connectionActions.dismissError() },
            )
        }

        private val homeDiagnostics: HomeDiagnosticsStateOwner by lazy {
            HomeDiagnosticsStateOwner(
                scope = viewModelScope,
                settingsState = settingsState,
                serviceStateStore = mainServiceDependencies.serviceStateStore,
                connectionRuntimeState = runtimeState,
                permissionState = permissionState,
                mutations = mutations,
                diagnosticsDependencies = mainDiagnosticsDependencies,
                stringResolver = stringResolver,
                requestVpnStart = {
                    permissionActions.resolvePermissionAction(PermissionAction.StartVpnMode)
                },
            )
        }

        val startupState: StateFlow<MainStartupState> = lifecycleOwner.startupState
        val homeDiagnosticsUiState: StateFlow<HomeDiagnosticsUiState> = homeDiagnostics.uiState
        val homeDiagnosticCard: StateFlow<HomeModeCardUiState> = homeDiagnostics.diagnosticCard
        val subscriptionExpiryUiState: StateFlow<SubscriptionExpirySummaryUiState> =
            combine(
                mainControlPlaneDependencies.proxyGroupRepository.groups(),
                mainControlPlaneDependencies.subscriptionExpiryClock.ticks(),
            ) { groups, nowMillis ->
                subscriptionExpiryUiState(groups, nowMillis)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SubscriptionExpirySummaryUiState(),
            )

        /** Privacy-safe details for the active Simple transport, or `null` when unavailable. */
        val activeTransportDescriptor =
            (
                activeTransportProvider.orElse(null)?.activeTransport
                    ?: flowOf(null)
            ).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )
        val uiState: StateFlow<MainUiState> =
            MainUiStateOwner(
                scope = viewModelScope,
                settingsState = settingsState,
                serviceDependencies = mainServiceDependencies,
                diagnosticsDependencies = mainDiagnosticsDependencies,
                controlPlaneDependencies = mainControlPlaneDependencies,
                runtimeState = runtimeState,
                permissionState = permissionState,
                stringResolver = stringResolver,
                buildApproachSummary = connectionActions::buildApproachSummary,
            ).uiState

        fun initialize() {
            if (startupState.value.readiness != AppStartupReadinessState.Ready || initialized) {
                return
            }
            initialized = true
            viewModelScope.launch {
                mainServiceDependencies.hardKillSwitchStateStore.snapshot.collect {
                    permissionActions.refreshPermissionSnapshot()
                }
            }
            connectionActions.initialize()
            homeDiagnostics.initialize()
            viewModelScope.launch {
                mainControlPlaneDependencies.hostPackCatalogUiStateCoordinator.ensureLoaded()
            }
            lifecycleOwner.initialize()
        }

        val retryStartupRecovery: () -> Unit = lifecycleOwner::retryStartupRecovery

        fun onPrimaryConnectionAction() {
            when (
                resolvePrimaryConnectionAction(
                    connectionState = uiState.value.connectionState,
                    appStatus = uiState.value.appStatus,
                )
            ) {
                MainPrimaryConnectionAction.NONE -> {
                    return
                }

                MainPrimaryConnectionAction.START_CONFIGURED_MODE -> {
                    permissionActions.resolvePermissionAction(PermissionAction.StartConfiguredMode)
                }

                MainPrimaryConnectionAction.STOP -> {
                    connectionActions.stop()
                }
            }
        }

        fun onStopRequested() {
            // Stop on any non-Halted status so an internal request issued during the brief
            // Reconnecting window is not silently dropped. The service owns the authoritative
            // Android-lockdown check because its cached UI snapshot can be stale.
            val status = mainServiceDependencies.serviceStateStore.status.value.first
            if (status != AppStatus.Halted) {
                connectionActions.stop()
            }
        }

        fun onToggleLocalBypass(enabled: Boolean) {
            val state = uiState.value
            if (enabled) {
                permissionActions.resolvePermissionAction(PermissionAction.StartProxyMode)
            } else if (shouldStopLocalBypassToggle(state)) {
                connectionActions.stop()
            }
        }

        fun onToggleVpn(enabled: Boolean) {
            if (enabled) {
                permissionActions.resolvePermissionAction(PermissionAction.StartVpnMode)
            } else if (uiState.value.appStatus == AppStatus.Running && uiState.value.activeMode == Mode.VPN) {
                connectionActions.stop()
            }
        }

        fun onVpnPermissionContinueRequested() = permissionActions.onVpnPermissionContinueRequested()

        fun onOpenVpnPermissionRequested() = permissionActions.onOpenVpnPermissionRequested()

        fun onRepairPermissionRequested(kind: PermissionKind) = permissionActions.onRepairPermissionRequested(kind)

        fun onPermissionResult(
            kind: PermissionKind,
            result: PermissionResult,
        ) = permissionActions.onPermissionResult(kind, result)

        fun onForeground() {
            if (startupState.value.readiness != AppStartupReadinessState.Ready) return
            mainServiceDependencies.serviceController.refreshHardKillSwitchState()
            permissionActions.refreshPermissionSnapshot()
        }

        fun dismissError() = connectionActions.dismissError()

        fun onDismissBatteryBanner() {
            mainLifecycleDependencies.settingsDismissCoordinator.dismissBatteryBanner(viewModelScope)
        }

        fun onDismissBackgroundGuidance() {
            mainLifecycleDependencies.settingsDismissCoordinator.dismissBackgroundGuidance(viewModelScope)
        }

        fun onRunHomeFullAnalysis() = permissionActions.resolvePermissionAction(PermissionAction.RunHomeAnalysis)

        fun onCancelHomeAnalysis() = homeDiagnostics.actions.cancelAnalysis()

        fun onRunHomeQuickAnalysis() = homeDiagnostics.actions.runQuickAnalysis()

        fun onStartVerifiedVpn() = homeDiagnostics.actions.startVerifiedVpn()

        fun onToggleHomePcapRecording() = homeDiagnostics.actions.togglePcapRecording()

        internal fun reportSupportError(
            message: String,
            supportCode: String,
        ) {
            mutations.trySend(MainEffect.ShowError(message = message, supportCode = supportCode))
        }

        fun applySavedStrategyConfig(): StrategyConfigApplyResult = strategyConfigActions.applySavedStrategyConfig()

        val onShareHomeAnalysis: () -> Unit = {
            homeDiagnostics.actions.shareLatestHomeAnalysis()
        }

        val onSaveHomeAnalysis: () -> Unit = {
            homeDiagnostics.actions.saveLatestHomeAnalysis()
        }

        val dismissHomeAnalysisSheet: () -> Unit = {
            homeDiagnostics.actions.dismissAnalysisSheet()
        }

        val dismissHomeVerificationSheet: () -> Unit = {
            homeDiagnostics.actions.dismissVerificationSheet()
        }
    }
