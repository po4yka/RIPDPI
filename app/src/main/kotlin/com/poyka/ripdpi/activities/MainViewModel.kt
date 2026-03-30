package com.poyka.ripdpi.activities

import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionCoordinator
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

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

    data object ShowVpnPermissionDialog : MainEffect

    data class ShowError(
        val message: String,
    ) : MainEffect

    data class ShareDiagnosticsArchive(
        val absolutePath: String,
        val fileName: String,
    ) : MainEffect
}

@Immutable
data class HomeDiagnosticsActionUiState(
    val label: String = "",
    val supportingText: String = "",
    val enabled: Boolean = false,
    val busy: Boolean = false,
)

@Immutable
data class HomeDiagnosticsLatestAuditUiState(
    val headline: String,
    val summary: String,
    val recommendationSummary: String? = null,
    val stageCountSummary: String? = null,
    val stale: Boolean = false,
    val actionable: Boolean = false,
)

@Immutable
data class HomeDiagnosticsStageUiState(
    val label: String,
    val headline: String,
    val summary: String,
    val failed: Boolean = false,
    val recommendationContributor: Boolean = false,
)

@Immutable
data class HomeDiagnosticsAnalysisSheetUiState(
    val runId: String,
    val headline: String,
    val summary: String,
    val confidenceSummary: String? = null,
    val coverageSummary: String? = null,
    val recommendationSummary: String? = null,
    val appliedSettings: List<DiagnosticsAppliedSetting> = emptyList(),
    val stageSummaries: List<HomeDiagnosticsStageUiState> = emptyList(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
)

@Immutable
data class HomeDiagnosticsVerificationSheetUiState(
    val sessionId: String,
    val success: Boolean,
    val headline: String,
    val summary: String,
    val detail: String? = null,
)

@Immutable
data class HomeDiagnosticsUiState(
    val analysisAction: HomeDiagnosticsActionUiState = HomeDiagnosticsActionUiState(),
    val verifiedVpnAction: HomeDiagnosticsActionUiState = HomeDiagnosticsActionUiState(),
    val latestAudit: HomeDiagnosticsLatestAuditUiState? = null,
    val analysisSheet: HomeDiagnosticsAnalysisSheetUiState? = null,
    val verificationSheet: HomeDiagnosticsVerificationSheetUiState? = null,
)

@Stable
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
    val homeDiagnostics: HomeDiagnosticsUiState = HomeDiagnosticsUiState(),
) {
    val isConnected: Boolean
        get() = connectionState == ConnectionState.Connected

    val isConnecting: Boolean
        get() = connectionState == ConnectionState.Connecting
}

@Immutable
data class HomeApproachSummaryUiState(
    val title: String,
    val verification: String,
    val successRate: String,
    val supportingText: String,
)

internal data class ConnectionRuntimeState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val errorMessage: String? = null,
    val connectionStartedAtMs: Long? = null,
    val baselineTransferredBytes: Long = 0L,
    val dataTransferred: Long = 0L,
    val connectionDuration: Duration = ZERO,
)

internal data class PermissionRuntimeState(
    val snapshot: com.poyka.ripdpi.permissions.PermissionSnapshot =
        com.poyka.ripdpi.permissions
            .PermissionSnapshot(),
    val issue: PermissionIssueUiState? = null,
)

private data class MainUiInputs(
    val settings: AppSettings,
    val statusAndMode: Pair<AppStatus, Mode>,
    val runtime: ConnectionRuntimeState,
    val permissions: PermissionRuntimeState,
    val approachStats: List<com.poyka.ripdpi.diagnostics.BypassApproachSummary>,
)

internal fun calculateTransferredBytes(
    totalBytes: Long,
    baselineBytes: Long,
): Long = (totalBytes - baselineBytes).coerceAtLeast(0L)

internal fun shouldPollConnectionMetrics(connectionState: ConnectionState): Boolean =
    connectionState == ConnectionState.Connected

@Immutable
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
        private val appSettingsRepository: AppSettingsRepository,
        serviceStateStore: ServiceStateStore,
        serviceController: ServiceController,
        diagnosticsTimelineSource: DiagnosticsTimelineSource,
        diagnosticsScanController: DiagnosticsScanController,
        diagnosticsShareService: DiagnosticsShareService,
        diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
        diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService,
        private val stringResolver: StringResolver,
        trafficStatsReader: TrafficStatsReader,
        permissionPlatformBridge: PermissionPlatformBridge,
        permissionStatusProvider: PermissionStatusProvider,
        permissionCoordinator: PermissionCoordinator,
        private val crashReportReader: CrashReportReader,
    ) : ViewModel() {
        private var initialized = false
        private val runtimeState = MutableStateFlow(ConnectionRuntimeState())
        private val permissionState = MutableStateFlow(PermissionRuntimeState())
        private val homeDiagnosticsState = MutableStateFlow(HomeDiagnosticsRuntimeState())
        private val _effects = Channel<MainEffect>(Channel.BUFFERED)

        val effects: Flow<MainEffect> = _effects.receiveAsFlow()

        private val _pendingCrashReport = MutableStateFlow<CrashReport?>(null)
        val pendingCrashReport: StateFlow<CrashReport?> = _pendingCrashReport.asStateFlow()

        private val settingsState: StateFlow<AppSettings> =
            appSettingsRepository.settings.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
            )

        private val mutations =
            MainMutationRunner(
                scope = viewModelScope,
                effects = _effects,
                currentUiState = { uiState.value },
            )

        private val connectionActions: MainConnectionActions by lazy {
            MainConnectionActions(
                mutations = mutations,
                serviceController = serviceController,
                serviceStateStore = serviceStateStore,
                trafficStatsReader = trafficStatsReader,
                stringResolver = stringResolver,
                runtimeState = runtimeState,
                refreshPermissionSnapshot = { permissionActions.refreshPermissionSnapshot() },
            )
        }

        private val permissionActions: MainPermissionActions by lazy {
            MainPermissionActions(
                mutations = mutations,
                permissionCoordinator = permissionCoordinator,
                permissionStatusProvider = permissionStatusProvider,
                permissionPlatformBridge = permissionPlatformBridge,
                stringResolver = stringResolver,
                permissionState = permissionState,
                onStartMode = { mode -> connectionActions.startMode(mode) },
                onShowPermissionIssue = { issue ->
                    permissionState.update { it.copy(issue = issue) }
                    connectionActions.showPermissionIssue(issue)
                },
                onDismissError = { connectionActions.dismissError() },
            )
        }

        private val homeDiagnosticsActions: MainHomeDiagnosticsActions by lazy {
            MainHomeDiagnosticsActions(
                mutations = mutations,
                diagnosticsTimelineSource = diagnosticsTimelineSource,
                diagnosticsScanController = diagnosticsScanController,
                diagnosticsShareService = diagnosticsShareService,
                diagnosticsHomeWorkflowService = diagnosticsHomeWorkflowService,
                diagnosticsHomeCompositeRunService = diagnosticsHomeCompositeRunService,
                serviceStateStore = serviceStateStore,
                runtimeState = runtimeState,
                permissionState = permissionState,
                homeDiagnosticsState = homeDiagnosticsState,
                stringResolver = stringResolver,
                requestVpnStart = {
                    permissionActions.resolvePermissionAction(PermissionAction.StartVpnMode)
                },
            )
        }

        val startupState: StateFlow<MainStartupState> =
            appSettingsRepository.settings
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
                diagnosticsTimelineSource.approachStats,
            ) { settings, statusAndMode, runtime, permissions, approachStats ->
                MainUiInputs(
                    settings = settings,
                    statusAndMode = statusAndMode,
                    runtime = runtime,
                    permissions = permissions,
                    approachStats = approachStats,
                )
            }.combine(homeDiagnosticsState) { inputs, homeDiagnostics ->
                val settings = inputs.settings
                val (status, activeMode) = inputs.statusAndMode
                val runtime = inputs.runtime
                val permissions = inputs.permissions
                val approachStats = inputs.approachStats
                val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
                val permissionSummary =
                    buildPermissionSummary(
                        snapshot = permissions.snapshot,
                        issue = permissions.issue,
                        configuredMode = configuredMode,
                        stringResolver = stringResolver,
                        deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                        batteryBannerDismissed = settings.batteryBannerDismissed,
                        backgroundGuidanceDismissed = settings.backgroundGuidanceDismissed,
                    )
                val effectiveConnectionState =
                    when {
                        status == AppStatus.Halted && runtime.connectionState == ConnectionState.Connected -> {
                            ConnectionState.Disconnected
                        }

                        status == AppStatus.Running && runtime.connectionState == ConnectionState.Disconnected -> {
                            ConnectionState.Connecting
                        }

                        else -> {
                            runtime.connectionState
                        }
                    }
                MainUiState(
                    appStatus = status,
                    activeMode = activeMode,
                    configuredMode = configuredMode,
                    proxyIp = settings.proxyIp.ifEmpty { "127.0.0.1" },
                    proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else "1080",
                    theme = settings.appTheme.ifEmpty { "system" },
                    connectionState = effectiveConnectionState,
                    connectionDuration = runtime.connectionDuration,
                    dataTransferred = runtime.dataTransferred,
                    errorMessage = runtime.errorMessage,
                    permissionSummary = permissionSummary,
                    approachSummary =
                        connectionActions.buildApproachSummary(
                            settings = settings,
                            activeMode = if (status == AppStatus.Running) activeMode else configuredMode,
                            approachStats = approachStats,
                        ),
                    homeDiagnostics =
                        buildHomeDiagnosticsUiState(
                            settings = settings,
                            appStatus = status,
                            connectionState = effectiveConnectionState,
                            permissionSummary = permissionSummary,
                            runtime = homeDiagnostics,
                            stringResolver = stringResolver,
                        ),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MainUiState(),
            )

        fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            permissionActions.refreshPermissionSnapshot()
            connectionActions.initialize()
            homeDiagnosticsActions.initialize()
            viewModelScope.launch {
                val report = crashReportReader.read()
                if (report != null) {
                    _pendingCrashReport.value = report
                }
            }
            viewModelScope.launch {
                permissionState
                    .map { it.snapshot.batteryOptimization }
                    .distinctUntilChanged()
                    .collect { status ->
                        if (
                            status == com.poyka.ripdpi.permissions.PermissionStatus.RequiresSettings &&
                            settingsState.value.batteryBannerDismissed
                        ) {
                            appSettingsRepository.update { setBatteryBannerDismissed(false) }
                        }
                    }
            }
        }

        fun onPrimaryConnectionAction() {
            when (uiState.value.connectionState) {
                ConnectionState.Connecting -> {
                    return
                }

                ConnectionState.Connected -> {
                    connectionActions.stop()
                }

                ConnectionState.Disconnected,
                ConnectionState.Error,
                -> {
                    when (uiState.value.appStatus) {
                        AppStatus.Halted -> {
                            permissionActions.resolvePermissionAction(PermissionAction.StartConfiguredMode)
                        }

                        AppStatus.Running -> {
                            connectionActions.stop()
                        }
                    }
                }
            }
        }

        fun onVpnPermissionContinueRequested() = permissionActions.onVpnPermissionContinueRequested()

        fun onOpenVpnPermissionRequested() = permissionActions.onOpenVpnPermissionRequested()

        fun onRepairPermissionRequested(kind: PermissionKind) = permissionActions.onRepairPermissionRequested(kind)

        fun onPermissionResult(
            kind: PermissionKind,
            result: PermissionResult,
        ) = permissionActions.onPermissionResult(kind, result)

        fun refreshPermissionSnapshot() = permissionActions.refreshPermissionSnapshot()

        fun dismissError() = connectionActions.dismissError()

        fun onDismissBatteryBanner() {
            viewModelScope.launch {
                appSettingsRepository.update { setBatteryBannerDismissed(true) }
            }
        }

        fun onDismissBackgroundGuidance() {
            viewModelScope.launch {
                appSettingsRepository.update { setBackgroundGuidanceDismissed(true) }
            }
        }

        fun onRunHomeFullAnalysis() = homeDiagnosticsActions.runFullAnalysis()

        fun onStartVerifiedVpn() = homeDiagnosticsActions.startVerifiedVpn()

        fun onShareHomeAnalysis() = homeDiagnosticsActions.shareLatestHomeAnalysis()

        fun dismissHomeAnalysisSheet() = homeDiagnosticsActions.dismissAnalysisSheet()

        fun dismissHomeVerificationSheet() = homeDiagnosticsActions.dismissVerificationSheet()

        fun buildCrashReportShareText(report: CrashReport): Pair<String, String> =
            crashReportReader.buildShareText(report)

        fun dismissCrashReport() {
            _pendingCrashReport.value = null
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                crashReportReader.delete()
            }
        }
    }
