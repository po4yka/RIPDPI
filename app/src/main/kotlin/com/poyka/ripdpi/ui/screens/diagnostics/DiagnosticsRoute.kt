package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsAllowlistSniToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsByohCompatibilityToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsCidrWhitelistToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsCompressionProbeToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDnsAvailabilityToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDnsIntegrityToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDomainReachabilityToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDpiSuiteToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDpiToolsUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsEffect
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsRknBlockDiagnosisToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSection
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTcp16FatHeaderToolUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsViewModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.PcapCaptureViewModel
import com.poyka.ripdpi.ui.screens.status.StatusDiagnosticsUiState
import com.poyka.ripdpi.ui.screens.xray.XrayProviderToolUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    callbacks: DiagnosticsRouteCallbacks = DiagnosticsRouteCallbacks(),
    modifier: Modifier = Modifier,
    initialSection: DiagnosticsSection? = null,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
    pcapCaptureViewModel: PcapCaptureViewModel? = null,
    topBarExtraActions: @Composable () -> Unit = {},
    homeDiagnostics: StatusDiagnosticsUiState = StatusDiagnosticsUiState(),
) {
    val localeTags = LocalConfiguration.current.locales.toLanguageTags()
    LaunchedEffect(viewModel, localeTags) {
        viewModel.onLocaleChanged(localeTags)
    }
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.screenUiState.collectAsStateWithLifecycle()
    val toolsStateFlow = remember(viewModel) { viewModel.toolsRouteStateFlow() }
    val toolsState by toolsStateFlow.collectAsStateWithLifecycle(DiagnosticsToolsRouteState())
    val pcapRecording =
        pcapCaptureViewModel?.let { captureViewModel ->
            captureViewModel.recording.collectAsStateWithLifecycle().value
        } ?: toolsState.pcapRecording
    val pagerState =
        rememberPagerState(initialPage = uiState.selectedSection.ordinal) { DiagnosticsSection.entries.size }
    val snackbarHostState = remember { SnackbarHostState() }

    DiagnosticsRoutePagerSync(uiState.selectedSection, pagerState, viewModel)
    DiagnosticsRouteEffects(viewModel, snackbarHostState, callbacks, initialSection)

    DiagnosticsScreen(
        uiState = uiState.copy(homeDiagnostics = homeDiagnostics),
        pagerState = pagerState,
        snackbarHostState = snackbarHostState,
        actions = rememberDiagnosticsScreenActions(viewModel, callbacks, pcapCaptureViewModel, homeDiagnostics),
        dpiTools = toolsState.dpiTools,
        cidrWhitelistTool = toolsState.cidrWhitelist,
        ipv4WhitelistTool = toolsState.ipv4Whitelist,
        pluggableTransportTool = toolsState.pluggableTransport,
        xrayProvider = toolsState.xrayProvider,
        remoteDeviceAcceptance = toolsState.remoteDeviceAcceptance,
        rootModeEnabled = toolsState.rootModeEnabled,
        pcapRecording = pcapRecording,
        topBarExtraActions = topBarExtraActions,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticsRoutePagerSync(
    selectedSection: DiagnosticsSection,
    pagerState: androidx.compose.foundation.pager.PagerState,
    viewModel: DiagnosticsViewModel,
) {
    LaunchedEffect(selectedSection) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedSection.ordinal) {
            pagerState.animateScrollToPage(selectedSection.ordinal)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val section = DiagnosticsSection.entries[pagerState.settledPage]
        if (selectedSection != section) {
            viewModel.selectionActions.selectSection(section)
        }
    }
}

@Composable
private fun DiagnosticsRouteEffects(
    viewModel: DiagnosticsViewModel,
    snackbarHostState: SnackbarHostState,
    callbacks: DiagnosticsRouteCallbacks,
    initialSection: DiagnosticsSection?,
) {
    val currentCallbacks by rememberUpdatedState(callbacks)
    val performHaptic = rememberRipDpiHapticPerformer()
    val snackbarScope = rememberCoroutineScope()

    LifecycleEventEffect(viewModel.effects) { effect ->
        handleDiagnosticsEffect(effect, currentCallbacks, snackbarHostState, performHaptic, snackbarScope)
    }

    LaunchedEffect(initialSection) {
        initialSection?.let {
            viewModel.selectionActions.selectSection(it)
            currentCallbacks.onInitialSectionHandled()
        }
    }
}

private fun DiagnosticsViewModel.toolsRouteStateFlow(): Flow<DiagnosticsToolsRouteState> {
    val baseFlow =
        combine(
            pcapRecording,
            dpiToolsRouteFlow(),
            cidrWhitelistTool,
            ipv4WhitelistTool,
            pluggableTransportTool,
        ) { pcapRecording, dpiTools, cidrWhitelist, ipv4Whitelist, pluggableTransport ->
            DiagnosticsToolsRouteState(
                pcapRecording = pcapRecording,
                dpiTools = dpiTools,
                cidrWhitelist = cidrWhitelist,
                ipv4Whitelist = ipv4Whitelist,
                pluggableTransport = pluggableTransport,
            )
        }
    val xrayProviderFlow =
        combine(
            xrayProviderSnapshot,
            xrayProviderProbeReport,
            xrayProviderProbeRunning,
        ) { snapshot, report, running ->
            XrayProviderToolUiModel(
                snapshot = snapshot,
                probeReport = report,
                probeRunning = running,
            )
        }
    return combine(
        baseFlow,
        rootModeEnabled,
        xrayProviderFlow,
        remoteDeviceAcceptanceReport,
    ) { state, rootMode, xrayProvider, remoteDeviceAcceptance ->
        state.copy(
            rootModeEnabled = rootMode,
            xrayProvider = xrayProvider,
            remoteDeviceAcceptance = remoteDeviceAcceptance,
        )
    }
}

private fun DiagnosticsViewModel.dpiToolsRouteFlow(): Flow<DiagnosticsDpiToolsUiModel> {
    val basicTools =
        combine(
            dnsIntegrityTool,
            dnsAvailabilityTool,
            domainReachabilityTool,
            rknBlockDiagnosisTool,
            compressionProbeTool,
        ) { dnsIntegrity, dnsAvailability, domainReachability, rknBlockDiagnosis, compressionProbe ->
            DiagnosticsBasicDpiTools(
                dnsIntegrity = dnsIntegrity,
                dnsAvailability = dnsAvailability,
                domainReachability = domainReachability,
                rknBlockDiagnosis = rknBlockDiagnosis,
                compressionProbe = compressionProbe,
            )
        }
    val advancedTools =
        combine(
            tcp16FatHeaderTool,
            allowlistSniTool,
            byohCompatibilityTool,
            dpiSuiteTool,
        ) { tcp16FatHeader, allowlistSni, byohCompatibility, dpiSuite ->
            DiagnosticsAdvancedDpiTools(
                tcp16FatHeader = tcp16FatHeader,
                allowlistSni = allowlistSni,
                byohCompatibility = byohCompatibility,
                dpiSuite = dpiSuite,
            )
        }
    return combine(basicTools, advancedTools) { basic, advanced ->
        DiagnosticsDpiToolsUiModel(
            dnsIntegrity = basic.dnsIntegrity,
            dnsAvailability = basic.dnsAvailability,
            domainReachability = basic.domainReachability,
            rknBlockDiagnosis = basic.rknBlockDiagnosis,
            compressionProbe = basic.compressionProbe,
            tcp16FatHeader = advanced.tcp16FatHeader,
            allowlistSni = advanced.allowlistSni,
            byohCompatibility = advanced.byohCompatibility,
            dpiSuite = advanced.dpiSuite,
        )
    }
}

// A snackbar suspends until it is dismissed, so it runs in snackbarScope: export and share effects
// that arrive while one is visible must not wait for it.
private fun handleDiagnosticsEffect(
    effect: DiagnosticsEffect,
    callbacks: DiagnosticsRouteCallbacks,
    snackbarHostState: SnackbarHostState,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
    snackbarScope: CoroutineScope,
) {
    when (effect) {
        is DiagnosticsEffect.PrepareExportRequested -> {
            callbacks.onPrepareExport(effect.preparation)
        }

        is DiagnosticsEffect.ShareTextRequested -> {
            callbacks.onShareText(effect.title, effect.body)
        }

        is DiagnosticsEffect.ScanQueued -> {
            snackbarScope.launch {
                showDiagnosticsSnackbar(snackbarHostState, effect.message, RipDpiSnackbarTone.Info)
            }
        }

        is DiagnosticsEffect.ScanCompleted -> {
            if (effect.hapticFeedbackEnabled) performHaptic(effect.tone.toHapticFeedback())
            snackbarScope.launch {
                val result =
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.summary,
                        actionLabel = effect.actionLabel,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                        tone = effect.tone.toSnackbarTone(),
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    handleSnackbarAction(effect.action, callbacks)
                }
            }
        }

        is DiagnosticsEffect.ScanStartFailed -> {
            if (effect.hapticFeedbackEnabled) performHaptic(RipDpiHapticFeedback.Error)
            snackbarScope.launch {
                val result =
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.message,
                        actionLabel = effect.actionLabel,
                        tone = RipDpiSnackbarTone.Error,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                if (result == SnackbarResult.ActionPerformed) handleSnackbarAction(effect.action, callbacks)
            }
        }
    }
}

private suspend fun showDiagnosticsSnackbar(
    snackbarHostState: SnackbarHostState,
    message: String,
    tone: RipDpiSnackbarTone,
) {
    snackbarHostState.showRipDpiSnackbar(
        message = message,
        tone = tone,
        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
    )
}

private fun handleSnackbarAction(
    action: DiagnosticsEffect.SnackbarAction?,
    callbacks: DiagnosticsRouteCallbacks,
) {
    when (action) {
        DiagnosticsEffect.SnackbarAction.OpenDnsSettings -> callbacks.onOpenDnsSettings()
        DiagnosticsEffect.SnackbarAction.RequestLocalNetwork -> callbacks.onRequestLocalNetworkPermission()
        null -> Unit
    }
}

private fun DiagnosticsTone.toHapticFeedback(): RipDpiHapticFeedback =
    when (this) {
        DiagnosticsTone.Positive -> RipDpiHapticFeedback.Success
        DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiHapticFeedback.Error
        else -> RipDpiHapticFeedback.Acknowledge
    }

private fun DiagnosticsTone.toSnackbarTone(): RipDpiSnackbarTone =
    when (this) {
        DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiSnackbarTone.Warning
        else -> RipDpiSnackbarTone.Default
    }

@Composable
private fun rememberDiagnosticsScreenActions(
    viewModel: DiagnosticsViewModel,
    callbacks: DiagnosticsRouteCallbacks,
    pcapCaptureViewModel: PcapCaptureViewModel?,
    homeDiagnostics: StatusDiagnosticsUiState,
): DiagnosticsScreenActions =
    DiagnosticsScreenActions(
        onSelectSection = remember(viewModel) { viewModel.selectionActions::selectSection },
        onSelectProfile = remember(viewModel) { viewModel.selectionActions::selectProfile },
        onSelectiveMatrixHostsChanged = remember(viewModel) { viewModel.selectionActions::updateSelectiveMatrixHosts },
        onRunScan = remember(viewModel) { viewModel::runScan },
        onRunRawScan = remember(viewModel) { viewModel.scanActions::startRawScan },
        onRunInPathScan = remember(viewModel) { viewModel.scanActions::startInPathScan },
        onWaitForHiddenProbeAndRun = remember(viewModel) { viewModel.scanActions::waitForHiddenProbeAndRun },
        onCancelHiddenProbeAndRun = remember(viewModel) { viewModel.scanActions::cancelHiddenProbeAndRun },
        onDismissHiddenProbeConflictDialog =
            remember(
                viewModel,
            ) { viewModel.scanActions::dismissHiddenProbeConflictDialog },
        onConfirmSensitiveProfileRun = remember(viewModel) { viewModel.scanActions::confirmSensitiveProfileRun },
        onDismissSensitiveProfileConsentDialog =
            remember(viewModel) { viewModel.scanActions::dismissSensitiveProfileConsentDialog },
        onKeepResolverRecommendation = remember(viewModel) { viewModel::keepResolverRecommendationForSession },
        onSaveResolverRecommendation = remember(viewModel) { viewModel::saveResolverRecommendation },
        onSelectSession = remember(viewModel) { viewModel.selectionActions::selectSession },
        onDismissSessionDetail = remember(viewModel) { viewModel.selectionActions::dismissSessionDetail },
        onSelectStrategyProbeCandidate =
            remember(
                viewModel,
            ) { viewModel.selectionActions::selectStrategyProbeCandidate },
        onDismissStrategyProbeCandidate =
            remember(
                viewModel,
            ) { viewModel.selectionActions::dismissStrategyProbeCandidate },
        onSelectApproachMode = remember(viewModel) { viewModel.selectionActions::selectApproachMode },
        onSelectApproach = remember(viewModel) { viewModel.selectionActions::selectApproach },
        onDismissApproachDetail = remember(viewModel) { viewModel.selectionActions::dismissApproachDetail },
        onSelectEvent = remember(viewModel) { viewModel.selectionActions::selectEvent },
        onDismissEventDetail = remember(viewModel) { viewModel.selectionActions::dismissEventDetail },
        onSelectProbe = remember(viewModel) { viewModel.selectionActions::selectProbe },
        onDismissProbeDetail = remember(viewModel) { viewModel.selectionActions::dismissProbeDetail },
        onToggleSensitiveSessionDetails =
            remember(
                viewModel,
            ) { viewModel.selectionActions::toggleSensitiveSessionDetails },
        onSessionSearch = remember(viewModel) { viewModel.filterActions::setSessionSearch },
        onToggleEventFilter = remember(viewModel) { viewModel.filterActions::toggleEventFilter },
        onEventSearch = remember(viewModel) { viewModel.filterActions::setEventSearch },
        onEventAutoScroll = remember(viewModel) { viewModel.filterActions::setEventAutoScroll },
        onShareSummary = remember(viewModel) { viewModel.shareActions::shareSummary },
        onShareArchive = remember(viewModel) { viewModel.shareActions::shareArchive },
        onSaveArchive = remember(viewModel) { viewModel.shareActions::saveArchive },
        onTogglePcapRecording = rememberPcapToggleAction(viewModel, pcapCaptureViewModel),
        onRunDnsIntegrityCheck = remember(viewModel) { viewModel.dpiToolsController::runDnsIntegrityCheck },
        onRunDnsAvailabilitySurvey = remember(viewModel) { viewModel.dpiToolsController::runDnsAvailabilitySurvey },
        onRunDomainReachabilityScan = remember(viewModel) { viewModel.dpiToolsController::runDomainReachabilityScan },
        onRunCompressionProbe = remember(viewModel) { viewModel.dpiToolsController::runCompressionProbe },
        onRunCidrWhitelistDetection = remember(viewModel) { viewModel.cidrWhitelistController::run },
        onCacheIpv4WhitelistSubnets = remember(viewModel) { viewModel.ipv4WhitelistController::cacheSubnets },
        onCheckIpv4WhitelistSubnets = remember(viewModel) { viewModel.ipv4WhitelistController::checkSubnets },
        onSaveIpv4WhitelistCsv = remember(viewModel) { viewModel.ipv4WhitelistController::saveCsv },
        onRunTcp16FatHeaderProbe = remember(viewModel) { viewModel.dpiToolsController::runTcp16FatHeaderProbe },
        onRunAllowlistSniFinder = remember(viewModel) { viewModel.dpiToolsController::runAllowlistSniFinder },
        onRunPluggableTransportProbe = remember(viewModel) { viewModel.pluggableTransportController::run },
        onRunByohCompatibilityCheck = remember(viewModel) { viewModel.dpiToolsController::runByohCompatibilityCheck },
        onRunRknBlockDiagnosis = remember(viewModel) { viewModel.dpiToolsController::runRknBlockDiagnosis },
        onRknSelfInfoEnabledChange = remember(viewModel) { viewModel.dpiToolsController::setRknSelfInfoEnabled },
        onCompressionProbeZstdEnabledChange =
            remember(viewModel) {
                viewModel.dpiToolsController::setCompressionProbeZstdEnabled
            },
        onByohDstIpChange = remember(viewModel) { viewModel.dpiToolsController::setByohDstIp },
        onByohUrlPathChange = remember(viewModel) { viewModel.dpiToolsController::setByohUrlPath },
        onByohSyntheticFixtureEnabledChange =
            remember(viewModel) {
                viewModel.dpiToolsController::setByohSyntheticFixtureEnabled
            },
        onDpiSuiteProbeEnabledChange = remember(viewModel) { viewModel.dpiSuiteController::setProbeEnabled },
        onDpiSuiteCustomDomainsChange = remember(viewModel) { viewModel.dpiSuiteController::setCustomDomains },
        onDpiSuiteConcurrencyDelta = remember(viewModel) { viewModel.dpiSuiteController::adjustConcurrency },
        onRunDpiProbeSuite = remember(viewModel) { viewModel.dpiSuiteController::run },
        onCancelDpiProbeSuite = remember(viewModel) { viewModel.dpiSuiteController::cancel },
        onRunXrayProviderProbe = remember(viewModel) { viewModel.xrayProviderController::runProbe },
        onRunRemoteDeviceAcceptance = remember(viewModel) { viewModel::runRemoteDeviceAcceptance },
        onShareRemoteDeviceAcceptance = remember(viewModel) { viewModel::shareRemoteDeviceAcceptance },
    ).withNavigationCallbacks(viewModel, callbacks, homeDiagnostics)

@Composable
private fun rememberPcapToggleAction(
    viewModel: DiagnosticsViewModel,
    pcapCaptureViewModel: PcapCaptureViewModel?,
): () -> Unit =
    pcapCaptureViewModel?.let { captureViewModel ->
        remember(captureViewModel) { captureViewModel::toggle }
    } ?: remember(viewModel) { viewModel::togglePcapRecording }

private data class DiagnosticsToolsRouteState(
    val pcapRecording: Boolean = false,
    val rootModeEnabled: Boolean = false,
    val dpiTools: DiagnosticsDpiToolsUiModel = DiagnosticsDpiToolsUiModel(),
    val cidrWhitelist: DiagnosticsCidrWhitelistToolUiModel = DiagnosticsCidrWhitelistToolUiModel(),
    val ipv4Whitelist: DiagnosticsIpv4WhitelistToolUiModel = DiagnosticsIpv4WhitelistToolUiModel(),
    val pluggableTransport: DiagnosticsPluggableTransportToolUiModel = DiagnosticsPluggableTransportToolUiModel(),
    val xrayProvider: XrayProviderToolUiModel = XrayProviderToolUiModel(),
    val remoteDeviceAcceptance: RemoteDeviceAcceptanceReport = RemoteDeviceAcceptanceReport(),
)

private data class DiagnosticsBasicDpiTools(
    val dnsIntegrity: DiagnosticsDnsIntegrityToolUiModel,
    val dnsAvailability: DiagnosticsDnsAvailabilityToolUiModel,
    val domainReachability: DiagnosticsDomainReachabilityToolUiModel,
    val rknBlockDiagnosis: DiagnosticsRknBlockDiagnosisToolUiModel,
    val compressionProbe: DiagnosticsCompressionProbeToolUiModel,
)

private data class DiagnosticsAdvancedDpiTools(
    val tcp16FatHeader: DiagnosticsTcp16FatHeaderToolUiModel,
    val allowlistSni: DiagnosticsAllowlistSniToolUiModel,
    val byohCompatibility: DiagnosticsByohCompatibilityToolUiModel,
    val dpiSuite: DiagnosticsDpiSuiteToolUiModel,
)

data class DiagnosticsRouteCallbacks(
    val onPrepareExport: (com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation) -> Unit = {},
    val onShareText: (String, String) -> Unit = { _, _ -> },
    val onSaveLogs: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onOpenConnectionHealth: () -> Unit = {},
    val onOpenAdvancedSettings: () -> Unit = {},
    val onOpenDnsSettings: () -> Unit = {},
    val onOpenDetectionCheck: () -> Unit = {},
    val onRequestVpnPermission: () -> Unit = {},
    val onRequestLocalNetworkPermission: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onOpenModeEditor: () -> Unit = {},
    val onOpenOwnedStackBrowser: (String) -> Unit = {},
    val onOpenPcapCaptureList: () -> Unit = {},
    val onOpenPastReplays: () -> Unit = {},
    val onReplayProbe: (Route.ReplayFailure) -> Unit = {},
    val onInitialSectionHandled: () -> Unit = {},
    val onCancelHomeAnalysis: () -> Unit = {},
    val onCheckNetwork: () -> Unit = {},
    val onStartVerifiedVpn: () -> Unit = {},
    val onDismissVerification: () -> Unit = {},
)
