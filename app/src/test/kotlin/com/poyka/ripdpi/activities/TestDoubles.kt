package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAppSettingsRepository(
    initialSettings: AppSettings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
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

class FakeServiceStateStore(
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

class FakeServiceController : ServiceController {
    val startedModes = mutableListOf<Mode>()
    var stopCount = 0

    override fun start(mode: Mode) {
        startedModes += mode
    }

    override fun stop() {
        stopCount += 1
    }
}

class FakeLauncherIconController : LauncherIconController {
    data class Selection(
        val iconKey: String,
        val iconStyle: String,
    )

    var lastSelection: Selection? = null

    override fun applySelection(
        iconKey: String,
        iconStyle: String,
    ) {
        lastSelection = Selection(iconKey, iconStyle)
    }
}

class FakeStringResolver : StringResolver {
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

class FakeTrafficStatsReader(
    var transferredBytes: Long = 0L,
) : TrafficStatsReader {
    override fun currentTransferredBytes(): Long = transferredBytes
}

class FakePermissionPlatformBridge(
    var vpnPermissionIntent: Intent? = Intent("fake.vpn.permission"),
    var appSettingsIntent: Intent = Intent("fake.app.settings"),
    var batteryOptimizationIntent: Intent = Intent("fake.battery.optimization"),
) : PermissionPlatformBridge {
    override fun prepareVpnPermissionIntent(): Intent? = vpnPermissionIntent

    override fun createAppSettingsIntent(): Intent = appSettingsIntent

    override fun createBatteryOptimizationIntent(): Intent = batteryOptimizationIntent
}

class FakePermissionStatusProvider(
    var snapshot: PermissionSnapshot = PermissionSnapshot(),
) : PermissionStatusProvider {
    var currentSnapshotCalls: Int = 0
        private set

    override fun currentSnapshot(): PermissionSnapshot {
        currentSnapshotCalls += 1
        return snapshot
    }
}

class StubDiagnosticsBootstrapper : DiagnosticsBootstrapper {
    var initializeCalls: Int = 0
        private set

    override suspend fun initialize() {
        initializeCalls += 1
    }
}

class StubDiagnosticsTimelineSource : DiagnosticsTimelineSource {
    override val activeScanProgress =
        MutableStateFlow<com.poyka.ripdpi.diagnostics.ScanProgress?>(null)
    override val profiles = MutableStateFlow(emptyList<DiagnosticProfile>())
    override val sessions = MutableStateFlow(emptyList<DiagnosticScanSession>())
    override val approachStats = MutableStateFlow(emptyList<BypassApproachSummary>())
    override val snapshots = MutableStateFlow(emptyList<DiagnosticNetworkSnapshot>())
    override val contexts = MutableStateFlow(emptyList<DiagnosticContextSnapshot>())
    override val telemetry = MutableStateFlow(emptyList<DiagnosticTelemetrySample>())
    override val nativeEvents = MutableStateFlow(emptyList<DiagnosticEvent>())
    override val exports = MutableStateFlow(emptyList<DiagnosticExportRecord>())
}

class StubDiagnosticsScanController : DiagnosticsScanController {
    var lastStartedPathMode: com.poyka.ripdpi.diagnostics.ScanPathMode? = null
    var cancelCount: Int = 0
        private set
    var lastActiveProfileId: String? = null

    override suspend fun startScan(pathMode: com.poyka.ripdpi.diagnostics.ScanPathMode): String {
        lastStartedPathMode = pathMode
        return "session"
    }

    override suspend fun cancelActiveScan() {
        cancelCount += 1
    }

    override suspend fun setActiveProfile(profileId: String) {
        lastActiveProfileId = profileId
    }
}

class StubDiagnosticsDetailLoader : DiagnosticsDetailLoader {
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

class StubDiagnosticsShareService : DiagnosticsShareService {
    override suspend fun buildShareSummary(sessionId: String?): com.poyka.ripdpi.diagnostics.ShareSummary {
        error("unused")
    }

    override suspend fun createArchive(sessionId: String?): com.poyka.ripdpi.diagnostics.DiagnosticsArchive {
        error("unused")
    }
}

class StubDiagnosticsResolverActions : DiagnosticsResolverActions {
    var keptSessionId: String? = null
    var savedSessionId: String? = null

    override suspend fun keepResolverRecommendationForSession(sessionId: String) {
        keptSessionId = sessionId
    }

    override suspend fun saveResolverRecommendation(sessionId: String) {
        savedSessionId = sessionId
    }
}
