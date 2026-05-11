package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.DiagnosticsDpiToolsUiModel
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    onShareArchive: (String, String) -> Unit,
    onSaveArchive: (String, String) -> Unit,
    onShareSummary: (String, String) -> Unit,
    onSaveLogs: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenDnsSettings: () -> Unit = {},
    onOpenDetectionCheck: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    onOpenHistory: () -> Unit,
    onOpenModeEditor: () -> Unit = {},
    onOpenOwnedStackBrowser: (String) -> Unit = {},
    initialSection: DiagnosticsSection? = null,
    onInitialSectionHandled: () -> Unit = {},
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pcapRecording by viewModel.pcapRecording.collectAsStateWithLifecycle()
    val dnsIntegrityTool by viewModel.dnsIntegrityTool.collectAsStateWithLifecycle()
    val dnsAvailabilityTool by viewModel.dnsAvailabilityTool.collectAsStateWithLifecycle()
    val domainReachabilityTool by viewModel.domainReachabilityTool.collectAsStateWithLifecycle()
    val rknBlockDiagnosisTool by viewModel.rknBlockDiagnosisTool.collectAsStateWithLifecycle()
    val compressionProbeTool by viewModel.compressionProbeTool.collectAsStateWithLifecycle()
    val tcp16FatHeaderTool by viewModel.tcp16FatHeaderTool.collectAsStateWithLifecycle()
    val allowlistSniTool by viewModel.allowlistSniTool.collectAsStateWithLifecycle()
    val byohCompatibilityTool by viewModel.byohCompatibilityTool.collectAsStateWithLifecycle()
    val dpiSuiteTool by viewModel.dpiSuiteTool.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { DiagnosticsSection.entries.size }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.selectedSection) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != uiState.selectedSection.ordinal) {
            pagerState.animateScrollToPage(uiState.selectedSection.ordinal)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val section = DiagnosticsSection.entries[pagerState.settledPage]
        if (uiState.selectedSection != section) {
            viewModel.selectSection(section)
        }
    }

    val currentOnSaveArchive by rememberUpdatedState(onSaveArchive)
    val currentOnShareArchive by rememberUpdatedState(onShareArchive)
    val currentOnShareSummary by rememberUpdatedState(onShareSummary)
    val currentOnOpenDnsSettings by rememberUpdatedState(onOpenDnsSettings)
    val performHaptic = rememberRipDpiHapticPerformer()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiagnosticsEffect.SaveArchiveRequested -> {
                    currentOnSaveArchive(effect.absolutePath, effect.fileName)
                }

                is DiagnosticsEffect.ShareArchiveRequested -> {
                    currentOnShareArchive(effect.absolutePath, effect.fileName)
                }

                is DiagnosticsEffect.ShareSummaryRequested -> {
                    currentOnShareSummary(effect.title, effect.body)
                }

                is DiagnosticsEffect.ScanStarted -> {
                    performHaptic(RipDpiHapticFeedback.Acknowledge)
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.scanTypeLabel,
                        tone = RipDpiSnackbarTone.Info,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }

                is DiagnosticsEffect.ScanQueued -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.message,
                        tone = RipDpiSnackbarTone.Info,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }

                is DiagnosticsEffect.ScanCompleted -> {
                    performHaptic(
                        when (effect.tone) {
                            DiagnosticsTone.Positive -> RipDpiHapticFeedback.Success
                            DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiHapticFeedback.Error
                            else -> RipDpiHapticFeedback.Acknowledge
                        },
                    )
                    val result =
                        snackbarHostState.showRipDpiSnackbar(
                            message = effect.summary,
                            actionLabel = effect.actionLabel,
                            testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                            tone =
                                when (effect.tone) {
                                    DiagnosticsTone.Positive -> RipDpiSnackbarTone.Default
                                    DiagnosticsTone.Negative, DiagnosticsTone.Warning -> RipDpiSnackbarTone.Warning
                                    else -> RipDpiSnackbarTone.Default
                                },
                        )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        when (effect.action) {
                            DiagnosticsEffect.SnackbarAction.OpenDnsSettings -> currentOnOpenDnsSettings()
                            null -> Unit
                        }
                    }
                }

                is DiagnosticsEffect.ScanStartFailed -> {
                    performHaptic(RipDpiHapticFeedback.Error)
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.message,
                        tone = RipDpiSnackbarTone.Error,
                        testTag = RipDpiTestTags.DiagnosticsStatusSnackbar,
                    )
                }
            }
        }
    }

    LaunchedEffect(initialSection) {
        initialSection?.let {
            viewModel.selectSection(it)
            onInitialSectionHandled()
        }
    }

    DiagnosticsScreen(
        uiState = uiState,
        pagerState = pagerState,
        snackbarHostState = snackbarHostState,
        actions =
            DiagnosticsScreenActions(
                onSelectSection = remember(viewModel) { viewModel::selectSection },
                onSelectProfile = remember(viewModel) { viewModel::selectProfile },
                onRunScan = remember(viewModel) { viewModel::runScan },
                onRunRawScan = remember(viewModel) { viewModel::startRawScan },
                onRunInPathScan = remember(viewModel) { viewModel::startInPathScan },
                onWaitForHiddenProbeAndRun = remember(viewModel) { viewModel::waitForHiddenProbeAndRun },
                onCancelHiddenProbeAndRun = remember(viewModel) { viewModel::cancelHiddenProbeAndRun },
                onDismissHiddenProbeConflictDialog =
                    remember(viewModel) { viewModel::dismissHiddenProbeConflictDialog },
                onConfirmSensitiveProfileRun = remember(viewModel) { viewModel::confirmSensitiveProfileRun },
                onDismissSensitiveProfileConsentDialog =
                    remember(viewModel) { viewModel::dismissSensitiveProfileConsentDialog },
                onCancelScan = remember(viewModel) { viewModel::cancelScan },
                onKeepResolverRecommendation =
                    remember(viewModel) { viewModel::keepResolverRecommendationForSession },
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
                onSaveLogs = onSaveLogs,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onOpenDnsSettings = onOpenDnsSettings,
                onOpenDetectionCheck = onOpenDetectionCheck,
                onRequestVpnPermission = onRequestVpnPermission,
                onOpenHistory = onOpenHistory,
                onOpenModeEditor = onOpenModeEditor,
                onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
                onTogglePcapRecording = remember(viewModel) { viewModel::togglePcapRecording },
                onRunDnsIntegrityCheck = remember(viewModel) { viewModel::runDnsIntegrityCheck },
                onRunDnsAvailabilitySurvey = remember(viewModel) { viewModel::runDnsAvailabilitySurvey },
                onRunDomainReachabilityScan = remember(viewModel) { viewModel::runDomainReachabilityScan },
                onRunCompressionProbe = remember(viewModel) { viewModel::runCompressionProbe },
                onRunTcp16FatHeaderProbe = remember(viewModel) { viewModel::runTcp16FatHeaderProbe },
                onRunAllowlistSniFinder = remember(viewModel) { viewModel::runAllowlistSniFinder },
                onRunByohCompatibilityCheck = remember(viewModel) { viewModel::runByohCompatibilityCheck },
                onRunRknBlockDiagnosis = remember(viewModel) { viewModel::runRknBlockDiagnosis },
                onRknSelfInfoEnabledChange = remember(viewModel) { viewModel::setRknSelfInfoEnabled },
                onCompressionProbeZstdEnabledChange =
                    remember(viewModel) { viewModel::setCompressionProbeZstdEnabled },
                onByohDstIpChange = remember(viewModel) { viewModel::setByohDstIp },
                onByohUrlPathChange = remember(viewModel) { viewModel::setByohUrlPath },
                onByohSyntheticFixtureEnabledChange =
                    remember(viewModel) { viewModel::setByohSyntheticFixtureEnabled },
                onDpiSuiteProbeEnabledChange = remember(viewModel) { viewModel::setDpiSuiteProbeEnabled },
                onDpiSuiteCustomDomainsChange = remember(viewModel) { viewModel::setDpiSuiteCustomDomains },
                onDpiSuiteConcurrencyDelta = remember(viewModel) { viewModel::adjustDpiSuiteConcurrency },
                onRunDpiProbeSuite = remember(viewModel) { viewModel::runDpiProbeSuite },
                onCancelDpiProbeSuite = remember(viewModel) { viewModel::cancelDpiProbeSuite },
            ),
        dpiTools =
            DiagnosticsDpiToolsUiModel(
                dnsIntegrity = dnsIntegrityTool,
                dnsAvailability = dnsAvailabilityTool,
                domainReachability = domainReachabilityTool,
                rknBlockDiagnosis = rknBlockDiagnosisTool,
                compressionProbe = compressionProbeTool,
                tcp16FatHeader = tcp16FatHeaderTool,
                allowlistSni = allowlistSniTool,
                byohCompatibility = byohCompatibilityTool,
                dpiSuite = dpiSuiteTool,
            ),
        pcapRecording = pcapRecording,
        modifier = modifier,
    )
}
