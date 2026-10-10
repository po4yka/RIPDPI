package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState

internal val HomeDiagnosticsUiState.compositeRunBusy: Boolean
    get() =
        analysisRunStatus == HomeDiagnosticsRunUiStatus.STARTING ||
            analysisRunStatus == HomeDiagnosticsRunUiStatus.RUNNING

internal val HomeDiagnosticsUiState.diagnosticsAdmissionBusy: Boolean
    get() = analysisAction.busy || verifiedVpnAction.busy || compositeRunBusy

internal fun DiagnosticsScreenActions.withNavigationCallbacks(
    viewModel: DiagnosticsViewModel,
    callbacks: DiagnosticsRouteCallbacks,
    homeDiagnostics: HomeDiagnosticsUiState,
): DiagnosticsScreenActions =
    copy(
        onSaveLogs = callbacks.onSaveLogs,
        onOpenLogs = callbacks.onOpenLogs,
        onOpenConnectionHealth = callbacks.onOpenConnectionHealth,
        onOpenAdvancedSettings = callbacks.onOpenAdvancedSettings,
        onOpenDnsSettings = callbacks.onOpenDnsSettings,
        onOpenDetectionCheck = callbacks.onOpenDetectionCheck,
        onRequestVpnPermission = callbacks.onRequestVpnPermission,
        onOpenHistory = callbacks.onOpenHistory,
        onOpenModeEditor = callbacks.onOpenModeEditor,
        onReviewRecommendedPath = {
            val candidate =
                viewModel.screenUiState.value.scan
                    .recommendedCandidate()
            if (candidate != null) {
                viewModel.selectStrategyProbeCandidate(candidate)
            } else {
                viewModel.selectSection(DiagnosticsSection.Scan)
            }
        },
        onCheckNetwork = callbacks.onCheckNetwork,
        onStartVerifiedVpn = callbacks.onStartVerifiedVpn,
        onDismissVerification = callbacks.onDismissVerification,
        onOpenOwnedStackBrowser = callbacks.onOpenOwnedStackBrowser,
        onOpenPcapCaptureList = callbacks.onOpenPcapCaptureList,
        onOpenPastReplays = callbacks.onOpenPastReplays,
        onCancelScan = {
            if (homeDiagnostics.compositeRunBusy) callbacks.onCancelHomeAnalysis() else viewModel.cancelScan()
        },
    )
