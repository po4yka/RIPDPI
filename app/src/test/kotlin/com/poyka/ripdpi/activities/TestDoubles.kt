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
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeAuditOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.HiddenProbeConflictAction
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.PinVerifier
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
        val now = System.currentTimeMillis()
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            currentTelemetry.copy(
                mode = mode,
                status = status,
                serviceStartedAt =
                    when {
                        status == AppStatus.Running && currentTelemetry.status != AppStatus.Running -> now
                        status == AppStatus.Running -> currentTelemetry.serviceStartedAt
                        else -> null
                    },
                restartCount =
                    when {
                        status == AppStatus.Running && currentTelemetry.status != AppStatus.Running -> {
                            currentTelemetry.restartCount + 1
                        }

                        else -> {
                            currentTelemetry.restartCount
                        }
                    },
                updatedAt = now,
            )
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val now = System.currentTimeMillis()
        telemetryState.value =
            telemetryState.value.copy(
                lastFailureSender = sender,
                lastFailureAt = now,
                updatedAt = now,
            )
        eventFlow.tryEmit(ServiceEvent.Failed(sender, reason))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            snapshot.copy(
                serviceStartedAt = snapshot.serviceStartedAt ?: currentTelemetry.serviceStartedAt,
                restartCount = maxOf(snapshot.restartCount, currentTelemetry.restartCount),
                lastFailureSender = snapshot.lastFailureSender ?: currentTelemetry.lastFailureSender,
                lastFailureAt = snapshot.lastFailureAt ?: currentTelemetry.lastFailureAt,
            )
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
    override val activeConnectionSession = MutableStateFlow<DiagnosticConnectionSession?>(null)
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

class StubDiagnosticsScanController : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
    var lastStartedPathMode: com.poyka.ripdpi.diagnostics.ScanPathMode? = null
    var cancelCount: Int = 0
        private set
    var lastActiveProfileId: String? = null
    val startedRequests = mutableListOf<Pair<com.poyka.ripdpi.diagnostics.ScanPathMode, String?>>()
    val startResults = ArrayDeque<DiagnosticsManualScanStartResult>()
    var startFailure: Throwable? = null

    override suspend fun startScan(
        pathMode: com.poyka.ripdpi.diagnostics.ScanPathMode,
        selectedProfileId: String?,
    ): DiagnosticsManualScanStartResult {
        startFailure?.let { throw it }
        lastStartedPathMode = pathMode
        lastActiveProfileId = selectedProfileId
        startedRequests += pathMode to selectedProfileId
        return startResults.removeFirstOrNull() ?: DiagnosticsManualScanStartResult.Started("session")
    }

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution = DiagnosticsManualScanResolution.Started("session")

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
    var archiveRequest: DiagnosticsArchiveRequest? = null
    var archiveFailure: Throwable? = null
    var archiveResult: DiagnosticsArchive =
        DiagnosticsArchive(
            fileName = "diagnostics.zip",
            absolutePath = "/tmp/diagnostics.zip",
            sessionId = "session",
            createdAt = 1L,
            scope = "session",
            schemaVersion = 2,
            privacyMode = "redacted",
        )

    override suspend fun buildShareSummary(sessionId: String?): com.poyka.ripdpi.diagnostics.ShareSummary {
        error("unused")
    }

    override suspend fun createArchive(
        request: com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest,
    ): com.poyka.ripdpi.diagnostics.DiagnosticsArchive {
        archiveRequest = request
        archiveFailure?.let { throw it }
        return archiveResult
    }
}

class StubDiagnosticsHomeWorkflowService : DiagnosticsHomeWorkflowService {
    var currentFingerprint: String? = "fingerprint-current"
    val finalizedSessionIds = mutableListOf<String>()
    val verificationSessionIds = mutableListOf<String>()
    val auditOutcomes = mutableMapOf<String, DiagnosticsHomeAuditOutcome>()
    val verificationOutcomes = mutableMapOf<String, DiagnosticsHomeVerificationOutcome>()

    override suspend fun currentFingerprintHash(): String? = currentFingerprint

    override suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome {
        finalizedSessionIds += sessionId
        return auditOutcomes[sessionId]
            ?: DiagnosticsHomeAuditOutcome(
                sessionId = sessionId,
                fingerprintHash = currentFingerprint,
                actionable = false,
                headline = "Analysis complete",
                summary = "No reusable outcome",
                appliedSettings = emptyList<DiagnosticsAppliedSetting>(),
            )
    }

    override suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome {
        verificationSessionIds += sessionId
        return verificationOutcomes[sessionId]
            ?: DiagnosticsHomeVerificationOutcome(
                sessionId = sessionId,
                success = false,
                headline = "Verification incomplete",
                summary = "No verification outcome",
            )
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

class FakePinVerifier : PinVerifier {
    override fun hashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun verify(
        candidatePin: String,
        storedHash: String,
    ): Boolean {
        if (storedHash.isBlank()) return false
        return hashPin(candidatePin) == storedHash
    }

    override fun isKeyAvailable(): Boolean = true
}
