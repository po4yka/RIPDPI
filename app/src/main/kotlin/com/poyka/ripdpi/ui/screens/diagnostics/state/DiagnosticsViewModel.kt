package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        private val diagnosticsInteractionDependencies: DiagnosticsInteractionDependencies,
        private val diagnosticsContextDependencies: DiagnosticsContextDependencies,
        private val diagnosticsBootstrapper: DiagnosticsBootstrapper,
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
        private val localeState = MutableStateFlow("")
        private val _effects =
            MutableSharedFlow<DiagnosticsEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val effects = _effects.bufferForUiLifecycle(viewModelScope)
        internal val dpiToolsController =
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
        internal val cidrWhitelistController =
            DiagnosticsCidrWhitelistController(
                scope = viewModelScope,
                detector = probeDependencies.cidrWhitelistDetector,
                stringResolver = stringResolver,
            )
        internal val ipv4WhitelistController =
            DiagnosticsIpv4WhitelistController(
                scope = viewModelScope,
                discoverer = probeDependencies.ipv4WhitelistedSubnetDiscoverer,
                shareCsv = { csv ->
                    _effects.tryEmit(
                        DiagnosticsEffect.ShareTextRequested(
                            title = stringResolver.getString(R.string.diagnostics_ipv4_whitelist_csv_title),
                            body = csv,
                        ),
                    )
                },
                stringResolver = stringResolver,
            )
        internal val dpiSuiteController =
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
        internal val pluggableTransportController =
            DiagnosticsPluggableTransportController(
                scope = viewModelScope,
                appSettingsRepository = appSettingsRepository,
                assetLoader = probeDependencies.assetLoader,
                stringResolver = stringResolver,
            )
        internal val xrayProviderController =
            DiagnosticsXrayProviderController(
                scope = viewModelScope,
                serviceStateStore = serviceStateStore,
                probeCoordinator = xrayProviderProbeCoordinator,
            )
        private val remoteDeviceAcceptance = probeDependencies.remoteDeviceAcceptance

        private val uiStates =
            diagnosticsUiStateAssembler.assemble(
                scope = viewModelScope,
                interactionDependencies = diagnosticsInteractionDependencies,
                contextDependencies = diagnosticsContextDependencies,
                selectionState = selectionState,
                filterState = filterState,
                sessionDetailState = sessionDetailState,
                scanLifecycleState = scanLifecycleState,
                localeState = localeState,
            )

        val uiState: StateFlow<DiagnosticsUiState> = uiStates.full
        val screenUiState: StateFlow<DiagnosticsScreenUiState> = uiStates.screen

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
                diagnosticsResolverActions = diagnosticsInteractionDependencies.diagnosticsResolverActions,
                uiStateFactory = uiStateFactory,
                effects = _effects,
                hapticFeedbackEnabled = { !appSettingsRepository.snapshot().hapticFeedbackDisabled },
                currentUiState = { uiState.value },
            )

        internal val selectionActions =
            DiagnosticsSelectionActions(mutations, selectionState, sessionDetailState)

        internal val filterActions = DiagnosticsFilterActions(filterState)

        internal val scanActions =
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

        internal val shareActions = DiagnosticsShareActions(mutations)

        fun onLocaleChanged(localeTags: String) {
            localeState.value = localeTags
        }

        fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            viewModelScope.launch { diagnosticsBootstrapper.initialize() }
            scanActions.initialize()
            if (autoStartScan && !autoStartScanHandled) {
                autoStartScanHandled = true
                runScan()
            }
        }

        fun runScan() = scanActions.startRawScan()

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

        fun togglePcapRecording() {
            _pcapRecording.value = !_pcapRecording.value
        }

        fun runRemoteDeviceAcceptance() = remoteDeviceAcceptance.start(viewModelScope)

        fun shareRemoteDeviceAcceptance() = remoteDeviceAcceptance.share(_effects::tryEmit)
    }
