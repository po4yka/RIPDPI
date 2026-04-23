package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.DiagnosticsEffect
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsRoute(
    onShareArchive: (String, String) -> Unit,
    onSaveArchive: (String, String) -> Unit,
    onShareSummary: (String, String) -> Unit,
    onSaveLogs: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenDnsSettings: () -> Unit = {},
    onOpenDetectionCheck: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    onOpenHistory: () -> Unit,
    initialSection: DiagnosticsSection? = null,
    onInitialSectionHandled: () -> Unit = {},
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pcapRecording by viewModel.pcapRecording.collectAsStateWithLifecycle()
    val pagerState =
        androidx.compose.foundation.pager
            .rememberPagerState { DiagnosticsSection.entries.size }
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

    LaunchedEffect(Unit) {
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
        onSelectSection = viewModel::selectSection,
        onSelectProfile = viewModel::selectProfile,
        onRunRawScan = viewModel::startRawScan,
        onRunInPathScan = viewModel::startInPathScan,
        onWaitForHiddenProbeAndRun = viewModel::waitForHiddenProbeAndRun,
        onCancelHiddenProbeAndRun = viewModel::cancelHiddenProbeAndRun,
        onDismissHiddenProbeConflictDialog = viewModel::dismissHiddenProbeConflictDialog,
        onConfirmSensitiveProfileRun = viewModel::confirmSensitiveProfileRun,
        onDismissSensitiveProfileConsentDialog = viewModel::dismissSensitiveProfileConsentDialog,
        onCancelScan = viewModel::cancelScan,
        onKeepResolverRecommendation = viewModel::keepResolverRecommendationForSession,
        onSaveResolverRecommendation = viewModel::saveResolverRecommendation,
        onSelectSession = viewModel::selectSession,
        onDismissSessionDetail = viewModel::dismissSessionDetail,
        onSelectStrategyProbeCandidate = viewModel::selectStrategyProbeCandidate,
        onDismissStrategyProbeCandidate = viewModel::dismissStrategyProbeCandidate,
        onSelectApproachMode = viewModel::selectApproachMode,
        onSelectApproach = viewModel::selectApproach,
        onDismissApproachDetail = viewModel::dismissApproachDetail,
        onSelectEvent = viewModel::selectEvent,
        onDismissEventDetail = viewModel::dismissEventDetail,
        onSelectProbe = viewModel::selectProbe,
        onDismissProbeDetail = viewModel::dismissProbeDetail,
        onToggleSensitiveSessionDetails = viewModel::toggleSensitiveSessionDetails,
        onSessionPathFilter = viewModel::setSessionPathModeFilter,
        onSessionStatusFilter = viewModel::setSessionStatusFilter,
        onSessionSearch = viewModel::setSessionSearch,
        onToggleEventFilter = viewModel::toggleEventFilter,
        onEventSearch = viewModel::setEventSearch,
        onEventAutoScroll = viewModel::setEventAutoScroll,
        onShareSummary = viewModel::shareSummary,
        onShareArchive = viewModel::shareArchive,
        onSaveArchive = viewModel::saveArchive,
        onSaveLogs = onSaveLogs,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenDnsSettings = onOpenDnsSettings,
        onOpenDetectionCheck = onOpenDetectionCheck,
        onRequestVpnPermission = onRequestVpnPermission,
        onOpenHistory = onOpenHistory,
        pcapRecording = pcapRecording,
        onTogglePcapRecording = viewModel::togglePcapRecording,
        modifier = modifier,
    )
}
