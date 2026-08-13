package com.poyka.ripdpi.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        private val diagnosticsFiles: DiagnosticsFiles,
        private val stringResolver: StringResolver,
        private val appSettingsRepository: AppSettingsRepository,
        serviceStateStore: ServiceStateStore,
        xrayProviderProbeCoordinator: XrayProviderProbeCoordinator,
        probeDependencies: DiagnosticsProbeDependencies,
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

        val effects = _effects.bufferForUiLifecycle(viewModelScope)
        private val dpiToolsController =
            DiagnosticsDpiToolsController(
                scope = viewModelScope,
                appSettingsRepository = appSettingsRepository,
                dnsIntegrityChecker = probeDependencies.dnsIntegrityChecker,
                dnsAvailabilitySurvey = probeDependencies.dnsAvailabilitySurvey,
                domainReachabilityScanner = probeDependencies.domainReachabilityScanner,
                tcp16FatHeaderProbe = probeDependencies.tcp16FatHeaderProbe,
                httpCompressionProber = probeDependencies.httpCompressionProber,
                rknLayeredProbePipeline = probeDependencies.rknLayeredProbePipeline,
                selfInfoFetcher = probeDependencies.selfInfoFetcher,
                assetLoader = probeDependencies.assetLoader,
                stringResolver = stringResolver,
            )
        private val cidrWhitelistController =
            DiagnosticsCidrWhitelistController(
                scope = viewModelScope,
                detector = probeDependencies.cidrWhitelistDetector,
                stringResolver = stringResolver,
            )
        private val ipv4WhitelistController =
            DiagnosticsIpv4WhitelistController(
                scope = viewModelScope,
                discoverer = probeDependencies.ipv4WhitelistedSubnetDiscoverer,
                shareCsv = { csv ->
                    _effects.tryEmit(
                        DiagnosticsEffect.ShareSummaryRequested(
                            title = stringResolver.getString(R.string.diagnostics_ipv4_whitelist_csv_title),
                            body = csv,
                        ),
                    )
                },
                stringResolver = stringResolver,
            )
        private val dpiSuiteController =
            DiagnosticsDpiSuiteController(
                scope = viewModelScope,
                appSettingsRepository = appSettingsRepository,
                dnsIntegrityChecker = probeDependencies.dnsIntegrityChecker,
                dnsAvailabilitySurvey = probeDependencies.dnsAvailabilitySurvey,
                domainReachabilityScanner = probeDependencies.domainReachabilityScanner,
                tcp16FatHeaderProbe = probeDependencies.tcp16FatHeaderProbe,
                assetLoader = probeDependencies.assetLoader,
                diagnosticsFiles = diagnosticsFiles,
                tlsKeylogRunFinalizer = probeDependencies.tlsKeylogRunFinalizer,
                echTlsHandshake = probeDependencies.echTlsHandshake,
                stringResolver = stringResolver,
            )
        private val pluggableTransportController =
            DiagnosticsPluggableTransportController(
                scope = viewModelScope,
                appSettingsRepository = appSettingsRepository,
                assetLoader = probeDependencies.assetLoader,
                stringResolver = stringResolver,
            )
        private val xrayProviderController =
            DiagnosticsXrayProviderController(
                scope = viewModelScope,
                serviceStateStore = serviceStateStore,
                probeCoordinator = xrayProviderProbeCoordinator,
            )
        private val remoteDeviceAcceptance = probeDependencies.remoteDeviceAcceptance

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
        val rootModeEnabled: StateFlow<Boolean> =
            appSettingsRepository.settings
                .map { it.rootModeEnabled }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = false,
                )
        val dnsIntegrityTool: StateFlow<DiagnosticsDnsIntegrityToolUiModel> = dpiToolsController.dnsIntegrityTool
        val dnsAvailabilityTool: StateFlow<DiagnosticsDnsAvailabilityToolUiModel> =
            dpiToolsController.dnsAvailabilityTool
        val domainReachabilityTool: StateFlow<DiagnosticsDomainReachabilityToolUiModel> =
            dpiToolsController.domainReachabilityTool
        val rknBlockDiagnosisTool: StateFlow<DiagnosticsRknBlockDiagnosisToolUiModel> =
            dpiToolsController.rknBlockDiagnosisTool
        val compressionProbeTool: StateFlow<DiagnosticsCompressionProbeToolUiModel> =
            dpiToolsController.compressionProbeTool
        val cidrWhitelistTool: StateFlow<DiagnosticsCidrWhitelistToolUiModel> =
            cidrWhitelistController.tool
        val ipv4WhitelistTool: StateFlow<DiagnosticsIpv4WhitelistToolUiModel> =
            ipv4WhitelistController.tool
        val tcp16FatHeaderTool: StateFlow<DiagnosticsTcp16FatHeaderToolUiModel> =
            dpiToolsController.tcp16FatHeaderTool
        val allowlistSniTool: StateFlow<DiagnosticsAllowlistSniToolUiModel> =
            dpiToolsController.allowlistSniTool
        val pluggableTransportTool: StateFlow<DiagnosticsPluggableTransportToolUiModel> =
            pluggableTransportController.tool
        val byohCompatibilityTool: StateFlow<DiagnosticsByohCompatibilityToolUiModel> =
            dpiToolsController.byohCompatibilityTool
        val dpiSuiteTool: StateFlow<DiagnosticsDpiSuiteToolUiModel> =
            dpiSuiteController.tool
        val xrayProviderSnapshot: StateFlow<XrayProviderSnapshot?> = xrayProviderController.snapshot
        val xrayProviderProbeReport: StateFlow<XrayProviderProbeReport?> = xrayProviderController.probeReport
        val xrayProviderProbeRunning: StateFlow<Boolean> = xrayProviderController.probeRunning
        val remoteDeviceAcceptanceReport = remoteDeviceAcceptance.report

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
                stringResolver = stringResolver,
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

        fun initialize(autoStartScan: Boolean? = null) {
            if (initialized) {
                return
            }
            initialized = true
            diagnosticsViewModelBootstrapper.initialize(
                scope = viewModelScope,
                initializeScanActions = scanActions::initialize,
            )
            if ((autoStartScan ?: this.autoStartScan) && !autoStartScanHandled) {
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

        fun runScan() = startRawScan()

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

        fun runCidrWhitelistDetection() = cidrWhitelistController.run()

        fun cacheIpv4WhitelistSubnets() = ipv4WhitelistController.cacheSubnets()

        fun checkIpv4WhitelistSubnets() = ipv4WhitelistController.checkSubnets()

        fun saveIpv4WhitelistCsv() = ipv4WhitelistController.saveCsv()

        fun runTcp16FatHeaderProbe() = dpiToolsController.runTcp16FatHeaderProbe()

        fun runAllowlistSniFinder() = dpiToolsController.runAllowlistSniFinder()

        fun runPluggableTransportProbe() = pluggableTransportController.run()

        fun runRknBlockDiagnosis() = dpiToolsController.runRknBlockDiagnosis()

        fun setRknSelfInfoEnabled(enabled: Boolean) = dpiToolsController.setRknSelfInfoEnabled(enabled)

        fun setCompressionProbeZstdEnabled(enabled: Boolean) =
            dpiToolsController.setCompressionProbeZstdEnabled(enabled)

        fun setByohDstIp(value: String) = dpiToolsController.setByohDstIp(value)

        fun setByohUrlPath(value: String) = dpiToolsController.setByohUrlPath(value)

        fun setByohSyntheticFixtureEnabled(enabled: Boolean) =
            dpiToolsController.setByohSyntheticFixtureEnabled(enabled)

        fun runByohCompatibilityCheck() = dpiToolsController.runByohCompatibilityCheck()

        fun setDpiSuiteProbeEnabled(
            kind: DpiProbeKind,
            enabled: Boolean,
        ) = dpiSuiteController.setProbeEnabled(kind, enabled)

        fun setDpiSuiteCustomDomains(value: String) = dpiSuiteController.setCustomDomains(value)

        fun adjustDpiSuiteConcurrency(delta: Int) = dpiSuiteController.adjustConcurrency(delta)

        fun runDpiProbeSuite() = dpiSuiteController.run()

        fun cancelDpiProbeSuite() = dpiSuiteController.cancel()

        fun runXrayProviderProbe() = xrayProviderController.runProbe()

        fun runRemoteDeviceAcceptance() = remoteDeviceAcceptance.start(viewModelScope)

        fun shareRemoteDeviceAcceptance() = remoteDeviceAcceptance.share(_effects::tryEmit)
    }

private fun DiagnosticsSessionRowUiModel.toLastScanSummary(): String =
    listOf(
        metrics.firstOrNull { it.label.contains("confidence", ignoreCase = true) }?.value ?: status,
        startedAtLabel,
    ).joinToString(" · ")
