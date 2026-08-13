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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsByohCompatibilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiToolsUiModel
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsRknBlockDiagnosisToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.activities.PcapCaptureViewModel
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.screens.xray.XrayProviderToolUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    callbacks: DiagnosticsRouteCallbacks = DiagnosticsRouteCallbacks(),
    modifier: Modifier = Modifier,
    initialSection: DiagnosticsSection? = null,
    autoStartScan: Boolean? = null,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
    pcapCaptureViewModel: PcapCaptureViewModel? = null,
    topBarExtraActions: @Composable () -> Unit = {},
) {
    LaunchedEffect(viewModel, autoStartScan) {
        viewModel.initialize(autoStartScan)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
        uiState = uiState,
        pagerState = pagerState,
        snackbarHostState = snackbarHostState,
        actions = rememberDiagnosticsScreenActions(viewModel, callbacks, pcapCaptureViewModel),
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
            viewModel.selectSection(section)
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

    LifecycleEventEffect(viewModel.effects) { effect ->
        handleDiagnosticsEffect(effect, currentCallbacks, snackbarHostState, performHaptic)
    }

    LaunchedEffect(initialSection) {
        initialSection?.let {
            viewModel.selectSection(it)
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

private suspend fun handleDiagnosticsEffect(
    effect: DiagnosticsEffect,
    callbacks: DiagnosticsRouteCallbacks,
    snackbarHostState: SnackbarHostState,
    performHaptic: (RipDpiHapticFeedback) -> Unit,
) {
    when (effect) {
        is DiagnosticsEffect.SaveArchiveRequested -> {
            callbacks.onSaveArchive(effect.absolutePath, effect.fileName)
        }

        is DiagnosticsEffect.ShareArchiveRequested -> {
            callbacks.onShareArchive(effect.absolutePath, effect.fileName)
        }

        is DiagnosticsEffect.ShareSummaryRequested -> {
            callbacks.onShareSummary(effect.title, effect.body)
        }

        is DiagnosticsEffect.ScanStarted -> {
            performHaptic(RipDpiHapticFeedback.Acknowledge)
            showDiagnosticsSnackbar(snackbarHostState, effect.scanTypeLabel, RipDpiSnackbarTone.Info)
        }

        is DiagnosticsEffect.ScanQueued -> {
            showDiagnosticsSnackbar(snackbarHostState, effect.message, RipDpiSnackbarTone.Info)
        }

        is DiagnosticsEffect.ScanCompleted -> {
            performHaptic(effect.tone.toHapticFeedback())
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

        is DiagnosticsEffect.ScanStartFailed -> {
            performHaptic(RipDpiHapticFeedback.Error)
            showDiagnosticsSnackbar(snackbarHostState, effect.message, RipDpiSnackbarTone.Error)
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
): DiagnosticsScreenActions =
    DiagnosticsScreenActions(
        onSelectSection = remember(viewModel) { viewModel::selectSection },
        onSelectProfile = remember(viewModel) { viewModel::selectProfile },
        onRunScan = remember(viewModel) { viewModel::runScan },
        onRunRawScan = remember(viewModel) { viewModel::startRawScan },
        onRunInPathScan = remember(viewModel) { viewModel::startInPathScan },
        onWaitForHiddenProbeAndRun = remember(viewModel) { viewModel::waitForHiddenProbeAndRun },
        onCancelHiddenProbeAndRun = remember(viewModel) { viewModel::cancelHiddenProbeAndRun },
        onDismissHiddenProbeConflictDialog = remember(viewModel) { viewModel::dismissHiddenProbeConflictDialog },
        onConfirmSensitiveProfileRun = remember(viewModel) { viewModel::confirmSensitiveProfileRun },
        onDismissSensitiveProfileConsentDialog =
            remember(viewModel) { viewModel::dismissSensitiveProfileConsentDialog },
        onCancelScan = remember(viewModel) { viewModel::cancelScan },
        onKeepResolverRecommendation = remember(viewModel) { viewModel::keepResolverRecommendationForSession },
        onSaveResolverRecommendation = remember(viewModel) { viewModel::saveResolverRecommendation },
        onSelectSession = remember(viewModel) { viewModel::selectSession },
        onDismissSessionDetail = remember(viewModel) { viewModel::dismissSessionDetail },
        onSelectStrategyProbeCandidate = remember(viewModel) { viewModel::selectStrategyProbeCandidate },
        onDismissStrategyProbeCandidate = remember(viewModel) { viewModel::dismissStrategyProbeCandidate },
        onSelectApproachMode = remember(viewModel) { viewModel::selectApproachMode },
        onSelectApproach = remember(viewModel) { viewModel::selectApproach },
        onDismissApproachDetail = remember(viewModel) { viewModel::dismissApproachDetail },
        onSelectEvent = remember(viewModel) { viewModel::selectEvent },
        onDismissEventDetail = remember(viewModel) { viewModel::dismissEventDetail },
        onSelectProbe = remember(viewModel) { viewModel::selectProbe },
        onDismissProbeDetail = remember(viewModel) { viewModel::dismissProbeDetail },
        onToggleSensitiveSessionDetails = remember(viewModel) { viewModel::toggleSensitiveSessionDetails },
        onSessionPathFilter = remember(viewModel) { viewModel::setSessionPathModeFilter },
        onSessionStatusFilter = remember(viewModel) { viewModel::setSessionStatusFilter },
        onSessionSearch = remember(viewModel) { viewModel::setSessionSearch },
        onToggleEventFilter = remember(viewModel) { viewModel::toggleEventFilter },
        onEventSearch = remember(viewModel) { viewModel::setEventSearch },
        onEventAutoScroll = remember(viewModel) { viewModel::setEventAutoScroll },
        onShareSummary = remember(viewModel) { viewModel::shareSummary },
        onShareArchive = remember(viewModel) { viewModel::shareArchive },
        onSaveArchive = remember(viewModel) { viewModel::saveArchive },
        onSaveLogs = callbacks.onSaveLogs,
        onOpenLogs = callbacks.onOpenLogs,
        onOpenConnectionHealth = callbacks.onOpenConnectionHealth,
        onOpenAdvancedSettings = callbacks.onOpenAdvancedSettings,
        onOpenDnsSettings = callbacks.onOpenDnsSettings,
        onOpenDetectionCheck = callbacks.onOpenDetectionCheck,
        onRequestVpnPermission = callbacks.onRequestVpnPermission,
        onOpenHistory = callbacks.onOpenHistory,
        onOpenModeEditor = callbacks.onOpenModeEditor,
        onApplyRecommendedPath = callbacks.onOpenModeEditor,
        onOpenOwnedStackBrowser = callbacks.onOpenOwnedStackBrowser,
        onOpenPcapCaptureList = callbacks.onOpenPcapCaptureList,
        onOpenPastReplays = callbacks.onOpenPastReplays,
        onTogglePcapRecording = rememberPcapToggleAction(viewModel, pcapCaptureViewModel),
        onRunDnsIntegrityCheck = remember(viewModel) { viewModel::runDnsIntegrityCheck },
        onRunDnsAvailabilitySurvey = remember(viewModel) { viewModel::runDnsAvailabilitySurvey },
        onRunDomainReachabilityScan = remember(viewModel) { viewModel::runDomainReachabilityScan },
        onRunCompressionProbe = remember(viewModel) { viewModel::runCompressionProbe },
        onRunCidrWhitelistDetection = remember(viewModel) { viewModel::runCidrWhitelistDetection },
        onCacheIpv4WhitelistSubnets = remember(viewModel) { viewModel::cacheIpv4WhitelistSubnets },
        onCheckIpv4WhitelistSubnets = remember(viewModel) { viewModel::checkIpv4WhitelistSubnets },
        onSaveIpv4WhitelistCsv = remember(viewModel) { viewModel::saveIpv4WhitelistCsv },
        onRunTcp16FatHeaderProbe = remember(viewModel) { viewModel::runTcp16FatHeaderProbe },
        onRunAllowlistSniFinder = remember(viewModel) { viewModel::runAllowlistSniFinder },
        onRunPluggableTransportProbe = remember(viewModel) { viewModel::runPluggableTransportProbe },
        onRunByohCompatibilityCheck = remember(viewModel) { viewModel::runByohCompatibilityCheck },
        onRunRknBlockDiagnosis = remember(viewModel) { viewModel::runRknBlockDiagnosis },
        onRknSelfInfoEnabledChange = remember(viewModel) { viewModel::setRknSelfInfoEnabled },
        onCompressionProbeZstdEnabledChange = remember(viewModel) { viewModel::setCompressionProbeZstdEnabled },
        onByohDstIpChange = remember(viewModel) { viewModel::setByohDstIp },
        onByohUrlPathChange = remember(viewModel) { viewModel::setByohUrlPath },
        onByohSyntheticFixtureEnabledChange = remember(viewModel) { viewModel::setByohSyntheticFixtureEnabled },
        onDpiSuiteProbeEnabledChange = remember(viewModel) { viewModel::setDpiSuiteProbeEnabled },
        onDpiSuiteCustomDomainsChange = remember(viewModel) { viewModel::setDpiSuiteCustomDomains },
        onDpiSuiteConcurrencyDelta = remember(viewModel) { viewModel::adjustDpiSuiteConcurrency },
        onRunDpiProbeSuite = remember(viewModel) { viewModel::runDpiProbeSuite },
        onCancelDpiProbeSuite = remember(viewModel) { viewModel::cancelDpiProbeSuite },
        onRunXrayProviderProbe = remember(viewModel) { viewModel::runXrayProviderProbe },
        onRunRemoteDeviceAcceptance = remember(viewModel) { viewModel::runRemoteDeviceAcceptance },
        onShareRemoteDeviceAcceptance = remember(viewModel) { viewModel::shareRemoteDeviceAcceptance },
    )

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
    val onShareArchive: (String, String) -> Unit = { _, _ -> },
    val onSaveArchive: (String, String) -> Unit = { _, _ -> },
    val onShareSummary: (String, String) -> Unit = { _, _ -> },
    val onSaveLogs: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onOpenConnectionHealth: () -> Unit = {},
    val onOpenAdvancedSettings: () -> Unit = {},
    val onOpenDnsSettings: () -> Unit = {},
    val onOpenDetectionCheck: () -> Unit = {},
    val onRequestVpnPermission: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onOpenModeEditor: () -> Unit = {},
    val onOpenOwnedStackBrowser: (String) -> Unit = {},
    val onOpenPcapCaptureList: () -> Unit = {},
    val onOpenPastReplays: () -> Unit = {},
    val onInitialSectionHandled: () -> Unit = {},
)
