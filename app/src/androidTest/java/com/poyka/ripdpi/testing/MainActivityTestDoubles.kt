package com.poyka.ripdpi.testing

import android.content.Intent
import com.poyka.ripdpi.activities.MainActivityHost
import com.poyka.ripdpi.activities.MainActivityHostCommand
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.HostAutolearnStoreController
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
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
        activity: androidx.activity.ComponentActivity,
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
            result = com.poyka.ripdpi.activities.MainActivity.mapNotificationPermissionResult(granted, shouldShowRationale),
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
    override val profiles =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity>())
    override val sessions =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.ScanSessionEntity>())
    override val approachStats = MutableStateFlow(emptyList<BypassApproachSummary>())
    override val snapshots =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity>())
    override val contexts =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity>())
    override val telemetry =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity>())
    override val nativeEvents =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>())
    override val exports =
        MutableStateFlow(emptyList<com.poyka.ripdpi.data.diagnostics.ExportRecordEntity>())
}

class StubInstrumentedDiagnosticsScanController : DiagnosticsScanController {
    override suspend fun startScan(pathMode: com.poyka.ripdpi.diagnostics.ScanPathMode): String = "session"

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

    override suspend fun createArchive(sessionId: String?): com.poyka.ripdpi.diagnostics.DiagnosticsArchive {
        error("unused")
    }
}

class StubInstrumentedDiagnosticsResolverActions : DiagnosticsResolverActions {
    override suspend fun keepResolverRecommendationForSession(sessionId: String) = Unit

    override suspend fun saveResolverRecommendation(sessionId: String) = Unit
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
