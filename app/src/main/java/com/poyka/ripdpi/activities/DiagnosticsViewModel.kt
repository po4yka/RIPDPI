package com.poyka.ripdpi.activities

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.services.ActiveConnectionPolicy
import com.poyka.ripdpi.services.ActiveConnectionPolicyStore
import com.poyka.ripdpi.services.DefaultActiveConnectionPolicyStore
import com.poyka.ripdpi.services.DefaultServiceStateStore
import com.poyka.ripdpi.services.ServiceStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    private constructor(
        private val diagnosticsManager: DiagnosticsManager,
        private val appSettingsRepository: AppSettingsRepository,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val activeConnectionPolicyStore: ActiveConnectionPolicyStore,
        private val serviceStateStore: ServiceStateStore,
        private val uiStateFactory: DiagnosticsUiStateFactory,
        @Suppress("UNUSED_PARAMETER")
        private val constructorToken: Any,
    ) : ViewModel() {
        private companion object {
            private object ConstructionToken
        }

        @Inject
        internal constructor(
            diagnosticsManager: DiagnosticsManager,
            appSettingsRepository: AppSettingsRepository,
            rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
            activeConnectionPolicyStore: ActiveConnectionPolicyStore,
            serviceStateStore: ServiceStateStore,
            uiStateFactory: DiagnosticsUiStateFactory,
        ) : this(
            diagnosticsManager = diagnosticsManager,
            appSettingsRepository = appSettingsRepository,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            activeConnectionPolicyStore = activeConnectionPolicyStore,
            serviceStateStore = serviceStateStore,
            uiStateFactory = uiStateFactory,
            constructorToken = ConstructionToken,
        )

        constructor(
            appContext: Context,
            diagnosticsManager: DiagnosticsManager,
            appSettingsRepository: AppSettingsRepository,
            rememberedNetworkPolicyStore: RememberedNetworkPolicyStore = defaultRememberedNetworkPolicyStore(),
            activeConnectionPolicyStore: ActiveConnectionPolicyStore = DefaultActiveConnectionPolicyStore(),
            serviceStateStore: ServiceStateStore = DefaultServiceStateStore(),
        ) : this(
            diagnosticsManager = diagnosticsManager,
            appSettingsRepository = appSettingsRepository,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            activeConnectionPolicyStore = activeConnectionPolicyStore,
            serviceStateStore = serviceStateStore,
            uiStateFactory = DiagnosticsUiStateFactory(appContext),
            constructorToken = ConstructionToken,
        )

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
    private val selectedStrategyProbeCandidate = MutableStateFlow<DiagnosticsStrategyProbeCandidateDetailUiModel?>(null)
    private val pendingAutoOpenAuditSessionId = MutableStateFlow<String?>(null)
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
            rememberedNetworkPolicyStore.observePolicies(limit = 64),
            serviceStateStore.status,
            activeConnectionPolicyStore.activePolicies,
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
            selectedStrategyProbeCandidate,
            selectedApproachDetail,
            sensitiveSessionDetailsVisible,
            archiveActionState,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            uiStateFactory.buildUiState(
                profiles = values[0] as List<DiagnosticProfileEntity>,
                settings = values[1] as com.poyka.ripdpi.proto.AppSettings,
                progress = values[2] as ScanProgress?,
                sessions = values[3] as List<ScanSessionEntity>,
                approachStats = values[4] as List<BypassApproachSummary>,
                snapshots = values[5] as List<NetworkSnapshotEntity>,
                contexts = values[6] as List<DiagnosticContextEntity>,
                telemetry = values[7] as List<TelemetrySampleEntity>,
                nativeEvents = values[8] as List<NativeSessionEventEntity>,
                exports = values[9] as List<ExportRecordEntity>,
                rememberedPolicies = values[10] as List<RememberedNetworkPolicyEntity>,
                activeConnectionPolicy =
                    selectActiveConnectionPolicy(
                        serviceStatus = values[11] as Pair<AppStatus, Mode>,
                        activePolicies = values[12] as Map<Mode, ActiveConnectionPolicy>,
                    ),
                selectedSectionRequest = values[13] as DiagnosticsSection,
                selectedProfileId = values[14] as String?,
                selectedApproachMode = values[15] as DiagnosticsApproachMode,
                selectedProbe = values[16] as DiagnosticsProbeResultUiModel?,
                selectedEventId = values[17] as String?,
                sessionPathMode = values[18] as String?,
                sessionStatus = values[19] as String?,
                sessionSearch = values[20] as String,
                eventSource = values[21] as String?,
                eventSeverity = values[22] as String?,
                eventSearch = values[23] as String,
                eventAutoScroll = values[24] as Boolean,
                selectedSessionDetail = values[25] as DiagnosticsSessionDetailUiModel?,
                selectedStrategyProbeCandidate = values[26] as DiagnosticsStrategyProbeCandidateDetailUiModel?,
                selectedApproachDetail = values[27] as DiagnosticsApproachDetailUiModel?,
                sensitiveSessionDetailsVisible = values[28] as Boolean,
                archiveActionState = values[29] as ArchiveActionState,
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
        viewModelScope.launch {
            combine(
                diagnosticsManager.sessions,
                diagnosticsManager.activeScanProgress,
                pendingAutoOpenAuditSessionId,
            ) { sessions, progress, pendingSessionId ->
                Triple(sessions, progress, pendingSessionId)
            }.collect { (sessions, progress, pendingSessionId) ->
                if (pendingSessionId == null || progress != null) {
                    return@collect
                }
                val session =
                    sessions.firstOrNull { it.id == pendingSessionId && it.reportJson != null }
                        ?: return@collect
                loadSessionDetail(session.id, showSensitiveDetails = false)
                pendingAutoOpenAuditSessionId.value = null
            }
        }
    }

    private fun selectActiveConnectionPolicy(
        serviceStatus: Pair<AppStatus, Mode>,
        activePolicies: Map<Mode, ActiveConnectionPolicy>,
    ): ActiveConnectionPolicy? {
        val (_, activeMode) = serviceStatus
        return activePolicies[activeMode] ?: activePolicies.values.maxByOrNull(ActiveConnectionPolicy::appliedAt)
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
            loadSessionDetail(sessionId, showSensitiveDetails = false)
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
            selectedApproachDetail.value = uiStateFactory.toApproachDetailUiModel(detail)
        }
    }

    fun dismissSessionDetail() {
        selectedSessionDetail.value = null
        selectedProbe.value = null
        selectedStrategyProbeCandidate.value = null
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

    fun selectStrategyProbeCandidate(detail: DiagnosticsStrategyProbeCandidateDetailUiModel) {
        selectedStrategyProbeCandidate.value = detail
    }

    fun dismissStrategyProbeCandidate() {
        selectedStrategyProbeCandidate.value = null
    }

    fun toggleSensitiveSessionDetails() {
        val nextValue = !sensitiveSessionDetailsVisible.value
        sensitiveSessionDetailsVisible.value = nextValue
        val sessionId = selectedSessionDetail.value?.session?.id ?: return
        viewModelScope.launch {
            loadSessionDetail(sessionId, showSensitiveDetails = nextValue)
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
            val sessionId = diagnosticsManager.startScan(ScanPathMode.RAW_PATH)
            if (uiState.value.scan.selectedProfile?.isFullAudit == true) {
                pendingAutoOpenAuditSessionId.value = sessionId
            }
        }
    }

    fun startInPathScan() {
        viewModelScope.launch {
            diagnosticsManager.startScan(ScanPathMode.IN_PATH)
        }
    }

    fun cancelScan() {
        viewModelScope.launch {
            pendingAutoOpenAuditSessionId.value = null
            diagnosticsManager.cancelActiveScan()
        }
    }

    fun keepResolverRecommendationForSession(sessionId: String? = uiState.value.scan.latestSession?.id) {
        val targetSessionId = sessionId ?: return
        viewModelScope.launch {
            diagnosticsManager.keepResolverRecommendationForSession(targetSessionId)
        }
    }

    fun saveResolverRecommendation(sessionId: String? = uiState.value.scan.latestSession?.id) {
        val targetSessionId = sessionId ?: return
        viewModelScope.launch {
            diagnosticsManager.saveResolverRecommendation(targetSessionId)
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

    private suspend fun loadSessionDetail(
        sessionId: String,
        showSensitiveDetails: Boolean,
    ) {
        val detail = diagnosticsManager.loadSessionDetail(sessionId)
        sensitiveSessionDetailsVisible.value = showSensitiveDetails
        selectedStrategyProbeCandidate.value = null
        selectedSessionDetail.value =
            uiStateFactory.toSessionDetailUiModel(
                detail = detail,
                showSensitiveDetails = showSensitiveDetails,
            )
    }

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

    private fun toggleValue(
        current: String?,
        next: String?,
    ): String? = if (current == next) null else next
}
