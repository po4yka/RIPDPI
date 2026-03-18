package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.ScanProgress
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
        private val diagnosticsManager: DiagnosticsManager,
        appSettingsRepository: AppSettingsRepository,
        rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        activeConnectionPolicyStore: ActiveConnectionPolicyStore,
        serviceStateStore: ServiceStateStore,
        uiStateFactory: DiagnosticsUiStateFactory,
    ) : ViewModel() {
        private val selectionState = MutableStateFlow(SelectionState())
        private val filterState = MutableStateFlow(FilterState())
        private val sessionDetailState = MutableStateFlow(SessionDetailState())
        private val scanLifecycleState = MutableStateFlow(ScanLifecycleState())
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
                selectionState,
                filterState,
                sessionDetailState,
                scanLifecycleState,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val sel = values[13] as SelectionState
                val flt = values[14] as FilterState
                val det = values[15] as SessionDetailState
                val scan = values[16] as ScanLifecycleState
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
                    selectedSectionRequest = sel.selectedSectionRequest,
                    selectedProfileId = sel.selectedProfileId,
                    selectedApproachMode = sel.selectedApproachMode,
                    selectedProbe = sel.selectedProbe,
                    selectedEventId = sel.selectedEventId,
                    sessionPathMode = flt.sessionPathModeFilter,
                    sessionStatus = flt.sessionStatusFilter,
                    sessionSearch = flt.sessionSearch,
                    eventSource = flt.eventSourceFilter,
                    eventSeverity = flt.eventSeverityFilter,
                    eventSearch = flt.eventSearch,
                    eventAutoScroll = flt.eventAutoScroll,
                    selectedSessionDetail = det.selectedSessionDetail,
                    selectedStrategyProbeCandidate = sel.selectedStrategyProbeCandidate,
                    selectedApproachDetail = sel.selectedApproachDetail,
                    sensitiveSessionDetailsVisible = det.sensitiveSessionDetailsVisible,
                    archiveActionState = scan.archiveActionState,
                    scanStartedAt = scan.scanStartedAt,
                    completedProbes = scan.accumulatedProbes,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DiagnosticsUiState(),
            )

        private val mutations = DiagnosticsMutationRunner(
            scope = viewModelScope,
            diagnosticsManager = diagnosticsManager,
            uiStateFactory = uiStateFactory,
            effects = _effects,
            currentUiState = { uiState.value },
        )

        private val selectionActions =
            DiagnosticsSelectionActions(mutations, selectionState, sessionDetailState, filterState)

        private val scanActions = DiagnosticsScanActions(
            mutations = mutations,
            scanLifecycle = scanLifecycleState,
            loadSessionDetail = { sessionId, showSensitive ->
                with(selectionActions) {
                    mutations.loadSessionDetail(sessionId, showSensitive)
                }
            },
        )

        private val shareActions = DiagnosticsShareActions(mutations, scanLifecycleState)

        init {
            viewModelScope.launch {
                diagnosticsManager.initialize()
            }
            scanActions.initialize()
        }

        private fun selectActiveConnectionPolicy(
            serviceStatus: Pair<AppStatus, Mode>,
            activePolicies: Map<Mode, ActiveConnectionPolicy>,
        ): ActiveConnectionPolicy? {
            val (_, activeMode) = serviceStatus
            return activePolicies[activeMode]
                ?: activePolicies.values.maxByOrNull(ActiveConnectionPolicy::appliedAt)
        }

        fun selectSection(section: DiagnosticsSection) =
            selectionActions.selectSection(section)

        fun selectProfile(profileId: String) =
            selectionActions.selectProfile(profileId)

        fun selectSession(sessionId: String) =
            selectionActions.selectSession(sessionId)

        fun selectApproachMode(mode: DiagnosticsApproachMode) =
            selectionActions.selectApproachMode(mode)

        fun selectApproach(approachId: String) =
            selectionActions.selectApproach(approachId)

        fun dismissSessionDetail() =
            selectionActions.dismissSessionDetail()

        fun dismissApproachDetail() =
            selectionActions.dismissApproachDetail()

        fun selectEvent(eventId: String) =
            selectionActions.selectEvent(eventId)

        fun dismissEventDetail() =
            selectionActions.dismissEventDetail()

        fun selectProbe(probe: DiagnosticsProbeResultUiModel) =
            selectionActions.selectProbe(probe)

        fun dismissProbeDetail() =
            selectionActions.dismissProbeDetail()

        fun selectStrategyProbeCandidate(detail: DiagnosticsStrategyProbeCandidateDetailUiModel) =
            selectionActions.selectStrategyProbeCandidate(detail)

        fun dismissStrategyProbeCandidate() =
            selectionActions.dismissStrategyProbeCandidate()

        fun toggleSensitiveSessionDetails() =
            selectionActions.toggleSensitiveSessionDetails()

        fun setSessionPathModeFilter(pathMode: String?) =
            selectionActions.setSessionPathModeFilter(pathMode)

        fun setSessionStatusFilter(status: String?) =
            selectionActions.setSessionStatusFilter(status)

        fun setSessionSearch(query: String) =
            selectionActions.setSessionSearch(query)

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) = selectionActions.toggleEventFilter(source, severity)

        fun setEventSearch(query: String) =
            selectionActions.setEventSearch(query)

        fun setEventAutoScroll(enabled: Boolean) =
            selectionActions.setEventAutoScroll(enabled)

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

        fun shareSummary(sessionId: String? = null) =
            shareActions.shareSummary(sessionId)

        fun shareArchive(sessionId: String? = null) =
            shareActions.shareArchive(sessionId)

        fun saveArchive(sessionId: String? = null) =
            shareActions.saveArchive(sessionId)
    }
