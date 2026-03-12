package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ScanRequest
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class DiagnosticsSection {
    Overview,
    Scan,
    Live,
    Approaches,
    Share,
}

enum class DiagnosticsApproachMode {
    Profiles,
    Strategies,
}

enum class DiagnosticsHealth {
    Healthy,
    Attention,
    Degraded,
    Idle,
}

enum class DiagnosticsTone {
    Neutral,
    Positive,
    Warning,
    Negative,
    Info,
}

data class DiagnosticsMetricUiModel(
    val label: String,
    val value: String,
    val tone: DiagnosticsTone = DiagnosticsTone.Neutral,
)

data class DiagnosticsFieldUiModel(
    val label: String,
    val value: String,
)

data class DiagnosticsNetworkSnapshotUiModel(
    val title: String,
    val subtitle: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsContextGroupUiModel(
    val title: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsProfileOptionUiModel(
    val id: String,
    val name: String,
    val source: String,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
)

data class DiagnosticsProgressUiModel(
    val phase: String,
    val summary: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val fraction: Float,
)

data class DiagnosticsProbeResultUiModel(
    val id: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val tone: DiagnosticsTone,
    val details: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsProbeGroupUiModel(
    val title: String,
    val items: List<DiagnosticsProbeResultUiModel>,
)

data class DiagnosticsEventUiModel(
    val id: String,
    val source: String,
    val severity: String,
    val message: String,
    val createdAtLabel: String,
    val tone: DiagnosticsTone,
)

data class DiagnosticsSparklineUiModel(
    val label: String,
    val values: List<Float>,
    val tone: DiagnosticsTone = DiagnosticsTone.Info,
)

data class DiagnosticsSessionRowUiModel(
    val id: String,
    val profileId: String,
    val title: String,
    val subtitle: String,
    val pathMode: String,
    val serviceMode: String,
    val status: String,
    val startedAtLabel: String,
    val summary: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

data class DiagnosticsSessionDetailUiModel(
    val session: DiagnosticsSessionRowUiModel,
    val probeGroups: List<DiagnosticsProbeGroupUiModel>,
    val snapshots: List<DiagnosticsNetworkSnapshotUiModel>,
    val events: List<DiagnosticsEventUiModel>,
    val contextGroups: List<DiagnosticsContextGroupUiModel>,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val hasSensitiveDetails: Boolean,
    val sensitiveDetailsVisible: Boolean,
)

data class DiagnosticsStrategyProbeCandidateUiModel(
    val id: String,
    val label: String,
    val outcome: String,
    val rationale: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
    val skipped: Boolean,
    val recommended: Boolean,
)

data class DiagnosticsStrategyProbeFamilyUiModel(
    val title: String,
    val candidates: List<DiagnosticsStrategyProbeCandidateUiModel>,
)

data class DiagnosticsStrategyProbeRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: List<DiagnosticsFieldUiModel>,
    val signature: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsStrategyProbeReportUiModel(
    val suiteLabel: String,
    val recommendation: DiagnosticsStrategyProbeRecommendationUiModel,
    val families: List<DiagnosticsStrategyProbeFamilyUiModel>,
)

data class DiagnosticsOverviewUiModel(
    val health: DiagnosticsHealth = DiagnosticsHealth.Idle,
    val headline: String = "Idle",
    val body: String = "No diagnostics activity yet.",
    val activeProfile: DiagnosticsProfileOptionUiModel? = null,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val contextSummary: DiagnosticsContextGroupUiModel? = null,
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val warnings: List<DiagnosticsEventUiModel> = emptyList(),
)

data class DiagnosticsScanUiModel(
    val profiles: List<DiagnosticsProfileOptionUiModel> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedProfile: DiagnosticsProfileOptionUiModel? = null,
    val activePathMode: ScanPathMode = ScanPathMode.RAW_PATH,
    val activeProgress: DiagnosticsProgressUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val latestResults: List<DiagnosticsProbeResultUiModel> = emptyList(),
    val selectedProfileScopeLabel: String? = null,
    val runRawEnabled: Boolean = true,
    val runInPathEnabled: Boolean = true,
    val runRawHint: String? = null,
    val runInPathHint: String? = null,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val isBusy: Boolean = false,
)

data class DiagnosticsLiveUiModel(
    val statusLabel: String = "Idle",
    val freshnessLabel: String = "No live telemetry",
    val headline: String = "Live monitor standing by",
    val body: String = "Continuous monitor is waiting for an active RIPDPI session.",
    val networkLabel: String? = null,
    val modeLabel: String? = null,
    val signalLabel: String = "No transfer observed yet",
    val eventSummaryLabel: String = "Runtime feed is quiet",
    val highlights: List<DiagnosticsMetricUiModel> = emptyList(),
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val trends: List<DiagnosticsSparklineUiModel> = emptyList(),
    val snapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val contextGroups: List<DiagnosticsContextGroupUiModel> = emptyList(),
    val passiveEvents: List<DiagnosticsEventUiModel> = emptyList(),
)

data class DiagnosticsSessionFiltersUiModel(
    val pathMode: String? = null,
    val status: String? = null,
    val query: String = "",
)

data class DiagnosticsSessionsUiModel(
    val filters: DiagnosticsSessionFiltersUiModel = DiagnosticsSessionFiltersUiModel(),
    val sessions: List<DiagnosticsSessionRowUiModel> = emptyList(),
    val pathModes: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val focusedSessionId: String? = null,
)

data class DiagnosticsApproachRowUiModel(
    val id: String,
    val kind: DiagnosticsApproachMode,
    val title: String,
    val subtitle: String,
    val verificationState: String,
    val lastValidatedResult: String,
    val dominantFailurePattern: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

data class DiagnosticsApproachDetailUiModel(
    val approach: DiagnosticsApproachRowUiModel,
    val signature: List<DiagnosticsFieldUiModel>,
    val breakdown: List<DiagnosticsMetricUiModel>,
    val runtimeSummary: List<DiagnosticsMetricUiModel>,
    val recentSessions: List<DiagnosticsSessionRowUiModel>,
    val recentUsageNotes: List<String>,
    val failureNotes: List<String>,
)

data class DiagnosticsApproachesUiModel(
    val selectedMode: DiagnosticsApproachMode = DiagnosticsApproachMode.Profiles,
    val rows: List<DiagnosticsApproachRowUiModel> = emptyList(),
    val focusedApproachId: String? = null,
)

data class DiagnosticsEventFiltersUiModel(
    val source: String? = null,
    val severity: String? = null,
    val search: String = "",
    val autoScroll: Boolean = true,
)

data class DiagnosticsEventsUiModel(
    val filters: DiagnosticsEventFiltersUiModel = DiagnosticsEventFiltersUiModel(),
    val events: List<DiagnosticsEventUiModel> = emptyList(),
    val availableSources: List<String> = emptyList(),
    val availableSeverities: List<String> = emptyList(),
    val focusedEventId: String? = null,
)

data class DiagnosticsShareUiModel(
    val targetSessionId: String? = null,
    val previewTitle: String = "RIPDPI diagnostics",
    val previewBody: String = "Select a session or use the latest diagnostics state.",
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val latestArchiveFileName: String? = null,
    val archiveStateMessage: String? = null,
    val archiveStateTone: DiagnosticsTone = DiagnosticsTone.Neutral,
    val isArchiveBusy: Boolean = false,
)

data class DiagnosticsUiState(
    val selectedSection: DiagnosticsSection = DiagnosticsSection.Overview,
    val overview: DiagnosticsOverviewUiModel = DiagnosticsOverviewUiModel(),
    val scan: DiagnosticsScanUiModel = DiagnosticsScanUiModel(),
    val live: DiagnosticsLiveUiModel = DiagnosticsLiveUiModel(),
    val sessions: DiagnosticsSessionsUiModel = DiagnosticsSessionsUiModel(),
    val approaches: DiagnosticsApproachesUiModel = DiagnosticsApproachesUiModel(),
    val events: DiagnosticsEventsUiModel = DiagnosticsEventsUiModel(),
    val share: DiagnosticsShareUiModel = DiagnosticsShareUiModel(),
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedApproachDetail: DiagnosticsApproachDetailUiModel? = null,
    val selectedEvent: DiagnosticsEventUiModel? = null,
    val selectedProbe: DiagnosticsProbeResultUiModel? = null,
)

sealed interface DiagnosticsEffect {
    data class ShareSummaryRequested(
        val title: String,
        val body: String,
    ) : DiagnosticsEffect

    data class ShareArchiveRequested(
        val absolutePath: String,
        val fileName: String,
    ) : DiagnosticsEffect

    data class SaveArchiveRequested(
        val absolutePath: String,
        val fileName: String,
    ) : DiagnosticsEffect
}

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val diagnosticsManager: DiagnosticsManager,
        private val appSettingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val json = Json { ignoreUnknownKeys = true }
        private val timestampFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        private val selectedSectionRequest = MutableStateFlow(DiagnosticsSection.Overview)
        private val selectedProfileId = MutableStateFlow<String?>(null)
        private val selectedApproachMode = MutableStateFlow(DiagnosticsApproachMode.Profiles)
        private val selectedApproachDetail = MutableStateFlow<DiagnosticsApproachDetailUiModel?>(null)
        private val selectedProbe = MutableStateFlow<DiagnosticsProbeResultUiModel?>(null)
        private val selectedEventId = MutableStateFlow<String?>(null)
        private val sessionPathModeFilter = MutableStateFlow<String?>(null)
        private val sessionStatusFilter = MutableStateFlow<String?>(null)
        private val sessionSearch = MutableStateFlow("")
        private val eventSourceFilter = MutableStateFlow<String?>(null)
        private val eventSeverityFilter = MutableStateFlow<String?>(null)
        private val eventSearch = MutableStateFlow("")
        private val eventAutoScroll = MutableStateFlow(true)
        private val selectedSessionDetail = MutableStateFlow<DiagnosticsSessionDetailUiModel?>(null)
        private val sensitiveSessionDetailsVisible = MutableStateFlow(false)
        private val archiveActionState = MutableStateFlow(ArchiveActionState())
        private val _effects = Channel<DiagnosticsEffect>(Channel.BUFFERED)
        val effects: Flow<DiagnosticsEffect> = _effects.receiveAsFlow()

        val uiState: StateFlow<DiagnosticsUiState> =
            combine(
                diagnosticsManager.profiles,
                appSettingsRepository.settings,
                diagnosticsManager.activeScanProgress,
                diagnosticsManager.sessions,
                diagnosticsManager.approachStats,
                diagnosticsManager.snapshots,
                diagnosticsManager.contexts,
                diagnosticsManager.telemetry,
                diagnosticsManager.nativeEvents,
                diagnosticsManager.exports,
                selectedSectionRequest,
                selectedProfileId,
                selectedApproachMode,
                selectedProbe,
                selectedEventId,
                sessionPathModeFilter,
                sessionStatusFilter,
                sessionSearch,
                eventSourceFilter,
                eventSeverityFilter,
                eventSearch,
                eventAutoScroll,
                selectedSessionDetail,
                selectedApproachDetail,
                sensitiveSessionDetailsVisible,
                archiveActionState,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val profiles = values[0] as List<DiagnosticProfileEntity>
                val settings = values[1] as com.poyka.ripdpi.proto.AppSettings
                val progress = values[2] as ScanProgress?
                val sessions = values[3] as List<ScanSessionEntity>
                val approachStats = values[4] as List<BypassApproachSummary>
                val snapshots = values[5] as List<NetworkSnapshotEntity>
                val contexts = values[6] as List<DiagnosticContextEntity>
                val telemetry = values[7] as List<TelemetrySampleEntity>
                val nativeEvents = values[8] as List<NativeSessionEventEntity>
                val exports = values[9] as List<ExportRecordEntity>
                val selectedSectionRequest = values[10] as DiagnosticsSection
                val selectedProfileId = values[11] as String?
                val selectedApproachMode = values[12] as DiagnosticsApproachMode
                val selectedProbe = values[13] as DiagnosticsProbeResultUiModel?
                val selectedEventId = values[14] as String?
                val sessionPathMode = values[15] as String?
                val sessionStatus = values[16] as String?
                val sessionSearch = values[17] as String
                val eventSource = values[18] as String?
                val eventSeverity = values[19] as String?
                val eventSearch = values[20] as String
                val eventAutoScroll = values[21] as Boolean
                val sessionDetail = values[22] as DiagnosticsSessionDetailUiModel?
                val approachDetail = values[23] as DiagnosticsApproachDetailUiModel?
                val sensitiveSessionDetailsVisible = values[24] as Boolean
                val archiveActionState = values[25] as ArchiveActionState

                buildUiState(
                    profiles = profiles,
                    settings = settings,
                    progress = progress,
                    sessions = sessions,
                    approachStats = approachStats,
                    snapshots = snapshots,
                    contexts = contexts,
                    telemetry = telemetry,
                    nativeEvents = nativeEvents,
                    exports = exports,
                    selectedSectionRequest = selectedSectionRequest,
                    selectedProfileId = selectedProfileId,
                    selectedApproachMode = selectedApproachMode,
                    selectedProbe = selectedProbe,
                    selectedEventId = selectedEventId,
                    sessionPathMode = sessionPathMode,
                    sessionStatus = sessionStatus,
                    sessionSearch = sessionSearch,
                    eventSource = eventSource,
                    eventSeverity = eventSeverity,
                    eventSearch = eventSearch,
                    eventAutoScroll = eventAutoScroll,
                    selectedSessionDetail = sessionDetail,
                    selectedApproachDetail = approachDetail,
                    sensitiveSessionDetailsVisible = sensitiveSessionDetailsVisible,
                    archiveActionState = archiveActionState,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DiagnosticsUiState(),
            )

        init {
            viewModelScope.launch {
                diagnosticsManager.initialize()
            }
        }

        fun selectSection(section: DiagnosticsSection) {
            selectedSectionRequest.value = section
        }

        fun selectProfile(profileId: String) {
            selectedProfileId.value = profileId
            viewModelScope.launch {
                diagnosticsManager.setActiveProfile(profileId)
            }
        }

        fun selectSession(sessionId: String) {
            viewModelScope.launch {
                val detail = diagnosticsManager.loadSessionDetail(sessionId)
                sensitiveSessionDetailsVisible.value = false
                selectedSessionDetail.value = detail.toUiModel(showSensitiveDetails = false)
            }
        }

        fun selectApproachMode(mode: DiagnosticsApproachMode) {
            selectedApproachMode.value = mode
            selectedApproachDetail.value = null
        }

        fun selectApproach(approachId: String) {
            viewModelScope.launch {
                val detail =
                    diagnosticsManager.loadApproachDetail(
                        kind =
                            when (selectedApproachMode.value) {
                                DiagnosticsApproachMode.Profiles -> BypassApproachKind.Profile
                                DiagnosticsApproachMode.Strategies -> BypassApproachKind.Strategy
                            },
                        id = approachId,
                    )
                selectedApproachDetail.value = detail.toUiModel()
            }
        }

        fun dismissSessionDetail() {
            selectedSessionDetail.value = null
            selectedProbe.value = null
            sensitiveSessionDetailsVisible.value = false
        }

        fun dismissApproachDetail() {
            selectedApproachDetail.value = null
        }

        fun selectEvent(eventId: String) {
            selectedEventId.value = eventId
        }

        fun dismissEventDetail() {
            selectedEventId.value = null
        }

        fun selectProbe(probe: DiagnosticsProbeResultUiModel) {
            selectedProbe.value = probe
        }

        fun dismissProbeDetail() {
            selectedProbe.value = null
        }

        fun toggleSensitiveSessionDetails() {
            val nextValue = !sensitiveSessionDetailsVisible.value
            sensitiveSessionDetailsVisible.value = nextValue
            val sessionId = selectedSessionDetail.value?.session?.id ?: return
            viewModelScope.launch {
                val detail = diagnosticsManager.loadSessionDetail(sessionId)
                selectedSessionDetail.value = detail.toUiModel(showSensitiveDetails = nextValue)
            }
        }

        fun setSessionPathModeFilter(pathMode: String?) {
            sessionPathModeFilter.value = toggleValue(sessionPathModeFilter.value, pathMode)
        }

        fun setSessionStatusFilter(status: String?) {
            sessionStatusFilter.value = toggleValue(sessionStatusFilter.value, status)
        }

        fun setSessionSearch(query: String) {
            sessionSearch.value = query
        }

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) {
            if (source != null) {
                eventSourceFilter.value = toggleValue(eventSourceFilter.value, source)
            }
            if (severity != null) {
                eventSeverityFilter.value = toggleValue(eventSeverityFilter.value, severity)
            }
        }

        fun setEventSearch(query: String) {
            eventSearch.value = query
        }

        fun setEventAutoScroll(enabled: Boolean) {
            eventAutoScroll.value = enabled
        }

        fun startRawScan() {
            viewModelScope.launch {
                diagnosticsManager.startScan(ScanPathMode.RAW_PATH)
            }
        }

        fun startInPathScan() {
            viewModelScope.launch {
                diagnosticsManager.startScan(ScanPathMode.IN_PATH)
            }
        }

        fun cancelScan() {
            viewModelScope.launch {
                diagnosticsManager.cancelActiveScan()
            }
        }

        fun shareSummary(sessionId: String? = null) {
            viewModelScope.launch {
                val summary = diagnosticsManager.buildShareSummary(sessionId ?: uiState.value.share.targetSessionId)
                _effects.send(
                    DiagnosticsEffect.ShareSummaryRequested(
                        title = summary.title,
                        body = summary.body,
                    ),
                )
            }
        }

        fun shareArchive(sessionId: String? = null) {
            viewModelScope.launch {
                runArchiveAction(
                    busyMessage = "Generating archive for sharing",
                    successMessage = "Archive ready to share",
                    failureMessage = "Failed to generate archive",
                ) { targetSessionId ->
                    val archive = diagnosticsManager.createArchive(targetSessionId)
                    _effects.send(
                        DiagnosticsEffect.ShareArchiveRequested(
                            absolutePath = archive.absolutePath,
                            fileName = archive.fileName,
                        ),
                    )
                    archive
                }
            }
        }

        fun saveArchive(sessionId: String? = null) {
            viewModelScope.launch {
                runArchiveAction(
                    busyMessage = "Preparing archive for saving",
                    successMessage = "Archive saved to export flow",
                    failureMessage = "Failed to prepare archive",
                ) { targetSessionId ->
                    val archive = diagnosticsManager.createArchive(targetSessionId)
                    _effects.send(
                        DiagnosticsEffect.SaveArchiveRequested(
                            absolutePath = archive.absolutePath,
                            fileName = archive.fileName,
                        ),
                    )
                    archive
                }
            }
        }

        private fun buildUiState(
            profiles: List<DiagnosticProfileEntity>,
            settings: com.poyka.ripdpi.proto.AppSettings,
            progress: ScanProgress?,
            sessions: List<ScanSessionEntity>,
            approachStats: List<BypassApproachSummary>,
            snapshots: List<NetworkSnapshotEntity>,
            contexts: List<DiagnosticContextEntity>,
            telemetry: List<TelemetrySampleEntity>,
            nativeEvents: List<NativeSessionEventEntity>,
            exports: List<ExportRecordEntity>,
            selectedSectionRequest: DiagnosticsSection,
            selectedProfileId: String?,
            selectedApproachMode: DiagnosticsApproachMode,
            selectedProbe: DiagnosticsProbeResultUiModel?,
            selectedEventId: String?,
            sessionPathMode: String?,
            sessionStatus: String?,
            sessionSearch: String,
            eventSource: String?,
            eventSeverity: String?,
            eventSearch: String,
            eventAutoScroll: Boolean,
            selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
            selectedApproachDetail: DiagnosticsApproachDetailUiModel?,
            sensitiveSessionDetailsVisible: Boolean,
            archiveActionState: ArchiveActionState,
        ): DiagnosticsUiState {
            val activeProfile =
                profiles.firstOrNull { it.id == selectedProfileId }
                    ?: profiles.firstOrNull()
            val activeProfileRequest = activeProfile?.decodeRequest()
            val latestSnapshot = snapshots.firstOrNull()?.toUiModel(showSensitiveDetails = false)
            val latestContext = (contexts.firstOrNull { it.sessionId == null } ?: contexts.firstOrNull())?.decodeContext()
            val eventModels = nativeEvents.map { it.toUiModel() }
            val sessionRows = sessions.map(::toSessionRowUiModel)
            val selectedProfileUi = activeProfile?.toOptionUiModel()
            val latestCompletedSession = sessions.firstOrNull { it.reportJson != null } ?: sessions.firstOrNull()
            val latestProfileSession =
                sessions.firstOrNull { it.profileId == activeProfile?.id && it.reportJson != null }
                    ?: sessions.firstOrNull { it.profileId == activeProfile?.id }
                    ?: latestCompletedSession
            val latestProfileReport = latestProfileSession?.reportJson?.let(::decodeReport)
            val latestReport = latestCompletedSession?.reportJson?.let(::decodeReport)
            val latestReportResults = latestProfileReport?.results?.mapIndexed(::toProbeResultUiModel).orEmpty()
            val latestStrategyProbeReport = latestProfileReport?.strategyProbeReport?.toUiModel()
            val currentTelemetry = telemetry.firstOrNull()
            val health = deriveHealth(progress, latestCompletedSession, currentTelemetry, nativeEvents)
            val strategyProbeSelected = activeProfileRequest?.kind == ScanKind.STRATEGY_PROBE
            val rawArgsEnabled = settings.enableCmdSettings
            val runRawEnabled = progress == null && !(strategyProbeSelected && rawArgsEnabled)
            val runInPathEnabled = progress == null && !strategyProbeSelected
            val runRawHint =
                when {
                    strategyProbeSelected && rawArgsEnabled -> "Automatic probing only works with visual RIPDPI settings. Command-line mode is active."
                    strategyProbeSelected -> "Automatic probing starts a temporary raw-path RIPDPI runtime and returns a manual recommendation."
                    else -> null
                }
            val runInPathHint =
                when {
                    strategyProbeSelected -> "Automatic probing is raw-path only because it launches isolated temporary strategy trials."
                    else -> null
                }
            val selectedSection =
                if (progress != null) {
                    DiagnosticsSection.Scan
                } else {
                    selectedSectionRequest
                }
            val filteredSessions =
                sessionRows.filter { session ->
                    (sessionPathMode == null || session.pathMode == sessionPathMode) &&
                        (sessionStatus == null || session.status.equals(sessionStatus, ignoreCase = true)) &&
                        session.matchesQuery(sessionSearch)
                }
            val filteredEvents =
                eventModels.filter { event ->
                    (eventSource == null || event.source.equals(eventSource, ignoreCase = true)) &&
                        (eventSeverity == null || event.severity.equals(eventSeverity, ignoreCase = true)) &&
                        event.matchesQuery(eventSearch)
                }
            val selectedEvent = filteredEvents.firstOrNull { it.id == selectedEventId }
            val sharePreview = buildSharePreview(latestCompletedSession, latestSnapshot, latestContext, currentTelemetry, nativeEvents, latestReport)
            val liveMetrics = buildLiveMetrics(currentTelemetry, nativeEvents)
            val liveTrends = buildLiveTrends(telemetry)
            val liveHighlights = buildLiveHighlights(currentTelemetry, nativeEvents)
            val selectedApproachKind =
                when (selectedApproachMode) {
                    DiagnosticsApproachMode.Profiles -> BypassApproachKind.Profile
                    DiagnosticsApproachMode.Strategies -> BypassApproachKind.Strategy
                }
            val filteredApproaches =
                approachStats
                    .filter { it.approachId.kind == selectedApproachKind }
                    .map { it.toApproachRowUiModel(selectedApproachMode) }
            val warnings =
                (buildContextWarnings(latestContext) + eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning })
                    .take(3)
            val overviewContext = latestContext?.toOverviewContextGroup()
            val liveContextGroups = latestContext?.toLiveContextGroups() ?: emptyList()
            val sessionDetailWithVisibility =
                selectedSessionDetail?.copy(
                    sensitiveDetailsVisible = sensitiveSessionDetailsVisible,
                )

            return DiagnosticsUiState(
                selectedSection = selectedSection,
                overview =
                    DiagnosticsOverviewUiModel(
                        health = health,
                        headline = overviewHeadline(health, progress, latestCompletedSession),
                        body = overviewBody(health, latestSnapshot, currentTelemetry),
                        activeProfile = selectedProfileUi,
                        latestSnapshot = latestSnapshot,
                        latestSession = sessionRows.firstOrNull(),
                        contextSummary = overviewContext,
                        metrics =
                            buildList {
                                add(
                                    DiagnosticsMetricUiModel(
                                        label = "Sessions",
                                        value = sessions.size.toString(),
                                    ),
                                )
                                add(
                                    DiagnosticsMetricUiModel(
                                        label = "Events",
                                        value = nativeEvents.size.toString(),
                                        tone =
                                            when (health) {
                                                DiagnosticsHealth.Degraded -> DiagnosticsTone.Negative
                                                DiagnosticsHealth.Attention -> DiagnosticsTone.Warning
                                                DiagnosticsHealth.Healthy -> DiagnosticsTone.Positive
                                                DiagnosticsHealth.Idle -> DiagnosticsTone.Neutral
                                            },
                                    ),
                                )
                                currentTelemetry?.let { sample ->
                                    add(
                                        DiagnosticsMetricUiModel(
                                            label = "TX",
                                            value = formatBytes(sample.txBytes),
                                            tone = DiagnosticsTone.Info,
                                        ),
                                    )
                                    add(
                                        DiagnosticsMetricUiModel(
                                            label = "RX",
                                            value = formatBytes(sample.rxBytes),
                                            tone = DiagnosticsTone.Info,
                                        ),
                                    )
                                }
                            },
                        warnings = warnings,
                    ),
                scan =
                    DiagnosticsScanUiModel(
                        profiles = profiles.map { it.toOptionUiModel() },
                        selectedProfileId = activeProfile?.id,
                        selectedProfile = selectedProfileUi,
                        activePathMode = latestProfileSession?.pathMode?.let(::parsePathMode) ?: ScanPathMode.RAW_PATH,
                        activeProgress = progress?.toUiModel(),
                        latestSession = latestProfileSession?.let(::toSessionRowUiModel),
                        latestResults = latestReportResults,
                        selectedProfileScopeLabel = activeProfileRequest.toScopeLabel(rawArgsEnabled = rawArgsEnabled),
                        runRawEnabled = runRawEnabled,
                        runInPathEnabled = runInPathEnabled,
                        runRawHint = runRawHint,
                        runInPathHint = runInPathHint,
                        strategyProbeReport = latestStrategyProbeReport,
                        isBusy = progress != null,
                    ),
                live =
                    DiagnosticsLiveUiModel(
                        statusLabel = currentTelemetry?.connectionState ?: "Idle",
                        freshnessLabel =
                            currentTelemetry?.createdAt?.let { "Updated ${formatTimestamp(it)}" }
                                ?: "No live telemetry",
                        headline = buildLiveHeadline(health, currentTelemetry, nativeEvents),
                        body = buildLiveBody(currentTelemetry, nativeEvents),
                        networkLabel = currentTelemetry?.networkType,
                        modeLabel = currentTelemetry?.activeMode,
                        signalLabel = buildLiveSignalLabel(currentTelemetry),
                        eventSummaryLabel = buildLiveEventSummaryLabel(nativeEvents),
                        highlights = liveHighlights,
                        metrics = liveMetrics,
                        trends = liveTrends,
                        snapshot = latestSnapshot,
                        contextGroups = liveContextGroups,
                        passiveEvents = eventModels.take(8),
                    ),
                sessions =
                    DiagnosticsSessionsUiModel(
                        filters =
                            DiagnosticsSessionFiltersUiModel(
                                pathMode = sessionPathMode,
                                status = sessionStatus,
                                query = sessionSearch,
                            ),
                        sessions = filteredSessions,
                        pathModes = sessions.map { it.pathMode }.distinct(),
                        statuses = sessions.map { it.status }.distinct(),
                        focusedSessionId = selectedSessionDetail?.session?.id,
                    ),
                approaches =
                    DiagnosticsApproachesUiModel(
                        selectedMode = selectedApproachMode,
                        rows = filteredApproaches,
                        focusedApproachId = selectedApproachDetail?.approach?.id,
                    ),
                events =
                    DiagnosticsEventsUiModel(
                        filters =
                            DiagnosticsEventFiltersUiModel(
                                source = eventSource,
                                severity = eventSeverity,
                                search = eventSearch,
                                autoScroll = eventAutoScroll,
                            ),
                        events = filteredEvents,
                        availableSources = eventModels.map { it.source }.distinct(),
                        availableSeverities = eventModels.map { it.severity }.distinct(),
                        focusedEventId = selectedEvent?.id,
                    ),
                share =
                    DiagnosticsShareUiModel(
                        targetSessionId = selectedSessionDetail?.session?.id ?: latestCompletedSession?.id,
                        previewTitle = sharePreview.title,
                        previewBody =
                            buildString {
                                append(sharePreview.body)
                                approachStats
                                    .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                                    ?.let { summary ->
                                        append("\n\nArchive includes approach analytics for ")
                                        append(summary.displayName)
                                        append(" with ")
                                        append(summary.verificationState)
                                        append(" validation and runtime health context.")
                                    }
                            },
                        metrics =
                            sharePreview.compactMetrics.map { DiagnosticsMetricUiModel(it.label, it.value) } +
                                listOfNotNull(
                                    approachStats
                                        .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                                        ?.let { summary ->
                                            DiagnosticsMetricUiModel(
                                                label = "Approach",
                                                value = summary.displayName,
                                                tone = summary.toTone(),
                                            )
                                        },
                                ),
                        latestArchiveFileName = archiveActionState.latestArchiveFileName ?: exports.firstOrNull()?.fileName,
                        archiveStateMessage = archiveActionState.message,
                        archiveStateTone = archiveActionState.tone,
                        isArchiveBusy = archiveActionState.isBusy,
                    ),
                selectedSessionDetail = sessionDetailWithVisibility,
                selectedApproachDetail = selectedApproachDetail,
                selectedEvent = selectedEvent,
                selectedProbe = selectedProbe,
            )
        }

        private fun overviewHeadline(
            health: DiagnosticsHealth,
            progress: ScanProgress?,
            latestSession: ScanSessionEntity?,
        ): String =
            when {
                progress != null -> "Diagnostics scan is active"
                latestSession == null -> "No diagnostics captured yet"
                health == DiagnosticsHealth.Degraded -> "The network path needs attention"
                health == DiagnosticsHealth.Attention -> "Diagnostics are reporting mixed signals"
                health == DiagnosticsHealth.Healthy -> "Current telemetry looks stable"
                else -> "Waiting for the next diagnostics session"
            }

        private fun overviewBody(
            health: DiagnosticsHealth,
            latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
            telemetry: TelemetrySampleEntity?,
        ): String =
            when (health) {
                DiagnosticsHealth.Healthy ->
                    "Passive telemetry and the latest report do not show critical failures. Review live counters for drift."
                DiagnosticsHealth.Attention ->
                    "Warnings or partial failures were detected. Compare the latest scan outcome with the current live monitor."
                DiagnosticsHealth.Degraded ->
                    "Recent events or scan outcomes indicate blocking, failures, or transport instability."
                DiagnosticsHealth.Idle ->
                    latestSnapshot?.subtitle
                        ?: telemetry?.connectionState
                        ?: "Start RIPDPI or run a scan to populate diagnostics analytics."
            }

        private fun deriveHealth(
            progress: ScanProgress?,
            latestSession: ScanSessionEntity?,
            latestTelemetry: TelemetrySampleEntity?,
            nativeEvents: List<NativeSessionEventEntity>,
        ): DiagnosticsHealth {
            if (progress != null) {
                return DiagnosticsHealth.Attention
            }
            if (latestSession == null && latestTelemetry == null && nativeEvents.isEmpty()) {
                return DiagnosticsHealth.Idle
            }
            if (nativeEvents.any { it.level.equals("error", ignoreCase = true) }) {
                return DiagnosticsHealth.Degraded
            }
            if (latestSession?.status?.contains("failed", ignoreCase = true) == true) {
                return DiagnosticsHealth.Degraded
            }
            if (nativeEvents.any { it.level.equals("warn", ignoreCase = true) }) {
                return DiagnosticsHealth.Attention
            }
            return DiagnosticsHealth.Healthy
        }

        private fun buildLiveMetrics(
            telemetry: TelemetrySampleEntity?,
            events: List<NativeSessionEventEntity>,
        ): List<DiagnosticsMetricUiModel> =
            buildList {
                if (telemetry != null) {
                    add(DiagnosticsMetricUiModel(label = "Network", value = telemetry.networkType))
                    add(DiagnosticsMetricUiModel(label = "Mode", value = telemetry.activeMode ?: "Idle"))
                    add(DiagnosticsMetricUiModel(label = "TX packets", value = telemetry.txPackets.toString(), tone = DiagnosticsTone.Info))
                    add(DiagnosticsMetricUiModel(label = "RX packets", value = telemetry.rxPackets.toString(), tone = DiagnosticsTone.Info))
                }
                add(
                    DiagnosticsMetricUiModel(
                        label = "Warnings",
                        value = events.count { it.level.equals("warn", ignoreCase = true) }.toString(),
                        tone = DiagnosticsTone.Warning,
                    ),
                )
                add(
                    DiagnosticsMetricUiModel(
                        label = "Errors",
                        value = events.count { it.level.equals("error", ignoreCase = true) }.toString(),
                        tone = DiagnosticsTone.Negative,
                    ),
                )
            }

        private fun buildLiveHighlights(
            telemetry: TelemetrySampleEntity?,
            events: List<NativeSessionEventEntity>,
        ): List<DiagnosticsMetricUiModel> {
            val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
            val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
            return buildList {
                telemetry?.let {
                    add(DiagnosticsMetricUiModel(label = "TX", value = formatBytes(it.txBytes), tone = DiagnosticsTone.Info))
                    add(DiagnosticsMetricUiModel(label = "RX", value = formatBytes(it.rxBytes), tone = DiagnosticsTone.Positive))
                    add(
                        DiagnosticsMetricUiModel(
                            label = "Packets",
                            value = (it.txPackets + it.rxPackets).toString(),
                            tone = DiagnosticsTone.Neutral,
                        ),
                    )
                }
                add(
                    DiagnosticsMetricUiModel(
                        label = "Warnings",
                        value = warningCount.toString(),
                        tone = if (warningCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                    ),
                )
                add(
                    DiagnosticsMetricUiModel(
                        label = "Errors",
                        value = errorCount.toString(),
                        tone = if (errorCount > 0) DiagnosticsTone.Negative else DiagnosticsTone.Neutral,
                    ),
                )
            }
        }

        private fun buildLiveTrends(telemetry: List<TelemetrySampleEntity>): List<DiagnosticsSparklineUiModel> {
            val samples = telemetry.take(24).reversed()
            if (samples.isEmpty()) {
                return emptyList()
            }
            return listOf(
                DiagnosticsSparklineUiModel(
                    label = "TX bytes",
                    values = samples.map { it.txBytes.toFloat() },
                    tone = DiagnosticsTone.Info,
                ),
                DiagnosticsSparklineUiModel(
                    label = "RX bytes",
                    values = samples.map { it.rxBytes.toFloat() },
                    tone = DiagnosticsTone.Positive,
                ),
                DiagnosticsSparklineUiModel(
                    label = "Errors",
                    values = samples.map { sample ->
                        if (sample.connectionState.equals("running", ignoreCase = true)) {
                            0f
                        } else {
                            1f
                        }
                    },
                    tone = DiagnosticsTone.Warning,
                ),
            )
        }

        private fun buildLiveHeadline(
            health: DiagnosticsHealth,
            telemetry: TelemetrySampleEntity?,
            events: List<NativeSessionEventEntity>,
        ): String {
            val surfacedEvent =
                events.firstOrNull { it.level.equals("error", ignoreCase = true) }
                    ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
            return when {
                surfacedEvent?.level?.equals("error", ignoreCase = true) == true -> "Runtime needs intervention"
                health == DiagnosticsHealth.Attention -> "Runtime requires a closer look"
                telemetry == null -> "Live monitor standing by"
                telemetry.connectionState.equals("running", ignoreCase = true) -> "Traffic is moving through ${telemetry.networkType}"
                else -> telemetry.connectionState.replaceFirstChar { it.uppercase() }
            }
        }

        private fun buildLiveBody(
            telemetry: TelemetrySampleEntity?,
            events: List<NativeSessionEventEntity>,
        ): String {
            val surfacedEvent =
                events.firstOrNull { it.level.equals("error", ignoreCase = true) }
                    ?: events.firstOrNull { it.level.equals("warn", ignoreCase = true) }
            if (surfacedEvent != null) {
                return surfacedEvent.message
            }
            telemetry ?: return "Continuous monitor is waiting for an active RIPDPI session."
            val totalBytes = formatBytes(telemetry.txBytes + telemetry.rxBytes)
            val packetCount = telemetry.txPackets + telemetry.rxPackets
            val modeLabel = telemetry.activeMode ?: "Idle"
            return "$modeLabel mode · $totalBytes transferred · $packetCount packets observed"
        }

        private fun buildLiveSignalLabel(telemetry: TelemetrySampleEntity?): String =
            telemetry?.let { "${formatBytes(it.txBytes)} sent · ${formatBytes(it.rxBytes)} received" }
                ?: "No transfer observed yet"

        private fun buildLiveEventSummaryLabel(events: List<NativeSessionEventEntity>): String {
            val warningCount = events.count { it.level.equals("warn", ignoreCase = true) }
            val errorCount = events.count { it.level.equals("error", ignoreCase = true) }
            return when {
                errorCount > 0 && warningCount > 0 ->
                    "$errorCount error${pluralSuffix(errorCount)} · $warningCount warning${pluralSuffix(warningCount)} in runtime feed"

                errorCount > 0 ->
                    "$errorCount error${pluralSuffix(errorCount)} in runtime feed"

                warningCount > 0 ->
                    "$warningCount warning${pluralSuffix(warningCount)} in runtime feed"

                events.isNotEmpty() ->
                    "${events.size} informational event${pluralSuffix(events.size)} in runtime feed"

                else -> "Runtime feed is quiet"
            }
        }

        private fun pluralSuffix(count: Int): String = if (count == 1) "" else "s"

        private fun buildSharePreview(
            latestSession: ScanSessionEntity?,
            latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
            latestContext: DiagnosticContextModel?,
            telemetry: TelemetrySampleEntity?,
            nativeEvents: List<NativeSessionEventEntity>,
            latestReport: ScanReport?,
        ): ShareSummary {
            val warningHeadline =
                nativeEvents.firstOrNull {
                    it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
                }
            val body =
                buildString {
                    appendLine("Archive includes the focused diagnostics session in full.")
                    appendLine("It also adds recent live telemetry and global runtime events.")
                    appendLine("Summary and manifest redact support context and network identity fields, while raw report data stays intact.")
                    latestSession?.let {
                        appendLine("Session ${it.id.take(8)} · ${it.pathMode} · ${it.status}")
                    }
                    latestSnapshot?.let {
                        appendLine("Network ${it.subtitle}")
                    }
                    latestContext?.let {
                        appendLine("Context ${it.service.activeMode.lowercase(Locale.US)} · ${it.device.manufacturer} ${it.device.model} · Android ${it.device.androidVersion}")
                        appendLine("Permissions ${it.permissions.vpnPermissionState} VPN · ${it.permissions.notificationPermissionState} notifications")
                    }
                    telemetry?.let {
                        appendLine("Live ${it.connectionState.lowercase(Locale.US)} · ${it.networkType}")
                    }
                    latestReport?.let {
                        appendLine("${it.results.size} probe results in the latest report")
                    }
                    warningHeadline?.let {
                        appendLine("Top warning: ${it.message}")
                    }
                }.trim()
            return ShareSummary(
                title = "RIPDPI diagnostics",
                body = body.ifBlank { "Select a diagnostics session to generate a summary." },
                compactMetrics =
                    listOfNotNull(
                        latestSession?.pathMode?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("Path", it) },
                        telemetry?.networkType?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("Network", it) },
                        latestContext?.service?.activeMode?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("Mode", it) },
                        latestContext?.device?.appVersionName?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("App", it) },
                        telemetry?.txBytes?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("TX", formatBytes(it)) },
                        telemetry?.rxBytes?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("RX", formatBytes(it)) },
                    ),
            )
        }

        private fun decodeReport(reportJson: String): ScanReport? =
            runCatching { json.decodeFromString(ScanReport.serializer(), reportJson) }.getOrNull()

        private fun DiagnosticProfileEntity.decodeRequest(): ScanRequest? =
            runCatching { json.decodeFromString(ScanRequest.serializer(), requestJson) }.getOrNull()

        private fun decodeProbeDetails(detailJson: String): List<ProbeDetail> =
            runCatching { json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson) }.getOrElse { emptyList() }

        private fun NetworkSnapshotEntity.toUiModel(showSensitiveDetails: Boolean): DiagnosticsNetworkSnapshotUiModel? {
            val snapshot = runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payloadJson) }.getOrNull() ?: return null
            return DiagnosticsNetworkSnapshotUiModel(
                title = snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
                subtitle = "${snapshot.transport} · ${formatTimestamp(snapshot.capturedAt)}",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("Capabilities", snapshot.capabilities.joinToString().ifBlank { "Unknown" }),
                        DiagnosticsFieldUiModel("DNS", if (showSensitiveDetails) snapshot.dnsServers.joinToString().ifBlank { "Unknown" } else redactCollection(snapshot.dnsServers)),
                        DiagnosticsFieldUiModel("Private DNS", snapshot.privateDnsMode),
                        DiagnosticsFieldUiModel("MTU", snapshot.mtu?.toString() ?: "Unknown"),
                        DiagnosticsFieldUiModel("Local", if (showSensitiveDetails) snapshot.localAddresses.joinToString().ifBlank { "Unknown" } else redactCollection(snapshot.localAddresses)),
                        DiagnosticsFieldUiModel("Public IP", if (showSensitiveDetails) snapshot.publicIp ?: "Unknown" else redactValue(snapshot.publicIp)),
                        DiagnosticsFieldUiModel("ASN", snapshot.publicAsn ?: "Unknown"),
                        DiagnosticsFieldUiModel("Validated", snapshot.networkValidated.toString()),
                        DiagnosticsFieldUiModel("Captive portal", snapshot.captivePortalDetected.toString()),
                    ) + transportSpecificFields(snapshot, showSensitiveDetails),
            )
        }

        private fun DiagnosticProfileEntity.toOptionUiModel(): DiagnosticsProfileOptionUiModel =
            DiagnosticsProfileOptionUiModel(
                id = id,
                name = name,
                source = source,
                kind = decodeRequest()?.kind ?: ScanKind.CONNECTIVITY,
            )

        private fun toSessionRowUiModel(session: ScanSessionEntity): DiagnosticsSessionRowUiModel {
            val report = session.reportJson?.let(::decodeReport)
            val metrics =
                buildList {
                    add(DiagnosticsMetricUiModel(label = "Path", value = session.pathMode))
                    add(DiagnosticsMetricUiModel(label = "Mode", value = session.serviceMode ?: "Unknown"))
                    report?.results?.size?.let {
                        add(DiagnosticsMetricUiModel(label = "Probes", value = it.toString()))
                    }
                }
            return DiagnosticsSessionRowUiModel(
                id = session.id,
                profileId = session.profileId,
                title = session.summary,
                subtitle = "${session.pathMode} · ${session.serviceMode ?: "Unknown"} · ${formatTimestamp(session.startedAt)}",
                pathMode = session.pathMode,
                serviceMode = session.serviceMode ?: "Unknown",
                status = session.status,
                startedAtLabel = formatTimestamp(session.startedAt),
                summary = session.summary,
                metrics = metrics,
                tone = toneForOutcome(session.status),
            )
        }

        private fun BypassApproachSummary.toApproachRowUiModel(mode: DiagnosticsApproachMode): DiagnosticsApproachRowUiModel =
            DiagnosticsApproachRowUiModel(
                id = approachId.value,
                kind = mode,
                title = displayName,
                subtitle = secondaryLabel,
                verificationState = verificationState.replaceFirstChar { it.uppercase() },
                lastValidatedResult = lastValidatedResult ?: "Unverified",
                dominantFailurePattern = topFailureOutcomes.firstOrNull() ?: "No dominant failure recorded",
                metrics =
                    buildList {
                        add(
                            DiagnosticsMetricUiModel(
                                label = "Validated",
                                value = validatedScanCount.toString(),
                                tone = if (validatedScanCount > 0) DiagnosticsTone.Info else DiagnosticsTone.Neutral,
                            ),
                        )
                        add(
                            DiagnosticsMetricUiModel(
                                label = "Success",
                                value = validatedSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "Unverified",
                                tone = toTone(),
                            ),
                        )
                        add(DiagnosticsMetricUiModel(label = "Usage", value = usageCount.toString(), tone = DiagnosticsTone.Info))
                        add(
                            DiagnosticsMetricUiModel(
                                label = "Runtime",
                                value = formatDurationMs(totalRuntimeDurationMs),
                                tone = DiagnosticsTone.Neutral,
                            ),
                        )
                    },
                tone = toTone(),
            )

        private fun strategySignatureFields(signature: BypassStrategySignature): List<DiagnosticsFieldUiModel> =
            buildList {
                add(DiagnosticsFieldUiModel("Mode", signature.mode))
                add(DiagnosticsFieldUiModel("Config source", signature.configSource))
                add(DiagnosticsFieldUiModel("Autolearn", signature.hostAutolearn))
                add(DiagnosticsFieldUiModel("Chain", signature.chainSummary))
                add(DiagnosticsFieldUiModel("Desync", signature.desyncMethod))
                add(DiagnosticsFieldUiModel("Protocols", signature.protocolToggles.joinToString("/")))
                add(DiagnosticsFieldUiModel("TLS record split", signature.tlsRecordSplitEnabled.toString()))
                signature.tlsRecordMarker?.let {
                    add(DiagnosticsFieldUiModel("TLS record marker", it))
                }
                signature.splitMarker?.let {
                    add(DiagnosticsFieldUiModel("Split marker", it))
                }
                signature.activationRound?.let {
                    add(DiagnosticsFieldUiModel("Activation round", it))
                }
                signature.activationPayloadSize?.let {
                    add(DiagnosticsFieldUiModel("Activation payload size", it))
                }
                signature.activationStreamBytes?.let {
                    add(DiagnosticsFieldUiModel("Activation stream bytes", it))
                }
                signature.fakeTlsBaseMode?.let {
                    add(DiagnosticsFieldUiModel("Fake TLS base", formatFakeTlsBaseMode(it)))
                }
                signature.fakeSniMode?.let {
                    add(
                        DiagnosticsFieldUiModel(
                            "Fake TLS SNI",
                            formatFakeTlsSni(
                                mode = it,
                                fixedValue = signature.fakeSniValue,
                            ),
                        ),
                    )
                }
                if (signature.fakeTlsMods.isNotEmpty()) {
                    add(DiagnosticsFieldUiModel("Fake TLS mods", formatFakeTlsMods(signature.fakeTlsMods)))
                }
                signature.fakeTlsSize?.let {
                    add(DiagnosticsFieldUiModel("Fake TLS size", formatFakeTlsSize(it)))
                }
                signature.httpFakeProfile?.let {
                    add(DiagnosticsFieldUiModel("HTTP fake profile", formatHttpFakeProfile(it)))
                }
                signature.tlsFakeProfile?.let {
                    add(DiagnosticsFieldUiModel("TLS fake profile", formatTlsFakeProfile(it)))
                }
                signature.udpFakeProfile?.let {
                    add(DiagnosticsFieldUiModel("UDP fake profile", formatUdpFakeProfile(it)))
                }
                signature.fakePayloadSource?.let {
                    add(DiagnosticsFieldUiModel("Fake payload source", formatFakePayloadSource(it)))
                }
                signature.quicFakeProfile?.let {
                    add(DiagnosticsFieldUiModel("QUIC fake profile", formatQuicFakeProfile(it)))
                }
                signature.quicFakeHost?.let {
                    add(DiagnosticsFieldUiModel("QUIC fake host", it))
                }
                signature.fakeOffsetMarker?.let {
                    add(DiagnosticsFieldUiModel("Fake offset marker", it))
                }
                add(DiagnosticsFieldUiModel("Route group", signature.routeGroup ?: "Unknown"))
            }

        private fun StrategyProbeCandidateSummary.toUiModel(recommended: Boolean): DiagnosticsStrategyProbeCandidateUiModel =
            DiagnosticsStrategyProbeCandidateUiModel(
                id = id,
                label = label,
                outcome = outcome.replaceFirstChar { it.uppercase() },
                rationale = rationale,
                metrics =
                    buildList {
                        add(DiagnosticsMetricUiModel("Targets", "$succeededTargets/$totalTargets"))
                        add(DiagnosticsMetricUiModel("Weight", "$weightedSuccessScore/$totalWeight"))
                        add(
                            DiagnosticsMetricUiModel(
                                "Quality",
                                qualityScore.toString(),
                                tone =
                                    when {
                                        qualityScore >= totalTargets.coerceAtLeast(1) * 3 -> DiagnosticsTone.Positive
                                        qualityScore > 0 -> DiagnosticsTone.Warning
                                        else -> DiagnosticsTone.Neutral
                                    },
                            ),
                        )
                        averageLatencyMs?.let {
                            add(DiagnosticsMetricUiModel("Latency", "${it} ms", DiagnosticsTone.Info))
                        }
                    },
                tone =
                    when {
                        recommended -> DiagnosticsTone.Positive
                        skipped -> DiagnosticsTone.Neutral
                        outcome.equals("success", ignoreCase = true) -> DiagnosticsTone.Positive
                        outcome.equals("partial", ignoreCase = true) -> DiagnosticsTone.Warning
                        else -> DiagnosticsTone.Negative
                    },
                skipped = skipped,
                recommended = recommended,
            )

        private fun StrategyProbeRecommendation.toUiModel(): DiagnosticsStrategyProbeRecommendationUiModel =
            DiagnosticsStrategyProbeRecommendationUiModel(
                headline = "$tcpCandidateLabel + $quicCandidateLabel",
                rationale = rationale,
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("TCP recommendation", tcpCandidateLabel),
                        DiagnosticsFieldUiModel("QUIC recommendation", quicCandidateLabel),
                        DiagnosticsFieldUiModel("Why it won", rationale),
                    ),
                signature = strategySignature?.let(::strategySignatureFields).orEmpty(),
            )

        private fun StrategyProbeReport.toUiModel(): DiagnosticsStrategyProbeReportUiModel {
            fun mapFamily(
                title: String,
                candidates: List<StrategyProbeCandidateSummary>,
                recommendedId: String,
            ): DiagnosticsStrategyProbeFamilyUiModel =
                DiagnosticsStrategyProbeFamilyUiModel(
                    title = title,
                    candidates =
                        candidates
                            .map { candidate ->
                                candidate.toUiModel(recommended = candidate.id == recommendedId)
                            }.sortedWith(
                                compareByDescending<DiagnosticsStrategyProbeCandidateUiModel> { it.recommended }
                                    .thenBy { it.skipped }
                                    .thenBy { it.label },
                            ),
                )

            return DiagnosticsStrategyProbeReportUiModel(
                suiteLabel = suiteId.replace('_', ' ').replaceFirstChar { it.uppercase() },
                recommendation = recommendation.toUiModel(),
                families =
                    listOf(
                        mapFamily(
                            title = "TCP candidates",
                            candidates = tcpCandidates,
                            recommendedId = recommendation.tcpCandidateId,
                        ),
                        mapFamily(
                            title = "QUIC candidates",
                            candidates = quicCandidates,
                            recommendedId = recommendation.quicCandidateId,
                        ),
                    ),
            )
        }

        private fun ScanRequest?.toScopeLabel(rawArgsEnabled: Boolean): String? =
            when (this?.kind) {
                ScanKind.STRATEGY_PROBE ->
                    if (rawArgsEnabled) {
                        "Automatic probing · raw-path only · blocked by command-line mode"
                    } else {
                        "Automatic probing · raw-path only"
                    }
                ScanKind.CONNECTIVITY -> "Connectivity profile"
                null -> null
                else -> null
            }

        private fun BypassApproachDetail.toUiModel(): DiagnosticsApproachDetailUiModel =
            DiagnosticsApproachDetailUiModel(
                approach =
                    summary.toApproachRowUiModel(
                        mode =
                            when (summary.approachId.kind) {
                                BypassApproachKind.Profile -> DiagnosticsApproachMode.Profiles
                                BypassApproachKind.Strategy -> DiagnosticsApproachMode.Strategies
                            },
                    ),
                signature =
                    buildList {
                        strategySignature?.let { addAll(strategySignatureFields(it)) }
                    },
                breakdown =
                    summary.outcomeBreakdown.map { breakdown ->
                        DiagnosticsMetricUiModel(
                            label = breakdown.probeType,
                            value = "${breakdown.successCount}/${breakdown.failureCount}",
                            tone =
                                when {
                                    breakdown.failureCount > 0 -> DiagnosticsTone.Warning
                                    breakdown.successCount > 0 -> DiagnosticsTone.Positive
                                    else -> DiagnosticsTone.Neutral
                                },
                        )
                    },
                runtimeSummary =
                    listOf(
                        DiagnosticsMetricUiModel("Usage", summary.usageCount.toString(), DiagnosticsTone.Info),
                        DiagnosticsMetricUiModel("Runtime", formatDurationMs(summary.totalRuntimeDurationMs), DiagnosticsTone.Info),
                        DiagnosticsMetricUiModel("Errors", summary.recentRuntimeHealth.totalErrors.toString(), DiagnosticsTone.Warning),
                        DiagnosticsMetricUiModel("Route changes", summary.recentRuntimeHealth.routeChanges.toString(), DiagnosticsTone.Info),
                    ),
                recentSessions = recentValidatedSessions.map(::toSessionRowUiModel),
                recentUsageNotes =
                    recentUsageSessions.map { usage ->
                        "${usage.serviceMode} · ${usage.networkType} · ${formatDurationMs((usage.finishedAt ?: usage.startedAt) - usage.startedAt)}"
                    },
                failureNotes = recentFailureNotes,
            )

        private fun DiagnosticSessionDetail.toUiModel(showSensitiveDetails: Boolean): DiagnosticsSessionDetailUiModel {
            val probeGroups =
                results
                    .mapIndexed { index, result -> result.toUiModel(index) }
                    .groupBy { it.probeType }
                    .map { (group, items) ->
                        DiagnosticsProbeGroupUiModel(
                            title = group,
                            items = items,
                        )
                    }
            return DiagnosticsSessionDetailUiModel(
                session = toSessionRowUiModel(session),
                probeGroups = probeGroups,
                snapshots = snapshots.mapNotNull { it.toUiModel(showSensitiveDetails = showSensitiveDetails) },
                events = events.map { it.toUiModel() },
                contextGroups = context?.toUiGroups(showSensitiveDetails = showSensitiveDetails).orEmpty(),
                strategyProbeReport = session.reportJson?.let(::decodeReport)?.strategyProbeReport?.toUiModel(),
                hasSensitiveDetails = true,
                sensitiveDetailsVisible = showSensitiveDetails,
            )
        }

        private fun ProbeResultEntity.toUiModel(index: Int): DiagnosticsProbeResultUiModel =
            DiagnosticsProbeResultUiModel(
                id = "$sessionId-$index-$probeType-$target",
                probeType = probeType,
                target = target,
                outcome = outcome,
                tone = toneForOutcome(outcome),
                details = decodeProbeDetails(detailJson).map { DiagnosticsFieldUiModel(it.key, it.value) },
            )

        private fun toProbeResultUiModel(
            index: Int,
            probeResult: com.poyka.ripdpi.diagnostics.ProbeResult,
        ): DiagnosticsProbeResultUiModel =
            DiagnosticsProbeResultUiModel(
                id = "report-$index-${probeResult.probeType}-${probeResult.target}",
                probeType = probeResult.probeType,
                target = probeResult.target,
                outcome = probeResult.outcome,
                tone = toneForOutcome(probeResult.outcome),
                details = probeResult.details.map { DiagnosticsFieldUiModel(it.key, it.value) },
            )

        private fun NativeSessionEventEntity.toUiModel(): DiagnosticsEventUiModel =
            DiagnosticsEventUiModel(
                id = id,
                source = source.replaceFirstChar { it.uppercase() },
                severity = level.uppercase(Locale.US),
                message = message,
                createdAtLabel = formatTimestamp(createdAt),
                tone = toneForOutcome(level),
            )

        private fun ScanProgress.toUiModel(): DiagnosticsProgressUiModel {
            val fraction =
                if (totalSteps <= 0) {
                    0f
                } else {
                    completedSteps.toFloat() / totalSteps.toFloat()
                }
            return DiagnosticsProgressUiModel(
                phase = phase,
                summary = message,
                completedSteps = completedSteps,
                totalSteps = totalSteps,
                fraction = fraction,
            )
        }

        private fun toneForOutcome(value: String): DiagnosticsTone {
            val normalized = value.lowercase(Locale.US)
            return when {
                normalized.contains("ok") || normalized.contains("success") || normalized.contains("completed") -> DiagnosticsTone.Positive
                normalized.contains("warn") || normalized.contains("timeout") || normalized.contains("partial") || normalized.contains("running") -> DiagnosticsTone.Warning
                normalized.contains("error") || normalized.contains("failed") || normalized.contains("blocked") || normalized.contains("reset") -> DiagnosticsTone.Negative
                normalized.contains("info") -> DiagnosticsTone.Info
                else -> DiagnosticsTone.Neutral
            }
        }

        private fun BypassApproachSummary.toTone(): DiagnosticsTone =
            when {
                verificationState.equals("unverified", ignoreCase = true) -> DiagnosticsTone.Neutral
                (validatedSuccessRate ?: 0f) >= 0.75f -> DiagnosticsTone.Positive
                (validatedSuccessRate ?: 0f) > 0f -> DiagnosticsTone.Warning
                else -> DiagnosticsTone.Negative
            }

        private fun parsePathMode(value: String): ScanPathMode =
            runCatching { ScanPathMode.valueOf(value) }.getOrDefault(ScanPathMode.RAW_PATH)

        private fun formatTimestamp(timestamp: Long): String = timestampFormatter.format(Date(timestamp))

        private fun formatBytes(bytes: Long): String =
            when {
                bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000f)
                bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
                bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000f)
                else -> "$bytes B"
            }

        private fun DiagnosticsSessionRowUiModel.matchesQuery(query: String): Boolean {
            if (query.isBlank()) {
                return true
            }
            val normalized = query.lowercase(Locale.US)
            return listOf(title, subtitle, summary, pathMode, serviceMode, status).any {
                it.lowercase(Locale.US).contains(normalized)
            }
        }

        private fun DiagnosticsEventUiModel.matchesQuery(query: String): Boolean {
            if (query.isBlank()) {
                return true
            }
            val normalized = query.lowercase(Locale.US)
            return listOf(source, severity, message).any { it.lowercase(Locale.US).contains(normalized) }
        }

        private fun DiagnosticContextEntity.decodeContext(): DiagnosticContextModel? =
            runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payloadJson) }.getOrNull()

        private fun DiagnosticContextEntity.toUiGroups(showSensitiveDetails: Boolean): List<DiagnosticsContextGroupUiModel> =
            decodeContext()?.toUiGroups(showSensitiveDetails).orEmpty()

        private fun DiagnosticContextModel.toOverviewContextGroup(): DiagnosticsContextGroupUiModel =
            DiagnosticsContextGroupUiModel(
                title = "Support context",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("App", device.appVersionName),
                        DiagnosticsFieldUiModel("Device", "${device.manufacturer} ${device.model}"),
                        DiagnosticsFieldUiModel("Android", "${device.androidVersion} (API ${device.apiLevel})"),
                        DiagnosticsFieldUiModel("Mode", service.activeMode),
                        DiagnosticsFieldUiModel("Profile", service.selectedProfileName),
                        DiagnosticsFieldUiModel("Host learning", buildHostAutolearnOverviewSummary(service)),
                        DiagnosticsFieldUiModel("Restrictions", listOf(permissions.dataSaverState, environment.powerSaveModeState).joinToString(" · ")),
                    ),
            )

        private fun DiagnosticContextModel.toLiveContextGroups(): List<DiagnosticsContextGroupUiModel> =
            listOf(
                DiagnosticsContextGroupUiModel(
                    title = "Service",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("Status", service.serviceStatus),
                            DiagnosticsFieldUiModel("Mode", service.activeMode),
                            DiagnosticsFieldUiModel("Profile", service.selectedProfileName),
                            DiagnosticsFieldUiModel("Uptime", service.sessionUptimeMs?.let(::formatDurationMs) ?: "Unknown"),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Environment",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("Data saver", permissions.dataSaverState),
                            DiagnosticsFieldUiModel("Power save", environment.powerSaveModeState),
                            DiagnosticsFieldUiModel("Metered", environment.networkMeteredState),
                            DiagnosticsFieldUiModel("Roaming", environment.roamingState),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Host learning",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("Enabled", formatAutolearnState(service.hostAutolearnEnabled)),
                            DiagnosticsFieldUiModel("Learned hosts", service.learnedHostCount.toString()),
                            DiagnosticsFieldUiModel("Penalized", service.penalizedHostCount.toString()),
                            DiagnosticsFieldUiModel("Last host", formatAutolearnHost(service.lastAutolearnHost)),
                            DiagnosticsFieldUiModel("Last group", formatAutolearnGroup(service.lastAutolearnGroup)),
                            DiagnosticsFieldUiModel("Last action", formatAutolearnAction(service.lastAutolearnAction)),
                        ),
                ),
            )

        private fun buildContextWarnings(context: DiagnosticContextModel?): List<DiagnosticsEventUiModel> {
            if (context == null) {
                return emptyList()
            }
            val warnings = mutableListOf<DiagnosticsEventUiModel>()
            if (context.permissions.vpnPermissionState == "disabled") {
                warnings +=
                    DiagnosticsEventUiModel(
                        id = "context-vpn-permission",
                        source = "Context",
                        severity = "WARN",
                        message = "VPN permission is not currently granted.",
                        createdAtLabel = "now",
                        tone = DiagnosticsTone.Warning,
                    )
            }
            if (context.permissions.notificationPermissionState == "disabled") {
                warnings +=
                    DiagnosticsEventUiModel(
                        id = "context-notification-permission",
                        source = "Context",
                        severity = "WARN",
                        message = "Notification permission is disabled, so service issues may be harder to notice.",
                        createdAtLabel = "now",
                        tone = DiagnosticsTone.Warning,
                    )
            }
            if (context.permissions.dataSaverState == "enabled" || context.environment.powerSaveModeState == "enabled") {
                warnings +=
                    DiagnosticsEventUiModel(
                        id = "context-power-restriction",
                        source = "Context",
                        severity = "WARN",
                        message = "Power or background restrictions may interfere with stable diagnostics.",
                        createdAtLabel = "now",
                        tone = DiagnosticsTone.Warning,
                    )
            }
            return warnings
        }

        private fun DiagnosticContextModel.toUiGroups(showSensitiveDetails: Boolean): List<DiagnosticsContextGroupUiModel> =
            listOf(
                DiagnosticsContextGroupUiModel(
                    title = "Service",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("Status", service.serviceStatus),
                            DiagnosticsFieldUiModel("Configured mode", service.configuredMode),
                            DiagnosticsFieldUiModel("Active mode", service.activeMode),
                            DiagnosticsFieldUiModel("Profile", service.selectedProfileName),
                            DiagnosticsFieldUiModel("Config source", service.configSource),
                            DiagnosticsFieldUiModel("Proxy", if (showSensitiveDetails) service.proxyEndpoint else redactValue(service.proxyEndpoint)),
                            DiagnosticsFieldUiModel("Chain", service.chainSummary),
                            DiagnosticsFieldUiModel("Desync", service.desyncMethod),
                            DiagnosticsFieldUiModel("Route group", service.routeGroup),
                            DiagnosticsFieldUiModel("Autolearn", formatAutolearnState(service.hostAutolearnEnabled)),
                            DiagnosticsFieldUiModel("Learned hosts", service.learnedHostCount.toString()),
                            DiagnosticsFieldUiModel("Penalized hosts", service.penalizedHostCount.toString()),
                            DiagnosticsFieldUiModel("Last learned host", formatAutolearnHost(service.lastAutolearnHost)),
                            DiagnosticsFieldUiModel("Last learned group", formatAutolearnGroup(service.lastAutolearnGroup)),
                            DiagnosticsFieldUiModel("Last autolearn action", formatAutolearnAction(service.lastAutolearnAction)),
                            DiagnosticsFieldUiModel("Restart count", service.restartCount.toString()),
                            DiagnosticsFieldUiModel("Uptime", service.sessionUptimeMs?.let(::formatDurationMs) ?: "Unknown"),
                            DiagnosticsFieldUiModel("Last native error", service.lastNativeErrorHeadline),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Permissions",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("VPN permission", permissions.vpnPermissionState),
                            DiagnosticsFieldUiModel("Notification permission", permissions.notificationPermissionState),
                            DiagnosticsFieldUiModel("Battery optimization", permissions.batteryOptimizationState),
                            DiagnosticsFieldUiModel("Data saver", permissions.dataSaverState),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Device",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("App version", "${device.appVersionName} (${device.buildType})"),
                            DiagnosticsFieldUiModel("Version code", device.appVersionCode.toString()),
                            DiagnosticsFieldUiModel("Device", "${device.manufacturer} ${device.model}"),
                            DiagnosticsFieldUiModel("Android", "${device.androidVersion} (API ${device.apiLevel})"),
                            DiagnosticsFieldUiModel("ABI", device.primaryAbi),
                            DiagnosticsFieldUiModel("Locale", device.locale),
                            DiagnosticsFieldUiModel("Timezone", device.timezone),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Environment",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("Battery saver", environment.batterySaverState),
                            DiagnosticsFieldUiModel("Power save", environment.powerSaveModeState),
                            DiagnosticsFieldUiModel("Network metered", environment.networkMeteredState),
                            DiagnosticsFieldUiModel("Roaming", environment.roamingState),
                        ),
                ),
            )

        private fun transportSpecificFields(
            snapshot: NetworkSnapshotModel,
            showSensitiveDetails: Boolean,
        ): List<DiagnosticsFieldUiModel> =
            buildList {
                snapshot.wifiDetails?.let { wifi ->
                    add(DiagnosticsFieldUiModel("Wi-Fi SSID", if (showSensitiveDetails) wifi.ssid else redactValue(wifi.ssid.takeUnless { it == "unknown" })))
                    add(DiagnosticsFieldUiModel("Wi-Fi BSSID", if (showSensitiveDetails) wifi.bssid else redactValue(wifi.bssid.takeUnless { it == "unknown" })))
                    add(DiagnosticsFieldUiModel("Wi-Fi band", wifi.band))
                    add(DiagnosticsFieldUiModel("Wi-Fi standard", wifi.wifiStandard))
                    add(DiagnosticsFieldUiModel("Wi-Fi frequency", wifi.frequencyMhz?.let { "$it MHz" } ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi channel width", wifi.channelWidth))
                    add(DiagnosticsFieldUiModel("Wi-Fi RSSI", wifi.rssiDbm?.let { "$it dBm" } ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi link", wifi.linkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi RX link", wifi.rxLinkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi TX link", wifi.txLinkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi hidden SSID", wifi.hiddenSsid?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi Passpoint", wifi.isPasspoint?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi OSU AP", wifi.isOsuAp?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi network ID", wifi.networkId?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Wi-Fi gateway", if (showSensitiveDetails) wifi.gateway ?: "Unknown" else redactValue(wifi.gateway)))
                    add(DiagnosticsFieldUiModel("Wi-Fi DHCP server", if (showSensitiveDetails) wifi.dhcpServer ?: "Unknown" else redactValue(wifi.dhcpServer)))
                    add(DiagnosticsFieldUiModel("Wi-Fi IP", if (showSensitiveDetails) wifi.ipAddress ?: "Unknown" else redactValue(wifi.ipAddress)))
                    add(DiagnosticsFieldUiModel("Wi-Fi subnet", if (showSensitiveDetails) wifi.subnetMask ?: "Unknown" else redactValue(wifi.subnetMask)))
                    add(DiagnosticsFieldUiModel("Wi-Fi lease", wifi.leaseDurationSeconds?.let { "${it}s" } ?: "Unknown"))
                }
                snapshot.cellularDetails?.let { cellular ->
                    add(DiagnosticsFieldUiModel("Carrier", cellular.carrierName))
                    add(DiagnosticsFieldUiModel("SIM operator", cellular.simOperatorName))
                    add(DiagnosticsFieldUiModel("Network operator", cellular.networkOperatorName))
                    add(DiagnosticsFieldUiModel("Network country", cellular.networkCountryIso))
                    add(DiagnosticsFieldUiModel("SIM country", cellular.simCountryIso))
                    add(DiagnosticsFieldUiModel("Operator code", cellular.operatorCode))
                    add(DiagnosticsFieldUiModel("SIM operator code", cellular.simOperatorCode))
                    add(DiagnosticsFieldUiModel("Data network", cellular.dataNetworkType))
                    add(DiagnosticsFieldUiModel("Voice network", cellular.voiceNetworkType))
                    add(DiagnosticsFieldUiModel("Data state", cellular.dataState))
                    add(DiagnosticsFieldUiModel("Service state", cellular.serviceState))
                    add(DiagnosticsFieldUiModel("Roaming", cellular.isNetworkRoaming?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Carrier ID", cellular.carrierId?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("SIM carrier ID", cellular.simCarrierId?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Signal level", cellular.signalLevel?.toString() ?: "Unknown"))
                    add(DiagnosticsFieldUiModel("Signal dBm", cellular.signalDbm?.let { "$it dBm" } ?: "Unknown"))
                }
            }

        private fun buildHostAutolearnOverviewSummary(service: com.poyka.ripdpi.diagnostics.ServiceContextModel): String {
            val state = formatAutolearnState(service.hostAutolearnEnabled)
            val details =
                buildList {
                    if (service.learnedHostCount > 0) {
                        add("${service.learnedHostCount} learned")
                    }
                    if (service.penalizedHostCount > 0) {
                        add("${service.penalizedHostCount} penalized")
                    }
                }
            return if (details.isEmpty()) state else "$state · ${details.joinToString(" · ")}"
        }

        private fun formatAutolearnState(value: String): String =
            when (value.lowercase(Locale.US)) {
                "enabled" -> "Active"
                "disabled" -> "Off"
                else -> "Unknown"
            }

        private fun formatAutolearnHost(value: String): String =
            value.takeUnless { it.isBlank() || it == "none" } ?: "None yet"

        private fun formatAutolearnGroup(value: String): String =
            value
                .takeUnless { it.isBlank() || it == "none" }
                ?.let { "Route $it" }
                ?: "None yet"

        private fun formatAutolearnAction(value: String): String =
            when (value.lowercase(Locale.US)) {
                "host_promoted" -> "Promoted best route"
                "group_penalized" -> "Penalized failing route"
                "store_reset" -> "Reset stored hosts"
                "none", "" -> "None yet"
                else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
            }

        private fun formatFakeTlsBaseMode(value: String): String =
            when (value.lowercase(Locale.US)) {
                "default" -> "Default fake ClientHello"
                "original" -> "Original ClientHello"
                else -> value
            }

        private fun formatFakeTlsSni(
            mode: String,
            fixedValue: String?,
        ): String =
            when (mode.lowercase(Locale.US)) {
                "fixed" -> fixedValue?.takeIf { it.isNotBlank() }?.let { "Fixed ($it)" } ?: "Fixed"
                "randomized" -> "Randomized"
                else -> mode
            }

        private fun formatFakeTlsMods(values: List<String>): String =
            values.joinToString(", ") { value ->
                when (value.lowercase(Locale.US)) {
                    "rand" -> "Randomize TLS material"
                    "dupsid" -> "Copy Session ID"
                    "padencap" -> "Padding camouflage"
                    else -> value
                }
            }

        private fun formatFakeTlsSize(value: Int): String =
            when {
                value > 0 -> "Exactly $value bytes"
                value < 0 -> "Input minus ${-value} bytes"
                else -> "Match input size"
            }

        private fun formatHttpFakeProfile(value: String): String =
            when (value.lowercase(Locale.US)) {
                "compat_default" -> "Compatibility default"
                "iana_get" -> "IANA GET"
                "cloudflare_get" -> "Cloudflare GET"
                else -> value
            }

        private fun formatTlsFakeProfile(value: String): String =
            when (value.lowercase(Locale.US)) {
                "compat_default" -> "Compatibility default"
                "iana_firefox" -> "IANA Firefox"
                "google_chrome" -> "Google Chrome"
                "vk_chrome" -> "VK Chrome"
                "sberbank_chrome" -> "Sberbank Chrome"
                "rutracker_kyber" -> "Rutracker Kyber"
                "bigsize_iana" -> "IANA bigsize"
                else -> value
            }

        private fun formatUdpFakeProfile(value: String): String =
            when (value.lowercase(Locale.US)) {
                "compat_default" -> "Compatibility default"
                "zero_256" -> "Zero blob 256"
                "zero_512" -> "Zero blob 512"
                "dns_query" -> "DNS query"
                "stun_binding" -> "STUN binding"
                "wireguard_initiation" -> "WireGuard initiation"
                "dht_get_peers" -> "DHT get_peers"
                else -> value
            }

        private fun formatFakePayloadSource(value: String): String =
            when (value.lowercase(Locale.US)) {
                "custom_raw" -> "Custom raw fake payload"
                else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
            }

        private fun formatQuicFakeProfile(value: String): String =
            when (value.lowercase(Locale.US)) {
                "compat_default" -> "Zapret compatibility"
                "realistic_initial" -> "Realistic Initial"
                "disabled" -> "Off"
                else -> value
            }

        private fun redactValue(value: String?): String = value?.let { "redacted" } ?: "Unknown"

        private fun redactCollection(values: List<String>): String =
            if (values.isEmpty()) {
                "Unknown"
            } else {
                "redacted(${values.size})"
            }

        private fun formatDurationMs(durationMs: Long): String {
            val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L -> String.format(Locale.US, "%dh %02dm", hours, minutes)
                minutes > 0L -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
                else -> "${seconds}s"
            }
        }

        private fun toggleValue(
            current: String?,
            next: String?,
        ): String? = if (current == next) null else next

        private suspend fun runArchiveAction(
            busyMessage: String,
            successMessage: String,
            failureMessage: String,
            action: suspend (String?) -> DiagnosticsArchive,
        ) {
            val targetSessionId = uiState.value.share.targetSessionId
            archiveActionState.value =
                ArchiveActionState(
                    message = busyMessage,
                    tone = DiagnosticsTone.Info,
                    isBusy = true,
                    latestArchiveFileName = archiveActionState.value.latestArchiveFileName,
                )
            runCatching { action(targetSessionId) }
                .onSuccess { archive ->
                    archiveActionState.value =
                        ArchiveActionState(
                            message = successMessage,
                            tone = DiagnosticsTone.Positive,
                            isBusy = false,
                            latestArchiveFileName = archive.fileName,
                        )
                }.onFailure {
                    archiveActionState.value =
                        ArchiveActionState(
                            message = failureMessage,
                            tone = DiagnosticsTone.Negative,
                            isBusy = false,
                            latestArchiveFileName = archiveActionState.value.latestArchiveFileName,
                        )
                }
        }
    }

private data class ArchiveActionState(
    val message: String? = null,
    val tone: DiagnosticsTone = DiagnosticsTone.Neutral,
    val isBusy: Boolean = false,
    val latestArchiveFileName: String? = null,
)
