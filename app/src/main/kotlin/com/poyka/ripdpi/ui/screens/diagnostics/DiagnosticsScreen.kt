package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.activities.DiagnosticsAutomaticProbeCalloutUiModel
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiToolsUiModel
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsOverviewUiModel
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsRememberedNetworkUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.xray.XrayProviderToolUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

data class DiagnosticsScreenActions(
    val onSelectSection: (DiagnosticsSection) -> Unit = {},
    val onSelectProfile: (String) -> Unit = {},
    val onRunScan: () -> Unit = {},
    val onRunRawScan: () -> Unit = {},
    val onRunInPathScan: () -> Unit = {},
    val onWaitForHiddenProbeAndRun: () -> Unit = {},
    val onCancelHiddenProbeAndRun: () -> Unit = {},
    val onDismissHiddenProbeConflictDialog: () -> Unit = {},
    val onConfirmSensitiveProfileRun: () -> Unit = {},
    val onDismissSensitiveProfileConsentDialog: () -> Unit = {},
    val onCancelScan: () -> Unit = {},
    val onKeepResolverRecommendation: (String?) -> Unit = {},
    val onSaveResolverRecommendation: (String?) -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onDismissSessionDetail: () -> Unit = {},
    val onSelectStrategyProbeCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit = {},
    val onDismissStrategyProbeCandidate: () -> Unit = {},
    val onSelectApproachMode: (DiagnosticsApproachMode) -> Unit = {},
    val onSelectApproach: (String) -> Unit = {},
    val onDismissApproachDetail: () -> Unit = {},
    val onSelectEvent: (String) -> Unit = {},
    val onDismissEventDetail: () -> Unit = {},
    val onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit = {},
    val onDismissProbeDetail: () -> Unit = {},
    val onToggleSensitiveSessionDetails: () -> Unit = {},
    val onSessionPathFilter: (String?) -> Unit = {},
    val onSessionStatusFilter: (String?) -> Unit = {},
    val onSessionSearch: (String) -> Unit = {},
    val onToggleEventFilter: (String?, String?) -> Unit = { _, _ -> },
    val onEventSearch: (String) -> Unit = {},
    val onEventAutoScroll: (Boolean) -> Unit = {},
    val onShareSummary: (String?) -> Unit = {},
    val onShareArchive: (String?) -> Unit = {},
    val onSaveArchive: (String?) -> Unit = {},
    val onSaveLogs: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onOpenConnectionHealth: () -> Unit = {},
    val onOpenAdvancedSettings: () -> Unit = {},
    val onOpenDnsSettings: () -> Unit = {},
    val onOpenDetectionCheck: () -> Unit = {},
    val onRequestVpnPermission: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onOpenModeEditor: () -> Unit = {},
    val onApplyRecommendedPath: () -> Unit = {},
    val onOpenOwnedStackBrowser: (String) -> Unit = {},
    val onOpenPcapCaptureList: () -> Unit = {},
    val onOpenPastReplays: () -> Unit = {},
    val onTogglePcapRecording: () -> Unit = {},
    val onRunDnsIntegrityCheck: () -> Unit = {},
    val onRunRemoteDeviceAcceptance: () -> Unit = {},
    val onShareRemoteDeviceAcceptance: () -> Unit = {},
    val onRunDnsAvailabilitySurvey: () -> Unit = {},
    val onRunDomainReachabilityScan: () -> Unit = {},
    val onRunCompressionProbe: () -> Unit = {},
    val onRunCidrWhitelistDetection: () -> Unit = {},
    val onCacheIpv4WhitelistSubnets: () -> Unit = {},
    val onCheckIpv4WhitelistSubnets: () -> Unit = {},
    val onSaveIpv4WhitelistCsv: () -> Unit = {},
    val onRunTcp16FatHeaderProbe: () -> Unit = {},
    val onRunAllowlistSniFinder: () -> Unit = {},
    val onRunPluggableTransportProbe: () -> Unit = {},
    val onRunByohCompatibilityCheck: () -> Unit = {},
    val onRunRknBlockDiagnosis: () -> Unit = {},
    val onRknSelfInfoEnabledChange: (Boolean) -> Unit = {},
    val onCompressionProbeZstdEnabledChange: (Boolean) -> Unit = {},
    val onByohDstIpChange: (String) -> Unit = {},
    val onByohUrlPathChange: (String) -> Unit = {},
    val onByohSyntheticFixtureEnabledChange: (Boolean) -> Unit = {},
    val onDpiSuiteProbeEnabledChange: (DpiProbeKind, Boolean) -> Unit = { _, _ -> },
    val onDpiSuiteCustomDomainsChange: (String) -> Unit = {},
    val onDpiSuiteConcurrencyDelta: (Int) -> Unit = {},
    val onRunDpiProbeSuite: () -> Unit = {},
    val onCancelDpiProbeSuite: () -> Unit = {},
    val onRunXrayProviderProbe: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    uiState: DiagnosticsUiState,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    actions: DiagnosticsScreenActions = DiagnosticsScreenActions(),
    dpiTools: DiagnosticsDpiToolsUiModel = DiagnosticsDpiToolsUiModel(),
    cidrWhitelistTool: DiagnosticsCidrWhitelistToolUiModel = DiagnosticsCidrWhitelistToolUiModel(),
    ipv4WhitelistTool: DiagnosticsIpv4WhitelistToolUiModel = DiagnosticsIpv4WhitelistToolUiModel(),
    pluggableTransportTool: DiagnosticsPluggableTransportToolUiModel = DiagnosticsPluggableTransportToolUiModel(),
    xrayProvider: XrayProviderToolUiModel = XrayProviderToolUiModel(),
    remoteDeviceAcceptance: RemoteDeviceAcceptanceReport = RemoteDeviceAcceptanceReport(),
    rootModeEnabled: Boolean = false,
    pcapRecording: Boolean = false,
    topBarExtraActions: @Composable () -> Unit = {},
) {
    TrackRecomposition("DiagnosticsScreen")
    var showDebugInfo by rememberSaveable { mutableStateOf(false) }

    DiagnosticsScreenFrame(
        uiState = uiState,
        pagerState = pagerState,
        snackbarHostState = snackbarHostState,
        actions = actions,
        showDebugInfo = showDebugInfo,
        onToggleDebugInfo = { if (BuildConfig.DEBUG) showDebugInfo = !showDebugInfo },
        tools =
            DiagnosticsToolsUiModel(
                dpiTools = dpiTools,
                cidrWhitelistTool = cidrWhitelistTool,
                ipv4WhitelistTool = ipv4WhitelistTool,
                pluggableTransportTool = pluggableTransportTool,
                xrayProvider = xrayProvider,
                onRunXrayProviderProbe = actions.onRunXrayProviderProbe,
                remoteDeviceAcceptance = remoteDeviceAcceptance,
                onRunRemoteDeviceAcceptance = actions.onRunRemoteDeviceAcceptance,
                onShareRemoteDeviceAcceptance = actions.onShareRemoteDeviceAcceptance,
            ),
        rootModeEnabled = rootModeEnabled,
        pcapRecording = pcapRecording,
        topBarExtraActions = topBarExtraActions,
        modifier = modifier,
    )
    DiagnosticsScreenDialogs(uiState = uiState, actions = actions)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticsScreenFrame(
    uiState: DiagnosticsUiState,
    pagerState: PagerState,
    snackbarHostState: SnackbarHostState,
    actions: DiagnosticsScreenActions,
    showDebugInfo: Boolean,
    onToggleDebugInfo: () -> Unit,
    tools: DiagnosticsToolsUiModel,
    rootModeEnabled: Boolean,
    pcapRecording: Boolean,
    topBarExtraActions: @Composable () -> Unit,
    modifier: Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Diagnostics()))
                .fillMaxSize()
                .background(colors.background),
        snackbarHost = { RipDpiSnackbarHost(snackbarHostState) },
        topBar = {
            DiagnosticsTopBar(
                actions = actions,
                onToggleDebugInfo = onToggleDebugInfo,
                topBarExtraActions = topBarExtraActions,
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = layout.contentMaxWidth),
            ) {
                DiagnosticsSectionSwitcher(
                    selectedSection = uiState.selectedSection,
                    onSelectSection = actions.onSelectSection,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding),
                )
                if (showDebugInfo) {
                    uiState.performance?.let { performance ->
                        DiagnosticsPerformanceCard(
                            performance = performance,
                            selectedSection = uiState.selectedSection,
                            modifier =
                                Modifier.padding(
                                    start = layout.horizontalPadding,
                                    end = layout.horizontalPadding,
                                    bottom = spacing.sm,
                                ),
                        )
                    }
                }
                DiagnosticsScreenPager(
                    uiState = uiState,
                    pagerState = pagerState,
                    actions = actions,
                    tools = tools,
                    rootModeEnabled = rootModeEnabled,
                    pcapRecording = pcapRecording,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsTopBar(
    actions: DiagnosticsScreenActions,
    onToggleDebugInfo: () -> Unit,
    topBarExtraActions: @Composable () -> Unit,
) {
    RipDpiTopAppBar(
        title = stringResource(R.string.diagnostics_title),
        modifier =
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = onToggleDebugInfo,
            ),
        actions = {
            RipDpiIconButton(
                icon = RipDpiIcons.NetworkCheck,
                contentDescription = stringResource(R.string.connection_health_open_action),
                onClick = actions.onOpenConnectionHealth,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsConnectionHealthAction),
            )
            topBarExtraActions()
            RipDpiIconButton(
                icon = RipDpiIcons.Logs,
                contentDescription = stringResource(R.string.history_open_action),
                onClick = actions.onOpenHistory,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsTopHistoryAction),
            )
        },
    )
}

@Composable
private fun DiagnosticsScreenPager(
    uiState: DiagnosticsUiState,
    pagerState: PagerState,
    actions: DiagnosticsScreenActions,
    tools: DiagnosticsToolsUiModel,
    rootModeEnabled: Boolean,
    pcapRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RectangleShape),
    ) { page ->
        when (DiagnosticsSection.entries[page]) {
            DiagnosticsSection.Dashboard -> {
                OverviewSection(
                    overview = uiState.overview,
                    scan = uiState.scan,
                    live = uiState.live,
                    isActiveScan = uiState.scan.activeProgress != null,
                    onSelectSection = actions.onSelectSection,
                    onRunScan = actions.onRunScan,
                    onApplyRecommendedPath = actions.onApplyRecommendedPath,
                    onSelectSession = actions.onSelectSession,
                    onOpenHistory = actions.onOpenHistory,
                )
            }

            DiagnosticsSection.Scan -> {
                ScanSection(
                    scan = uiState.scan,
                    onSelectProfile = actions.onSelectProfile,
                    onRunRawScan = actions.onRunRawScan,
                    onRunInPathScan = actions.onRunInPathScan,
                    onCancelScan = actions.onCancelScan,
                    onOpenAdvancedSettings = actions.onOpenAdvancedSettings,
                    onOpenDnsSettings = actions.onOpenDnsSettings,
                    onRequestVpnPermission = actions.onRequestVpnPermission,
                    onSelectStrategyProbeCandidate = actions.onSelectStrategyProbeCandidate,
                    onSelectProbe = actions.onSelectProbe,
                    onOpenHistory = actions.onOpenHistory,
                    onOpenModeEditor = actions.onOpenModeEditor,
                    onOpenOwnedStackBrowser = actions.onOpenOwnedStackBrowser,
                )
            }

            DiagnosticsSection.Tools -> {
                DiagnosticsToolsPagerPage(
                    uiState = uiState,
                    actions = actions,
                    tools = tools,
                    rootModeEnabled = rootModeEnabled,
                    pcapRecording = pcapRecording,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsToolsPagerPage(
    uiState: DiagnosticsUiState,
    actions: DiagnosticsScreenActions,
    tools: DiagnosticsToolsUiModel,
    rootModeEnabled: Boolean,
    pcapRecording: Boolean,
) {
    ToolsSection(
        approaches = uiState.approaches,
        share = uiState.share,
        onSelectApproachMode = actions.onSelectApproachMode,
        onSelectApproach = actions.onSelectApproach,
        shareActions = actions.toDiagnosticsShareActions(),
        tools = tools,
        dpiToolActions = actions.toDiagnosticsDpiToolActions(),
        navActions =
            DiagnosticsToolsNavActions(
                onOpenDetectionCheck = actions.onOpenDetectionCheck,
                onOpenPcapCaptureList = actions.onOpenPcapCaptureList,
                onOpenPastReplays = actions.onOpenPastReplays,
            ),
        rootModeEnabled = rootModeEnabled,
        pcapRecording = pcapRecording,
        onTogglePcapRecording = actions.onTogglePcapRecording,
    )
}

private fun DiagnosticsScreenActions.toDiagnosticsShareActions(): DiagnosticsShareActions =
    DiagnosticsShareActions(
        onShareSummary = onShareSummary,
        onShareArchive = onShareArchive,
        onSaveArchive = onSaveArchive,
        onSaveLogs = onSaveLogs,
        onOpenLogs = onOpenLogs,
    )

private fun DiagnosticsScreenActions.toDiagnosticsDpiToolActions(): DiagnosticsDpiToolActions =
    DiagnosticsDpiToolActions(
        onRunDnsIntegrityCheck = onRunDnsIntegrityCheck,
        onRunDnsAvailabilitySurvey = onRunDnsAvailabilitySurvey,
        onRunDomainReachabilityScan = onRunDomainReachabilityScan,
        onRunCompressionProbe = onRunCompressionProbe,
        onRunCidrWhitelistDetection = onRunCidrWhitelistDetection,
        onCacheIpv4WhitelistSubnets = onCacheIpv4WhitelistSubnets,
        onCheckIpv4WhitelistSubnets = onCheckIpv4WhitelistSubnets,
        onSaveIpv4WhitelistCsv = onSaveIpv4WhitelistCsv,
        onRunTcp16FatHeaderProbe = onRunTcp16FatHeaderProbe,
        onRunAllowlistSniFinder = onRunAllowlistSniFinder,
        onRunPluggableTransportProbe = onRunPluggableTransportProbe,
        onRunByohCompatibilityCheck = onRunByohCompatibilityCheck,
        onRunRknBlockDiagnosis = onRunRknBlockDiagnosis,
        onRknSelfInfoEnabledChange = onRknSelfInfoEnabledChange,
        onCompressionProbeZstdEnabledChange = onCompressionProbeZstdEnabledChange,
        onByohDstIpChange = onByohDstIpChange,
        onByohUrlPathChange = onByohUrlPathChange,
        onByohSyntheticFixtureEnabledChange = onByohSyntheticFixtureEnabledChange,
        onDpiSuiteProbeEnabledChange = onDpiSuiteProbeEnabledChange,
        onDpiSuiteCustomDomainsChange = onDpiSuiteCustomDomainsChange,
        onDpiSuiteConcurrencyDelta = onDpiSuiteConcurrencyDelta,
        onRunDpiProbeSuite = onRunDpiProbeSuite,
        onCancelDpiProbeSuite = onCancelDpiProbeSuite,
    )

@Composable
private fun DiagnosticsScreenDialogs(
    uiState: DiagnosticsUiState,
    actions: DiagnosticsScreenActions,
) {
    DiagnosticsBottomSheetHost(
        selectedSessionDetail = uiState.selectedSessionDetail,
        selectedApproachDetail = uiState.selectedApproachDetail,
        selectedEvent = uiState.selectedEvent,
        selectedProbe = uiState.selectedProbe,
        selectedStrategyProbeCandidate = uiState.selectedStrategyProbeCandidate,
        onDismissSessionDetail = actions.onDismissSessionDetail,
        onToggleSensitiveSessionDetails = actions.onToggleSensitiveSessionDetails,
        onSelectStrategyProbeCandidate = actions.onSelectStrategyProbeCandidate,
        onDismissStrategyProbeCandidate = actions.onDismissStrategyProbeCandidate,
        onSelectEvent = actions.onSelectEvent,
        onDismissEventDetail = actions.onDismissEventDetail,
        onSelectProbe = actions.onSelectProbe,
        onDismissProbeDetail = actions.onDismissProbeDetail,
        onDismissApproachDetail = actions.onDismissApproachDetail,
    )
    uiState.scan.hiddenProbeConflictDialog?.let { dialogState ->
        HiddenProbeConflictDialog(dialogState.profileName, actions)
    }
    uiState.scan.sensitiveProfileConsentDialog?.let { dialogState ->
        SensitiveProfileConsentDialog(dialogState.profileName, actions)
    }
}

@Composable
private fun HiddenProbeConflictDialog(
    profileName: String,
    actions: DiagnosticsScreenActions,
) {
    RipDpiDialog(
        onDismissRequest = actions.onDismissHiddenProbeConflictDialog,
        title = stringResource(R.string.diagnostics_hidden_probe_conflict_title),
        dialogTestTag = RipDpiTestTags.DiagnosticsHiddenProbeConflictDialog,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.diagnostics_hidden_probe_wait_action),
                onClick = actions.onWaitForHiddenProbeAndRun,
                testTag = RipDpiTestTags.DiagnosticsHiddenProbeConflictWait,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.diagnostics_hidden_probe_cancel_and_run_action),
                onClick = actions.onCancelHiddenProbeAndRun,
                testTag = RipDpiTestTags.DiagnosticsHiddenProbeConflictCancelAndRun,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.diagnostics_hidden_probe_conflict_body_format, profileName),
                tone = RipDpiDialogTone.Info,
            ),
    ) {
        RipDpiButton(
            text = stringResource(R.string.diagnostics_hidden_probe_dismiss_action),
            onClick = actions.onDismissHiddenProbeConflictDialog,
            variant = RipDpiButtonVariant.Ghost,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsHiddenProbeConflictDismiss),
        )
    }
}

@Composable
private fun SensitiveProfileConsentDialog(
    profileName: String,
    actions: DiagnosticsScreenActions,
) {
    RipDpiDialog(
        onDismissRequest = actions.onDismissSensitiveProfileConsentDialog,
        title = stringResource(R.string.diagnostics_sensitive_profile_consent_title),
        dialogTestTag = RipDpiTestTags.DiagnosticsSensitiveProfileConsentDialog,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.diagnostics_sensitive_profile_consent_dismiss),
                onClick = actions.onDismissSensitiveProfileConsentDialog,
                testTag = RipDpiTestTags.DiagnosticsSensitiveProfileConsentDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.diagnostics_sensitive_profile_consent_confirm),
                onClick = actions.onConfirmSensitiveProfileRun,
                testTag = RipDpiTestTags.DiagnosticsSensitiveProfileConsentConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.diagnostics_sensitive_profile_consent_body_format, profileName),
                tone = RipDpiDialogTone.Info,
            ),
    ) {
        RipDpiButton(
            text = stringResource(R.string.diagnostics_sensitive_profile_consent_dismiss),
            onClick = actions.onDismissSensitiveProfileConsentDialog,
            variant = RipDpiButtonVariant.Ghost,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsSensitiveProfileConsentDismiss),
        )
    }
}

@Preview(showBackground = true, name = "Dashboard — idle")
@Composable
private fun previewDiagnosticsScreenIdle() {
    RipDpiTheme {
        DiagnosticsScreen(
            uiState = DiagnosticsUiState(),
            pagerState = rememberPagerState(pageCount = { DiagnosticsSection.entries.size }),
        )
    }
}

@Preview(showBackground = true, name = "Scan tab — idle")
@Composable
private fun previewDiagnosticsScreenScanTab() {
    RipDpiTheme {
        DiagnosticsScreen(
            uiState = DiagnosticsUiState(selectedSection = DiagnosticsSection.Scan),
            pagerState =
                rememberPagerState(
                    initialPage = 1,
                    pageCount = { DiagnosticsSection.entries.size },
                ),
        )
    }
}
