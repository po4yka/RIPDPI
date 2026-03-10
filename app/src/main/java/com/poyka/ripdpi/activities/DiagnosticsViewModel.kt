package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ShareSummary
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
    Sessions,
    Events,
    Share,
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

data class DiagnosticsProfileOptionUiModel(
    val id: String,
    val name: String,
    val source: String,
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
)

data class DiagnosticsOverviewUiModel(
    val health: DiagnosticsHealth = DiagnosticsHealth.Idle,
    val headline: String = "Idle",
    val body: String = "No diagnostics activity yet.",
    val activeProfile: DiagnosticsProfileOptionUiModel? = null,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val warnings: List<DiagnosticsEventUiModel> = emptyList(),
)

data class DiagnosticsScanUiModel(
    val profiles: List<DiagnosticsProfileOptionUiModel> = emptyList(),
    val selectedProfileId: String? = null,
    val activePathMode: ScanPathMode = ScanPathMode.RAW_PATH,
    val activeProgress: DiagnosticsProgressUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val latestResults: List<DiagnosticsProbeResultUiModel> = emptyList(),
    val isBusy: Boolean = false,
)

data class DiagnosticsLiveUiModel(
    val statusLabel: String = "Idle",
    val freshnessLabel: String = "No live telemetry",
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val trends: List<DiagnosticsSparklineUiModel> = emptyList(),
    val snapshot: DiagnosticsNetworkSnapshotUiModel? = null,
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
    val events: DiagnosticsEventsUiModel = DiagnosticsEventsUiModel(),
    val share: DiagnosticsShareUiModel = DiagnosticsShareUiModel(),
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel? = null,
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
    ) : ViewModel() {
        private val json = Json { ignoreUnknownKeys = true }
        private val timestampFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        private val selectedSectionRequest = MutableStateFlow(DiagnosticsSection.Overview)
        private val selectedProfileId = MutableStateFlow<String?>(null)
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
        private val archiveActionState = MutableStateFlow(ArchiveActionState())
        private val _effects = Channel<DiagnosticsEffect>(Channel.BUFFERED)
        val effects: Flow<DiagnosticsEffect> = _effects.receiveAsFlow()

        val uiState: StateFlow<DiagnosticsUiState> =
            combine(
                diagnosticsManager.profiles,
                diagnosticsManager.activeScanProgress,
                diagnosticsManager.sessions,
                diagnosticsManager.snapshots,
                diagnosticsManager.telemetry,
                diagnosticsManager.nativeEvents,
                diagnosticsManager.exports,
                selectedSectionRequest,
                selectedProfileId,
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
                archiveActionState,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val profiles = values[0] as List<DiagnosticProfileEntity>
                val progress = values[1] as ScanProgress?
                val sessions = values[2] as List<ScanSessionEntity>
                val snapshots = values[3] as List<NetworkSnapshotEntity>
                val telemetry = values[4] as List<TelemetrySampleEntity>
                val nativeEvents = values[5] as List<NativeSessionEventEntity>
                val exports = values[6] as List<ExportRecordEntity>
                val selectedSectionRequest = values[7] as DiagnosticsSection
                val selectedProfileId = values[8] as String?
                val selectedProbe = values[9] as DiagnosticsProbeResultUiModel?
                val selectedEventId = values[10] as String?
                val sessionPathMode = values[11] as String?
                val sessionStatus = values[12] as String?
                val sessionSearch = values[13] as String
                val eventSource = values[14] as String?
                val eventSeverity = values[15] as String?
                val eventSearch = values[16] as String
                val eventAutoScroll = values[17] as Boolean
                val sessionDetail = values[18] as DiagnosticsSessionDetailUiModel?
                val archiveActionState = values[19] as ArchiveActionState

                buildUiState(
                    profiles = profiles,
                    progress = progress,
                    sessions = sessions,
                    snapshots = snapshots,
                    telemetry = telemetry,
                    nativeEvents = nativeEvents,
                    exports = exports,
                    selectedSectionRequest = selectedSectionRequest,
                    selectedProfileId = selectedProfileId,
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
                selectedSessionDetail.value = detail.toUiModel()
            }
        }

        fun dismissSessionDetail() {
            selectedSessionDetail.value = null
            selectedProbe.value = null
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
            progress: ScanProgress?,
            sessions: List<ScanSessionEntity>,
            snapshots: List<NetworkSnapshotEntity>,
            telemetry: List<TelemetrySampleEntity>,
            nativeEvents: List<NativeSessionEventEntity>,
            exports: List<ExportRecordEntity>,
            selectedSectionRequest: DiagnosticsSection,
            selectedProfileId: String?,
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
            archiveActionState: ArchiveActionState,
        ): DiagnosticsUiState {
            val activeProfile =
                profiles.firstOrNull { it.id == selectedProfileId }
                    ?: profiles.firstOrNull()
            val latestSnapshot = snapshots.firstOrNull()?.toUiModel()
            val eventModels = nativeEvents.map { it.toUiModel() }
            val sessionRows = sessions.map(::toSessionRowUiModel)
            val latestCompletedSession = sessions.firstOrNull { it.reportJson != null } ?: sessions.firstOrNull()
            val latestReport = latestCompletedSession?.reportJson?.let(::decodeReport)
            val latestReportResults = latestReport?.results?.mapIndexed(::toProbeResultUiModel).orEmpty()
            val currentTelemetry = telemetry.firstOrNull()
            val health = deriveHealth(progress, latestCompletedSession, currentTelemetry, nativeEvents)
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
            val sharePreview = buildSharePreview(latestCompletedSession, latestSnapshot, currentTelemetry, nativeEvents, latestReport)
            val liveMetrics = buildLiveMetrics(currentTelemetry, nativeEvents)
            val liveTrends = buildLiveTrends(telemetry)
            val warnings = eventModels.filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning }.take(3)

            return DiagnosticsUiState(
                selectedSection = selectedSection,
                overview =
                    DiagnosticsOverviewUiModel(
                        health = health,
                        headline = overviewHeadline(health, progress, latestCompletedSession),
                        body = overviewBody(health, latestSnapshot, currentTelemetry),
                        activeProfile = activeProfile?.toOptionUiModel(),
                        latestSnapshot = latestSnapshot,
                        latestSession = sessionRows.firstOrNull(),
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
                        activePathMode = latestCompletedSession?.pathMode?.let(::parsePathMode) ?: ScanPathMode.RAW_PATH,
                        activeProgress = progress?.toUiModel(),
                        latestSession = latestCompletedSession?.let(::toSessionRowUiModel),
                        latestResults = latestReportResults,
                        isBusy = progress != null,
                    ),
                live =
                    DiagnosticsLiveUiModel(
                        statusLabel = currentTelemetry?.connectionState ?: "Idle",
                        freshnessLabel =
                            currentTelemetry?.createdAt?.let { "Updated ${formatTimestamp(it)}" }
                                ?: "No live telemetry",
                        metrics = liveMetrics,
                        trends = liveTrends,
                        snapshot = latestSnapshot,
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
                        previewBody = sharePreview.body,
                        metrics = sharePreview.compactMetrics.map { DiagnosticsMetricUiModel(it.label, it.value) },
                        latestArchiveFileName = archiveActionState.latestArchiveFileName ?: exports.firstOrNull()?.fileName,
                        archiveStateMessage = archiveActionState.message,
                        archiveStateTone = archiveActionState.tone,
                        isArchiveBusy = archiveActionState.isBusy,
                    ),
                selectedSessionDetail = selectedSessionDetail,
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

        private fun buildSharePreview(
            latestSession: ScanSessionEntity?,
            latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
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
                    appendLine("Summary and manifest redact network identity fields, while raw report data stays intact.")
                    latestSession?.let {
                        appendLine("Session ${it.id.take(8)} · ${it.pathMode} · ${it.status}")
                    }
                    latestSnapshot?.let {
                        appendLine("Network ${it.subtitle}")
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
                        telemetry?.txBytes?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("TX", formatBytes(it)) },
                        telemetry?.rxBytes?.let { com.poyka.ripdpi.diagnostics.SummaryMetric("RX", formatBytes(it)) },
                    ),
            )
        }

        private fun decodeReport(reportJson: String): ScanReport? =
            runCatching { json.decodeFromString(ScanReport.serializer(), reportJson) }.getOrNull()

        private fun decodeProbeDetails(detailJson: String): List<ProbeDetail> =
            runCatching { json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson) }.getOrElse { emptyList() }

        private fun NetworkSnapshotEntity.toUiModel(): DiagnosticsNetworkSnapshotUiModel? {
            val snapshot = runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payloadJson) }.getOrNull() ?: return null
            return DiagnosticsNetworkSnapshotUiModel(
                title = snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
                subtitle = "${snapshot.transport} · ${formatTimestamp(snapshot.capturedAt)}",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("Capabilities", snapshot.capabilities.joinToString().ifBlank { "Unknown" }),
                        DiagnosticsFieldUiModel("DNS", snapshot.dnsServers.joinToString().ifBlank { "Unknown" }),
                        DiagnosticsFieldUiModel("Private DNS", snapshot.privateDnsMode),
                        DiagnosticsFieldUiModel("MTU", snapshot.mtu?.toString() ?: "Unknown"),
                        DiagnosticsFieldUiModel("Local", snapshot.localAddresses.joinToString().ifBlank { "Unknown" }),
                        DiagnosticsFieldUiModel("Public IP", snapshot.publicIp ?: "Unknown"),
                        DiagnosticsFieldUiModel("ASN", snapshot.publicAsn ?: "Unknown"),
                        DiagnosticsFieldUiModel("Validated", snapshot.networkValidated.toString()),
                        DiagnosticsFieldUiModel("Captive portal", snapshot.captivePortalDetected.toString()),
                    ),
            )
        }

        private fun DiagnosticProfileEntity.toOptionUiModel(): DiagnosticsProfileOptionUiModel =
            DiagnosticsProfileOptionUiModel(
                id = id,
                name = name,
                source = source,
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

        private fun DiagnosticSessionDetail.toUiModel(): DiagnosticsSessionDetailUiModel {
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
                snapshots = snapshots.mapNotNull { it.toUiModel() },
                events = events.map { it.toUiModel() },
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
