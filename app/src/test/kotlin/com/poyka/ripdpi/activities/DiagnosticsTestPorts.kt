package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassOutcomeBreakdown
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.HiddenProbeConflictAction
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.SummaryMetric
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeDiagnosticsManager(
    var detail: DiagnosticSessionDetail? = null,
    private val archiveFailure: Throwable? = null,
) {
    val bootstrapper = FakeDiagnosticsBootstrapper()
    val timelineSource = FakeDiagnosticsTimelineSource()
    val scanController = FakeDiagnosticsScanController()
    val detailLoader = FakeDiagnosticsDetailLoader()
    val shareService = FakeDiagnosticsShareService()
    val resolverActions = FakeDiagnosticsResolverActions()

    val progressState: MutableStateFlow<ScanProgress?> = timelineSource.activeScanProgress
    val activeConnectionSessionState: MutableStateFlow<DiagnosticConnectionSession?> =
        timelineSource.activeConnectionSession
    val profilesState: MutableStateFlow<List<DiagnosticProfile>> = timelineSource.profiles
    val sessionsState: MutableStateFlow<List<DiagnosticScanSession>> = timelineSource.sessions
    val snapshotsState: MutableStateFlow<List<DiagnosticNetworkSnapshot>> = timelineSource.snapshots
    val contextsState: MutableStateFlow<List<DiagnosticContextSnapshot>> =
        timelineSource.contexts
    val telemetryState: MutableStateFlow<List<DiagnosticTelemetrySample>> = timelineSource.telemetry
    val nativeEventsState: MutableStateFlow<List<DiagnosticEvent>> = timelineSource.nativeEvents
    val liveSnapshotsState: MutableStateFlow<List<DiagnosticNetworkSnapshot>> = timelineSource.liveSnapshots
    val liveContextsState: MutableStateFlow<List<DiagnosticContextSnapshot>> = timelineSource.liveContexts
    val liveTelemetryState: MutableStateFlow<List<DiagnosticTelemetrySample>> = timelineSource.liveTelemetry
    val liveNativeEventsState: MutableStateFlow<List<DiagnosticEvent>> = timelineSource.liveNativeEvents
    val exportsState: MutableStateFlow<List<DiagnosticExportRecord>> = timelineSource.exports
    val approachStatsState: MutableStateFlow<List<BypassApproachSummary>> = timelineSource.approachStats

    var initializeCalls: Int = 0
        private set
    var lastArchiveSessionId: String? = null
        private set
    var lastActiveProfileId: String? = null
        private set
    var keptResolverRecommendationSessionId: String? = null
        private set
    var savedResolverRecommendationSessionId: String? = null
        private set
    var strategySignatureOverride: BypassStrategySignature? = null

    init {
        bootstrapper.onInitialize = {
            initializeCalls += 1
        }
        scanController.onStartScan = { pathMode ->
            DiagnosticsManualScanStartResult.Started("session-${pathMode.name}")
        }
        scanController.onCancel = { progressState.value = null }
        scanController.onSetActiveProfile = { profileId -> lastActiveProfileId = profileId }
        detailLoader.onLoadSessionDetail = { sessionId ->
            requireNotNull(detail) { "Missing fake detail for $sessionId" }
        }
        detailLoader.onLoadApproachDetail = { kind, id ->
            BypassApproachDetail(
                summary =
                    approachStatsState.value.firstOrNull { it.approachId.kind == kind && it.approachId.value == id }
                        ?: sampleApproachSummary(kind = kind, id = id),
                strategySignature =
                    strategySignatureOverride ?: BypassStrategySignature(
                        mode = "VPN",
                        configSource = "ui",
                        hostAutolearn = "enabled",
                        desyncMethod = "split",
                        chainSummary = "tcp: split(1)",
                        protocolToggles = listOf("HTTP", "HTTPS"),
                        tlsRecordSplitEnabled = true,
                        tlsRecordMarker = "extlen",
                        splitMarker = "1",
                        fakeSniMode = null,
                        fakeSniValue = null,
                        fakeTlsBaseMode = null,
                        fakeTlsMods = emptyList(),
                        fakeTlsSize = null,
                        fakeOffsetMarker = null,
                        routeGroup = "3",
                    ),
                recentValidatedSessions = sessionsState.value.take(2),
                recentUsageSessions = emptyList<DiagnosticConnectionSession>(),
                commonProbeFailures = listOf("dns_blocked (2)"),
                recentFailureNotes = listOf("dns:example.org=blocked"),
            )
        }
        shareService.onBuildShareSummary = { sessionId ->
            ShareSummary(
                title = "RIPDPI summary",
                body = "Summary for ${sessionId ?: "latest"}",
                compactMetrics = listOf(SummaryMetric("Path", "RAW_PATH")),
            )
        }
        shareService.onCreateArchive = { sessionId ->
            archiveFailure?.let { throw it }
            lastArchiveSessionId = sessionId
            DiagnosticsArchive(
                fileName = "archive.zip",
                absolutePath = "/tmp/archive-${sessionId ?: "all"}.zip",
                sessionId = sessionId,
                createdAt = 42L,
                scope = "hybrid",
                schemaVersion = 1,
                privacyMode = "split_output",
            )
        }
        resolverActions.onKeep = { sessionId -> keptResolverRecommendationSessionId = sessionId }
        resolverActions.onSave = { sessionId -> savedResolverRecommendationSessionId = sessionId }
    }
}

internal class FakeDiagnosticsBootstrapper : DiagnosticsBootstrapper {
    var onInitialize: (suspend () -> Unit)? = null

    override suspend fun initialize() {
        onInitialize?.invoke()
    }
}

internal class FakeDiagnosticsTimelineSource : DiagnosticsTimelineSource {
    override val activeScanProgress = MutableStateFlow<ScanProgress?>(null)
    override val activeConnectionSession = MutableStateFlow<DiagnosticConnectionSession?>(null)
    override val profiles = MutableStateFlow<List<DiagnosticProfile>>(emptyList())
    override val sessions = MutableStateFlow<List<DiagnosticScanSession>>(emptyList())
    override val approachStats = MutableStateFlow<List<BypassApproachSummary>>(emptyList())
    override val snapshots = MutableStateFlow<List<DiagnosticNetworkSnapshot>>(emptyList())
    override val contexts = MutableStateFlow<List<DiagnosticContextSnapshot>>(emptyList())
    override val telemetry = MutableStateFlow<List<DiagnosticTelemetrySample>>(emptyList())
    override val nativeEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    override val liveSnapshots = MutableStateFlow<List<DiagnosticNetworkSnapshot>>(emptyList())
    override val liveContexts = MutableStateFlow<List<DiagnosticContextSnapshot>>(emptyList())
    override val liveTelemetry = MutableStateFlow<List<DiagnosticTelemetrySample>>(emptyList())
    override val liveNativeEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    override val exports = MutableStateFlow<List<DiagnosticExportRecord>>(emptyList())
}

internal class FakeDiagnosticsScanController : DiagnosticsScanController {
    override val hiddenAutomaticProbeActive = MutableStateFlow(false)
    var onStartScan: (suspend (ScanPathMode, String?) -> DiagnosticsManualScanStartResult)? = null
    var onResolveHiddenProbeConflict:
        (suspend (String, HiddenProbeConflictAction) -> DiagnosticsManualScanResolution)? = null
    var onCancel: (suspend () -> Unit)? = null
    var onSetActiveProfile: (suspend (String) -> Unit)? = null

    override suspend fun startScan(
        pathMode: ScanPathMode,
        selectedProfileId: String?,
    ): DiagnosticsManualScanStartResult =
        onStartScan?.invoke(pathMode, selectedProfileId) ?: DiagnosticsManualScanStartResult.Started("session")

    override suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution =
        onResolveHiddenProbeConflict?.invoke(requestId, action)
            ?: DiagnosticsManualScanResolution.Started("session")

    override suspend fun cancelActiveScan() {
        onCancel?.invoke()
    }

    override suspend fun setActiveProfile(profileId: String) {
        onSetActiveProfile?.invoke(profileId)
    }
}

internal class FakeDiagnosticsDetailLoader : DiagnosticsDetailLoader {
    var onLoadSessionDetail: (suspend (String) -> DiagnosticSessionDetail)? = null
    var onLoadApproachDetail: (suspend (BypassApproachKind, String) -> BypassApproachDetail)? = null

    override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
        requireNotNull(onLoadSessionDetail) { "Missing loadSessionDetail handler" }(sessionId)

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail = requireNotNull(onLoadApproachDetail) { "Missing loadApproachDetail handler" }(kind, id)
}

internal class FakeDiagnosticsShareService : DiagnosticsShareService {
    var onBuildShareSummary: (suspend (String?) -> ShareSummary)? = null
    var onCreateArchive: (suspend (String?) -> DiagnosticsArchive)? = null

    override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
        requireNotNull(onBuildShareSummary) { "Missing buildShareSummary handler" }(sessionId)

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive =
        requireNotNull(onCreateArchive) { "Missing createArchive handler" }(sessionId)
}

internal class FakeDiagnosticsResolverActions : DiagnosticsResolverActions {
    var onKeep: (suspend (String) -> Unit)? = null
    var onSave: (suspend (String) -> Unit)? = null

    override suspend fun keepResolverRecommendationForSession(sessionId: String) {
        onKeep?.invoke(sessionId)
    }

    override suspend fun saveResolverRecommendation(sessionId: String) {
        onSave?.invoke(sessionId)
    }
}

internal fun sampleApproachSummary(
    kind: BypassApproachKind,
    id: String,
): BypassApproachSummary =
    BypassApproachSummary(
        approachId = BypassApproachId(kind = kind, value = id),
        displayName = "VPN Split",
        secondaryLabel = "Strategy",
        verificationState = "validated",
        validatedScanCount = 3,
        validatedSuccessCount = 2,
        validatedSuccessRate = 0.66f,
        lastValidatedResult = "Latest report",
        usageCount = 4,
        totalRuntimeDurationMs = 30_000L,
        recentRuntimeHealth = BypassRuntimeHealthSummary(totalErrors = 1, routeChanges = 2, restartCount = 1),
        lastUsedAt = 42L,
        topFailureOutcomes = listOf("dns_blocked (1)"),
        outcomeBreakdown = listOf(BypassOutcomeBreakdown("dns", 2, 0, 1, "dns_blocked")),
    )
