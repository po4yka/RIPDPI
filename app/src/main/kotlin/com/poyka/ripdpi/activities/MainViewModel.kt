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
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import com.poyka.ripdpi.permissions.PermissionAction
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    data object RelockRequested : MainEffect
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
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val totalStageCount: Int = 0,
    val stale: Boolean = false,
    val actionable: Boolean = false,
)

enum class AnalysisStageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

@Immutable
data class AnalysisStageUiState(
    val status: AnalysisStageStatus,
    val progress: Float = 0f,
)

@Immutable
data class AnalysisProgressUiState(
    val stages: List<AnalysisStageUiState>,
    val activeStageIndex: Int?,
)

@Immutable
data class HomeDiagnosticsStageUiState(
    val label: String,
    val headline: String,
    val summary: String,
    val failed: Boolean = false,
    val skipped: Boolean = false,
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
    val capabilityEvidence: List<DiagnosticsCapabilityEvidenceUiModel> = emptyList(),
    val stageSummaries: List<HomeDiagnosticsStageUiState> = emptyList(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val shareBusy: Boolean = false,
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
    val analysisProgress: AnalysisProgressUiState? = null,
    val quickScanBusy: Boolean = false,
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

internal data class MainUiInputs(
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
        private val mainServiceDependencies: MainServiceDependencies,
        private val mainPermissionDependencies: MainPermissionDependencies,
        private val mainDiagnosticsDependencies: MainDiagnosticsDependencies,
        private val mainLifecycleDependencies: MainLifecycleDependencies,
        private val stringResolver: StringResolver,
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
                serviceController = mainServiceDependencies.serviceController,
                serviceStateStore = mainServiceDependencies.serviceStateStore,
                trafficStatsReader = mainServiceDependencies.trafficStatsReader,
                stringResolver = stringResolver,
                runtimeState = runtimeState,
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
                onRunHomeAnalysis = { homeDiagnosticsActions.runFullAnalysis() },
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
                diagnosticsTimelineSource = mainDiagnosticsDependencies.diagnosticsTimelineSource,
                diagnosticsScanController = mainDiagnosticsDependencies.diagnosticsScanController,
                diagnosticsShareService = mainDiagnosticsDependencies.diagnosticsShareService,
                diagnosticsHomeWorkflowService = mainDiagnosticsDependencies.homeDiagnosticsServices.workflowService,
                diagnosticsHomeCompositeRunService =
                    mainDiagnosticsDependencies.homeDiagnosticsServices.compositeRunService,
                serviceStateStore = mainServiceDependencies.serviceStateStore,
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
                mainServiceDependencies.serviceStateStore.status,
                runtimeState,
                permissionState,
                mainDiagnosticsDependencies.diagnosticsTimelineSource.approachStats,
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
                val configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })
                buildMainUiState(
                    inputs = inputs,
                    homeDiagnostics = homeDiagnostics,
                    stringResolver = stringResolver,
                    approachSummary =
                        connectionActions.buildApproachSummary(
                            settings = settings,
                            activeMode = if (status == AppStatus.Running) activeMode else configuredMode,
                            approachStats = inputs.approachStats,
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
            mainLifecycleDependencies.appLockLifecycleCoordinator.start(
                isBiometricEnabled = { settingsState.value.biometricEnabled },
            ) {
                _effects.trySend(MainEffect.RelockRequested)
            }
            mainLifecycleDependencies.startupSideEffectsCoordinator.start(
                scope = viewModelScope,
                batteryOptimizationStatus = permissionState.map { it.snapshot.batteryOptimization },
                isBatteryBannerDismissed = { settingsState.value.batteryBannerDismissed },
            ) { report ->
                _pendingCrashReport.value = report
            }
        }

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
            mainLifecycleDependencies.settingsDismissCoordinator.dismissBatteryBanner(viewModelScope)
        }

        fun onDismissBackgroundGuidance() {
            mainLifecycleDependencies.settingsDismissCoordinator.dismissBackgroundGuidance(viewModelScope)
        }

        fun onRunHomeFullAnalysis() = permissionActions.resolvePermissionAction(PermissionAction.RunHomeAnalysis)

        fun onRunHomeQuickAnalysis() = homeDiagnosticsActions.runQuickAnalysis()

        fun onStartVerifiedVpn() = homeDiagnosticsActions.startVerifiedVpn()

        fun onShareHomeAnalysis() = homeDiagnosticsActions.shareLatestHomeAnalysis()

        fun dismissHomeAnalysisSheet() = homeDiagnosticsActions.dismissAnalysisSheet()

        fun dismissHomeVerificationSheet() = homeDiagnosticsActions.dismissVerificationSheet()

        fun buildCrashReportShareText(report: CrashReport): Pair<String, String> =
            mainLifecycleDependencies.crashReportCoordinator.buildShareText(report)

        fun dismissCrashReport() {
            mainLifecycleDependencies.crashReportCoordinator.dismiss(viewModelScope) {
                _pendingCrashReport.value = null
            }
        }

        fun onAuthenticated() {
            mainLifecycleDependencies.appLockLifecycleCoordinator.onAuthenticated()
        }
    }
