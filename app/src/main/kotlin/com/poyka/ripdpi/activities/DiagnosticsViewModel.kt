package com.poyka.ripdpi.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        private val diagnosticsInteractionDependencies: DiagnosticsInteractionDependencies,
        private val diagnosticsContextDependencies: DiagnosticsContextDependencies,
        private val diagnosticsViewModelBootstrapper: DiagnosticsViewModelBootstrapper,
        appSettingsRepository: AppSettingsRepository,
        dnsIntegrityChecker: DnsIntegrityChecker,
        dnsAvailabilitySurvey: DnsAvailabilitySurvey,
        domainReachabilityScanner: DomainReachabilityScanner,
        tcp16FatHeaderProbe: Tcp16FatHeaderProbe,
        httpCompressionProber: HttpCompressionProber,
        rknLayeredProbePipeline: RknLayeredProbePipeline,
        selfInfoFetcher: SelfInfoFetcher,
        diagnosticsUiStateAssembler: DiagnosticsUiStateAssembler,
        uiStateFactory: DiagnosticsUiStateFactory,
    ) : ViewModel() {
        private val autoStartScan: Boolean =
            savedStateHandle.get<Boolean>("auto_start_scan") ?: false
        private var initialized = false
        private var autoStartScanHandled = false
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
        private val dpiToolsController =
            DiagnosticsDpiToolsController(
                scope = viewModelScope,
                appContext = diagnosticsContextDependencies.appContext,
                appSettingsRepository = appSettingsRepository,
                dnsIntegrityChecker = dnsIntegrityChecker,
                dnsAvailabilitySurvey = dnsAvailabilitySurvey,
                domainReachabilityScanner = domainReachabilityScanner,
                tcp16FatHeaderProbe = tcp16FatHeaderProbe,
                httpCompressionProber = httpCompressionProber,
                rknLayeredProbePipeline = rknLayeredProbePipeline,
                selfInfoFetcher = selfInfoFetcher,
            )

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
        val pcapRecording: StateFlow<Boolean> = _pcapRecording
        val dnsIntegrityTool: StateFlow<DiagnosticsDnsIntegrityToolUiModel> = dpiToolsController.dnsIntegrityTool
        val dnsAvailabilityTool: StateFlow<DiagnosticsDnsAvailabilityToolUiModel> =
            dpiToolsController.dnsAvailabilityTool
        val domainReachabilityTool: StateFlow<DiagnosticsDomainReachabilityToolUiModel> =
            dpiToolsController.domainReachabilityTool
        val rknBlockDiagnosisTool: StateFlow<DiagnosticsRknBlockDiagnosisToolUiModel> =
            dpiToolsController.rknBlockDiagnosisTool
        val compressionProbeTool: StateFlow<DiagnosticsCompressionProbeToolUiModel> =
            dpiToolsController.compressionProbeTool
        val tcp16FatHeaderTool: StateFlow<DiagnosticsTcp16FatHeaderToolUiModel> =
            dpiToolsController.tcp16FatHeaderTool
        val allowlistSniTool: StateFlow<DiagnosticsAllowlistSniToolUiModel> =
            dpiToolsController.allowlistSniTool

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
            if (autoStartScan && !autoStartScanHandled) {
                autoStartScanHandled = true
                runScan()
            }
        }

        fun selectSection(section: DiagnosticsSection) = selectionActions.selectSection(section)

        val lastScanSummary: String?
            get() =
                uiState.value.scan.latestSession
                    ?.toLastScanSummary()

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

        fun runScan() = startInPathScan()

        fun waitForHiddenProbeAndRun() = scanActions.waitForHiddenProbeAndRun()

        fun cancelHiddenProbeAndRun() = scanActions.cancelHiddenProbeAndRun()

        fun dismissHiddenProbeConflictDialog() = scanActions.dismissHiddenProbeConflictDialog()

        fun confirmSensitiveProfileRun() = scanActions.confirmSensitiveProfileRun()

        fun dismissSensitiveProfileConsentDialog() = scanActions.dismissSensitiveProfileConsentDialog()

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

        fun runDnsIntegrityCheck() = dpiToolsController.runDnsIntegrityCheck()

        fun runDnsAvailabilitySurvey() = dpiToolsController.runDnsAvailabilitySurvey()

        fun runDomainReachabilityScan() = dpiToolsController.runDomainReachabilityScan()

        fun runCompressionProbe() = dpiToolsController.runCompressionProbe()

        fun runTcp16FatHeaderProbe() = dpiToolsController.runTcp16FatHeaderProbe()

        fun runAllowlistSniFinder() = dpiToolsController.runAllowlistSniFinder()

        fun runRknBlockDiagnosis() = dpiToolsController.runRknBlockDiagnosis()

        fun setRknSelfInfoEnabled(enabled: Boolean) = dpiToolsController.setRknSelfInfoEnabled(enabled)

        fun setCompressionProbeZstdEnabled(enabled: Boolean) =
            dpiToolsController.setCompressionProbeZstdEnabled(enabled)
    }

private fun DiagnosticsSessionRowUiModel.toLastScanSummary(): String =
    listOf(
        metrics.firstOrNull { it.label.contains("confidence", ignoreCase = true) }?.value ?: status,
        startedAtLabel,
    ).joinToString(" · ")
