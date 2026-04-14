package com.poyka.ripdpi.testing

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.poyka.ripdpi.activities.MainActivityHost
import com.poyka.ripdpi.activities.MainActivityHostCommand
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeAuditOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeProgress
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStarted
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.HostAutolearnStoreController
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.VpnTunnelBuilderHost
import com.poyka.ripdpi.services.VpnTunnelSession
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

class FakeInstrumentedAppSettingsRepository(
    initialSettings: AppSettings,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

class RecordingInstrumentedServiceController : ServiceController {
    val startedModes = CopyOnWriteArrayList<Mode>()
    var stopCount: Int = 0
        private set

    override fun start(mode: Mode) {
        startedModes += mode
    }

    override fun stop() {
        stopCount += 1
    }
}

class MutablePermissionStatusProvider(
    var snapshot: PermissionSnapshot = PermissionSnapshot(),
) : PermissionStatusProvider {
    override fun currentSnapshot(): PermissionSnapshot = snapshot
}

class FakeInstrumentedPermissionPlatformBridge(
    var vpnPermissionIntent: Intent? = Intent("fake.vpn.permission"),
    var appSettingsIntent: Intent = Intent("fake.app.settings"),
    var batteryOptimizationIntent: Intent = Intent("fake.battery.optimization"),
) : PermissionPlatformBridge {
    override fun prepareVpnPermissionIntent(): Intent? = vpnPermissionIntent

    override fun createAppSettingsIntent(): Intent = appSettingsIntent

    override fun createBatteryOptimizationIntent(): Intent = batteryOptimizationIntent
}

class FakeInstrumentedStringResolver : StringResolver {
    override fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String =
        buildString {
            append(resId)
            if (formatArgs.isNotEmpty()) {
                append(':')
                append(formatArgs.joinToString(","))
            }
        }
}

class FakeInstrumentedLauncherIconController : LauncherIconController {
    override fun applySelection(
        iconKey: String,
        iconStyle: String,
    ) = Unit
}

class FakeInstrumentedHostAutolearnStoreController : HostAutolearnStoreController {
    override fun hasStore(): Boolean = false

    override fun clearStore(): Boolean = false
}

internal class RecordingMainActivityHost : MainActivityHost {
    internal val commands = CopyOnWriteArrayList<MainActivityHostCommand>()
    private var viewModel: MainViewModel? = null

    override fun register(
        activity: AppCompatActivity,
        viewModel: MainViewModel,
    ) {
        this.viewModel = viewModel
    }

    override fun handle(command: MainActivityHostCommand) {
        commands += command
    }

    fun clear() {
        commands.clear()
    }

    fun dispatchNotificationPermissionResult(
        granted: Boolean,
        shouldShowRationale: Boolean,
    ) {
        viewModel?.onPermissionResult(
            kind = com.poyka.ripdpi.permissions.PermissionKind.Notifications,
            result =
                com.poyka.ripdpi.activities.MainActivity.mapNotificationPermissionResult(
                    granted,
                    shouldShowRationale,
                ),
        )
    }

    fun dispatchVpnConsentResult(granted: Boolean) {
        viewModel?.onPermissionResult(
            kind = com.poyka.ripdpi.permissions.PermissionKind.VpnConsent,
            result =
                if (granted) {
                    com.poyka.ripdpi.permissions.PermissionResult.Granted
                } else {
                    com.poyka.ripdpi.permissions.PermissionResult.Denied
                },
        )
    }

    fun dispatchBatteryOptimizationResult() {
        viewModel?.onPermissionResult(
            kind = com.poyka.ripdpi.permissions.PermissionKind.BatteryOptimization,
            result = com.poyka.ripdpi.permissions.PermissionResult.ReturnedFromSettings,
        )
    }
}

class StubInstrumentedDiagnosticsBootstrapper : DiagnosticsBootstrapper {
    override suspend fun initialize() = Unit
}

class StubInstrumentedDiagnosticsTimelineSource : DiagnosticsTimelineSource {
    override val activeScanProgress = MutableStateFlow<com.poyka.ripdpi.diagnostics.ScanProgress?>(null)
    override val activeConnectionSession =
        MutableStateFlow<com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession?>(null)
    override val profiles = MutableStateFlow(emptyList<DiagnosticProfile>())
    override val sessions = MutableStateFlow(emptyList<DiagnosticScanSession>())
    override val approachStats = MutableStateFlow(emptyList<BypassApproachSummary>())
    override val snapshots = MutableStateFlow(emptyList<DiagnosticNetworkSnapshot>())
    override val contexts = MutableStateFlow(emptyList<DiagnosticContextSnapshot>())
    override val telemetry = MutableStateFlow(emptyList<DiagnosticTelemetrySample>())
    override val nativeEvents = MutableStateFlow(emptyList<DiagnosticEvent>())
    override val liveSnapshots = MutableStateFlow(emptyList<DiagnosticNetworkSnapshot>())
    override val liveContexts = MutableStateFlow(emptyList<DiagnosticContextSnapshot>())
    override val liveTelemetry = MutableStateFlow(emptyList<DiagnosticTelemetrySample>())
    override val liveNativeEvents = MutableStateFlow(emptyList<DiagnosticEvent>())
    override val exports = MutableStateFlow(emptyList<DiagnosticExportRecord>())
}

class StubInstrumentedDiagnosticsScanController : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun startScan(
        pathMode: com.poyka.ripdpi.diagnostics.ScanPathMode,
        selectedProfileId: String?,
        skipActiveScanCheck: Boolean,
        scanDeadlineMs: Long?,
        maxCandidates: Int?,
    ): com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult =
        com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
            .Started("session")

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: com.poyka.ripdpi.diagnostics.HiddenProbeConflictAction,
    ): com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution =
        com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution
            .Started("session")

    override suspend fun cancelActiveScan() = Unit

    override suspend fun setActiveProfile(profileId: String) = Unit
}

class StubInstrumentedDiagnosticsDetailLoader : DiagnosticsDetailLoader {
    override suspend fun loadSessionDetail(sessionId: String): com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail {
        error("unused")
    }

    override suspend fun loadApproachDetail(
        kind: com.poyka.ripdpi.diagnostics.BypassApproachKind,
        id: String,
    ): com.poyka.ripdpi.diagnostics.BypassApproachDetail {
        error("unused")
    }
}

class StubInstrumentedDiagnosticsShareService : DiagnosticsShareService {
    override suspend fun buildShareSummary(sessionId: String?): com.poyka.ripdpi.diagnostics.ShareSummary {
        error("unused")
    }

    override suspend fun createArchive(
        request: com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest,
    ): com.poyka.ripdpi.diagnostics.DiagnosticsArchive {
        error("unused")
    }
}

class StubInstrumentedDiagnosticsResolverActions : DiagnosticsResolverActions {
    override suspend fun keepResolverRecommendationForSession(sessionId: String) = Unit

    override suspend fun saveResolverRecommendation(sessionId: String) = Unit
}

class StubInstrumentedDiagnosticsHistorySource : DiagnosticsHistorySource {
    override fun observeConnectionSessions(limit: Int): Flow<List<DiagnosticConnectionSession>> =
        MutableStateFlow(emptyList())

    override fun observeDiagnosticsSessions(limit: Int): Flow<List<DiagnosticScanSession>> =
        MutableStateFlow(emptyList())

    override fun observeNativeEvents(limit: Int): Flow<List<DiagnosticEvent>> = MutableStateFlow(emptyList())

    override suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail? = null
}

class StubInstrumentedDiagnosticsRememberedPolicySource : DiagnosticsRememberedPolicySource {
    override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> = MutableStateFlow(emptyList())

    override suspend fun clearAll() = Unit
}

class StubInstrumentedDiagnosticsActiveConnectionPolicySource : DiagnosticsActiveConnectionPolicySource {
    override val activePolicies: StateFlow<Map<Mode, DiagnosticActiveConnectionPolicy>> =
        MutableStateFlow(emptyMap())
}

class FakeInstrumentedServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        eventFlow.tryEmit(ServiceEvent.Failed(sender, reason))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

class StubInstrumentedProxyPreferencesResolver : ProxyPreferencesResolver {
    override suspend fun resolve(): com.poyka.ripdpi.core.RipDpiProxyPreferences = RipDpiProxyUIPreferences()
}

class StubInstrumentedRipDpiProxyRuntime : RipDpiProxyRuntime {
    override suspend fun startProxy(preferences: com.poyka.ripdpi.core.RipDpiProxyPreferences): Int = 0

    override suspend fun awaitReady(timeoutMillis: Long) = Unit

    override suspend fun stopProxy() = Unit

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy")

    override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) = Unit
}

class StubInstrumentedRipDpiProxyFactory : RipDpiProxyFactory {
    override fun create(): RipDpiProxyRuntime = StubInstrumentedRipDpiProxyRuntime()
}

class StubInstrumentedTun2SocksBridge : Tun2SocksBridge {
    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) = Unit

    override suspend fun stop() = Unit

    override suspend fun stats(): TunnelStats = TunnelStats()

    override suspend fun telemetry(): NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "tunnel")
}

class StubInstrumentedTun2SocksBridgeFactory : Tun2SocksBridgeFactory {
    override fun create(): Tun2SocksBridge = StubInstrumentedTun2SocksBridge()
}

class StubInstrumentedVpnTunnelSession : VpnTunnelSession {
    override val tunFd: Int = -1

    override fun close() = Unit
}

class StubInstrumentedVpnTunnelSessionProvider : VpnTunnelSessionProvider {
    override fun establish(
        host: VpnTunnelBuilderHost,
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelSession = StubInstrumentedVpnTunnelSession()
}

class StubInstrumentedDiagnosticsHomeWorkflowService : DiagnosticsHomeWorkflowService {
    override suspend fun currentFingerprintHash(): String? = null

    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome =
        DiagnosticsHomeAuditOutcome(
            sessionId = sessionId,
            fingerprintHash = null,
            actionable = false,
            headline = "Test",
            summary = "Test stub",
        )

    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome =
        DiagnosticsHomeVerificationOutcome(
            sessionId = sessionId,
            success = false,
            headline = "Test",
            summary = "Test stub",
        )
}

class StubInstrumentedDiagnosticsHomeCompositeRunService : DiagnosticsHomeCompositeRunService {
    override suspend fun startHomeAnalysis(
        options: com.poyka.ripdpi.diagnostics.DiagnosticsHomeRunOptions,
    ): DiagnosticsHomeCompositeRunStarted =
        DiagnosticsHomeCompositeRunStarted(runId = "test-run")

    override suspend fun startQuickAnalysis(
        options: com.poyka.ripdpi.diagnostics.DiagnosticsHomeRunOptions,
    ): DiagnosticsHomeCompositeRunStarted =
        DiagnosticsHomeCompositeRunStarted(runId = "test-quick-run")

    override fun observeHomeRun(runId: String): Flow<DiagnosticsHomeCompositeProgress> =
        MutableStateFlow(DiagnosticsHomeCompositeProgress(runId = runId))

    override suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = runId,
            actionable = false,
            headline = "Test",
            summary = "Test stub",
        )

    override suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? = null

    override suspend fun lookupCachedOutcome(
        fingerprintHash: String,
    ): com.poyka.ripdpi.diagnostics.CachedProbeOutcome? = null

    override suspend fun evictCachedOutcome(fingerprintHash: String) = Unit
}
