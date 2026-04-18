package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    internal constructor(
        private val diagnosticsInteractionDependencies: DiagnosticsInteractionDependencies,
        private val diagnosticsContextDependencies: DiagnosticsContextDependencies,
        private val diagnosticsViewModelBootstrapper: DiagnosticsViewModelBootstrapper,
        diagnosticsUiStateAssembler: DiagnosticsUiStateAssembler,
        uiStateFactory: DiagnosticsUiStateFactory,
    ) : ViewModel() {
        private var initialized = false
        private val selectionState = MutableStateFlow(SelectionState())
        private val filterState = MutableStateFlow(FilterState())
        private val sessionDetailState = MutableStateFlow(SessionDetailState())
        private val scanLifecycleState = MutableStateFlow(ScanLifecycleState())
        private val _effects =
            MutableSharedFlow<DiagnosticsEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val effects: SharedFlow<DiagnosticsEffect> = _effects.asSharedFlow()

        val uiState: StateFlow<DiagnosticsUiState> =
            diagnosticsUiStateAssembler.assemble(
                scope = viewModelScope,
                interactionDependencies = diagnosticsInteractionDependencies,
                contextDependencies = diagnosticsContextDependencies,
                selectionState = selectionState,
                filterState = filterState,
                sessionDetailState = sessionDetailState,
                scanLifecycleState = scanLifecycleState,
            )

        private val _pcapRecording = MutableStateFlow(false)
        val pcapRecording: StateFlow<Boolean> = _pcapRecording.asStateFlow()

        private val mutations =
            DiagnosticsMutationRunner(
                scope = viewModelScope,
                diagnosticsTimelineSource = diagnosticsInteractionDependencies.diagnosticsTimelineSource,
                diagnosticsScanController = diagnosticsInteractionDependencies.diagnosticsScanController,
                diagnosticsDetailLoader = diagnosticsInteractionDependencies.diagnosticsDetailLoader,
                diagnosticsShareService = diagnosticsInteractionDependencies.diagnosticsShareService,
                diagnosticsResolverActions = diagnosticsInteractionDependencies.diagnosticsResolverActions,
                uiStateFactory = uiStateFactory,
                effects = _effects,
                currentUiState = { uiState.value },
            )

        private val selectionActions =
            DiagnosticsSelectionActions(mutations, selectionState, sessionDetailState)

        private val filterActions = DiagnosticsFilterActions(filterState)

        private val scanActions =
            DiagnosticsScanActions(
                mutations = mutations,
                scanLifecycle = scanLifecycleState,
                appContext = diagnosticsContextDependencies.appContext,
                loadSessionDetail = { sessionId, showSensitive ->
                    mutations.loadSessionDetail(
                        sessionId = sessionId,
                        showSensitiveDetails = showSensitive,
                        selection = selectionState,
                        sessionDetail = sessionDetailState,
                    )
                },
            )

        private val shareActions = DiagnosticsShareActions(mutations, scanLifecycleState)

        fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            diagnosticsViewModelBootstrapper.initialize(
                scope = viewModelScope,
                initializeScanActions = scanActions::initialize,
            )
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

        fun setSessionPathModeFilter(pathMode: String?) = filterActions.setSessionPathModeFilter(pathMode)

        fun setSessionStatusFilter(status: String?) = filterActions.setSessionStatusFilter(status)

        fun setSessionSearch(query: String) = filterActions.setSessionSearch(query)

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) = filterActions.toggleEventFilter(source, severity)

        fun setEventSearch(query: String) = filterActions.setEventSearch(query)

        fun setEventAutoScroll(enabled: Boolean) = filterActions.setEventAutoScroll(enabled)

        fun startRawScan() = scanActions.startRawScan()

        fun startInPathScan() = scanActions.startInPathScan()

        fun waitForHiddenProbeAndRun() = scanActions.waitForHiddenProbeAndRun()

        fun cancelHiddenProbeAndRun() = scanActions.cancelHiddenProbeAndRun()

        fun dismissHiddenProbeConflictDialog() = scanActions.dismissHiddenProbeConflictDialog()

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

        fun togglePcapRecording() {
            _pcapRecording.value = !_pcapRecording.value
        }
    }
