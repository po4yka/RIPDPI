package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
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
    @Inject
    internal constructor(
        private val diagnosticsBootstrapper: DiagnosticsBootstrapper,
        diagnosticsTimelineSource: DiagnosticsTimelineSource,
        diagnosticsScanController: DiagnosticsScanController,
        diagnosticsDetailLoader: DiagnosticsDetailLoader,
        diagnosticsShareService: DiagnosticsShareService,
        diagnosticsResolverActions: DiagnosticsResolverActions,
        appSettingsRepository: AppSettingsRepository,
        rememberedPolicySource: DiagnosticsRememberedPolicySource,
        activeConnectionPolicySource: DiagnosticsActiveConnectionPolicySource,
        serviceStateStore: ServiceStateStore,
        uiStateFactory: DiagnosticsUiStateFactory,
    ) : ViewModel() {
        private var initialized = false
        private val selectionState = MutableStateFlow(SelectionState())
        private val filterState = MutableStateFlow(FilterState())
        private val sessionDetailState = MutableStateFlow(SessionDetailState())
        private val scanLifecycleState = MutableStateFlow(ScanLifecycleState())
        private val _effects = Channel<DiagnosticsEffect>(Channel.BUFFERED)

        val effects: Flow<DiagnosticsEffect> = _effects.receiveAsFlow()

        // --- Tier 1: Group raw flows by emission frequency ---

        private val liveData: StateFlow<LiveDataSnapshot> =
            combine(
                diagnosticsTimelineSource.telemetry,
                diagnosticsTimelineSource.nativeEvents,
                diagnosticsTimelineSource.activeScanProgress,
                diagnosticsTimelineSource.snapshots,
                diagnosticsTimelineSource.contexts,
            ) { telemetry, nativeEvents, progress, snapshots, contexts ->
                LiveDataSnapshot(telemetry, nativeEvents, progress, snapshots, contexts)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveDataSnapshot.EMPTY)

        private val scanData: StateFlow<ScanDataSnapshot> =
            combine(
                diagnosticsTimelineSource.profiles,
                diagnosticsTimelineSource.sessions,
                diagnosticsTimelineSource.approachStats,
                diagnosticsTimelineSource.exports,
            ) { profiles, sessions, approachStats, exports ->
                ScanDataSnapshot(profiles, sessions, approachStats, exports)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanDataSnapshot.EMPTY)

        private val configData: StateFlow<ConfigSnapshot> =
            combine(
                appSettingsRepository.settings,
                rememberedPolicySource.observePolicies(limit = 64),
                serviceStateStore.status,
                activeConnectionPolicySource.activePolicies,
            ) { settings, rememberedPolicies, serviceStatus, activePolicies ->
                val (_, activeMode) = serviceStatus
                val connectionPolicy =
                    activePolicies[activeMode]
                        ?: activePolicies.values.maxByOrNull(DiagnosticActiveConnectionPolicy::appliedAt)
                ConfigSnapshot(settings, rememberedPolicies, connectionPolicy)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ConfigSnapshot(
                    settings = AppSettingsSerializer.defaultValue,
                    rememberedPolicies = emptyList(),
                    activeConnectionPolicy = null,
                ),
            )

        // --- Tier 2: Merge tiers for final assembly ---

        private val combinedData: StateFlow<Triple<LiveDataSnapshot, ScanDataSnapshot, ConfigSnapshot>> =
            combine(liveData, scanData, configData) { live, scan, config ->
                Triple(live, scan, config)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                Triple(LiveDataSnapshot.EMPTY, ScanDataSnapshot.EMPTY, configData.value),
            )

        private val combinedUi: StateFlow<UiControlState> =
            combine(
                selectionState,
                filterState,
                sessionDetailState,
                scanLifecycleState,
            ) { sel, flt, det, scanLc ->
                UiControlState(sel, flt, det, scanLc)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                UiControlState(SelectionState(), FilterState(), SessionDetailState(), ScanLifecycleState()),
            )

        // --- Final assembly: type-safe 2-flow combine ---

        val uiState: StateFlow<DiagnosticsUiState> =
            combine(combinedData, combinedUi) { (live, scan, config), ui ->
                uiStateFactory.buildUiState(
                    profiles = scan.profiles,
                    settings = config.settings,
                    progress = live.progress,
                    sessions = scan.sessions,
                    approachStats = scan.approachStats,
                    snapshots = live.snapshots,
                    contexts = live.contexts,
                    telemetry = live.telemetry,
                    nativeEvents = live.nativeEvents,
                    exports = scan.exports,
                    rememberedPolicies = config.rememberedPolicies,
                    activeConnectionPolicy = config.activeConnectionPolicy,
                    selectedSectionRequest = ui.selection.selectedSectionRequest,
                    selectedProfileId = ui.selection.selectedProfileId,
                    selectedApproachMode = ui.selection.selectedApproachMode,
                    selectedProbe = ui.selection.selectedProbe,
                    selectedEventId = ui.selection.selectedEventId,
                    sessionPathMode = ui.filter.sessionPathModeFilter,
                    sessionStatus = ui.filter.sessionStatusFilter,
                    sessionSearch = ui.filter.sessionSearch,
                    eventSource = ui.filter.eventSourceFilter,
                    eventSeverity = ui.filter.eventSeverityFilter,
                    eventSearch = ui.filter.eventSearch,
                    eventAutoScroll = ui.filter.eventAutoScroll,
                    selectedSessionDetail = ui.sessionDetail.selectedSessionDetail,
                    selectedStrategyProbeCandidate = ui.selection.selectedStrategyProbeCandidate,
                    selectedApproachDetail = ui.selection.selectedApproachDetail,
                    sensitiveSessionDetailsVisible = ui.sessionDetail.sensitiveSessionDetailsVisible,
                    archiveActionState = ui.scanLifecycle.archiveActionState,
                    scanStartedAt = ui.scanLifecycle.scanStartedAt,
                    completedProbes = ui.scanLifecycle.accumulatedProbes,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DiagnosticsUiState(),
            )

        private val mutations =
            DiagnosticsMutationRunner(
                scope = viewModelScope,
                diagnosticsTimelineSource = diagnosticsTimelineSource,
                diagnosticsScanController = diagnosticsScanController,
                diagnosticsDetailLoader = diagnosticsDetailLoader,
                diagnosticsShareService = diagnosticsShareService,
                diagnosticsResolverActions = diagnosticsResolverActions,
                uiStateFactory = uiStateFactory,
                effects = _effects,
                currentUiState = { uiState.value },
            )

        private val selectionActions =
            DiagnosticsSelectionActions(mutations, selectionState, sessionDetailState, filterState)

        private val scanActions =
            DiagnosticsScanActions(
                mutations = mutations,
                scanLifecycle = scanLifecycleState,
                loadSessionDetail = { sessionId, showSensitive ->
                    with(selectionActions) {
                        mutations.loadSessionDetail(sessionId, showSensitive)
                    }
                },
            )

        private val shareActions = DiagnosticsShareActions(mutations, scanLifecycleState)

        fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            viewModelScope.launch {
                diagnosticsBootstrapper.initialize()
            }
            scanActions.initialize()
        }

        fun selectSection(section: DiagnosticsSection) = selectionActions.selectSection(section)

        fun selectProfile(profileId: String) = selectionActions.selectProfile(profileId)

        fun selectSession(sessionId: String) = selectionActions.selectSession(sessionId)

        fun selectApproachMode(mode: DiagnosticsApproachMode) = selectionActions.selectApproachMode(mode)

        fun selectApproach(approachId: String) = selectionActions.selectApproach(approachId)

        fun dismissSessionDetail() = selectionActions.dismissSessionDetail()

        fun dismissApproachDetail() = selectionActions.dismissApproachDetail()

        fun selectEvent(eventId: String) = selectionActions.selectEvent(eventId)

        fun dismissEventDetail() = selectionActions.dismissEventDetail()

        fun selectProbe(probe: DiagnosticsProbeResultUiModel) = selectionActions.selectProbe(probe)

        fun dismissProbeDetail() = selectionActions.dismissProbeDetail()

        fun selectStrategyProbeCandidate(detail: DiagnosticsStrategyProbeCandidateDetailUiModel) =
            selectionActions.selectStrategyProbeCandidate(detail)

        fun dismissStrategyProbeCandidate() = selectionActions.dismissStrategyProbeCandidate()

        fun toggleSensitiveSessionDetails() = selectionActions.toggleSensitiveSessionDetails()

        fun setSessionPathModeFilter(pathMode: String?) = selectionActions.setSessionPathModeFilter(pathMode)

        fun setSessionStatusFilter(status: String?) = selectionActions.setSessionStatusFilter(status)

        fun setSessionSearch(query: String) = selectionActions.setSessionSearch(query)

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) = selectionActions.toggleEventFilter(source, severity)

        fun setEventSearch(query: String) = selectionActions.setEventSearch(query)

        fun setEventAutoScroll(enabled: Boolean) = selectionActions.setEventAutoScroll(enabled)

        fun startRawScan() = scanActions.startRawScan()

        fun startInPathScan() = scanActions.startInPathScan()

        fun cancelScan() = scanActions.cancelScan()

        fun keepResolverRecommendationForSession(
            sessionId: String? =
                uiState.value.scan.latestSession
                    ?.id,
        ) = scanActions.keepResolverRecommendationForSession(sessionId)

        fun saveResolverRecommendation(
            sessionId: String? =
                uiState.value.scan.latestSession
                    ?.id,
        ) = scanActions.saveResolverRecommendation(sessionId)

        fun shareSummary(sessionId: String? = null) = shareActions.shareSummary(sessionId)

        fun shareArchive(sessionId: String? = null) = shareActions.shareArchive(sessionId)

        fun saveArchive(sessionId: String? = null) = shareActions.saveArchive(sessionId)
    }
