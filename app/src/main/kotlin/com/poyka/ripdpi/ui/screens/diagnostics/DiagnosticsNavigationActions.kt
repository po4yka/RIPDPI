package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSection
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsViewModel
import com.poyka.ripdpi.ui.screens.status.StatusDiagnosticsRunUiStatus
import com.poyka.ripdpi.ui.screens.status.StatusDiagnosticsUiState

internal val StatusDiagnosticsUiState.compositeRunBusy: Boolean
    get() =
        analysisRunStatus == StatusDiagnosticsRunUiStatus.STARTING ||
            analysisRunStatus == StatusDiagnosticsRunUiStatus.RUNNING

internal val StatusDiagnosticsUiState.diagnosticsAdmissionBusy: Boolean
    get() = analysisAction.busy || verifiedVpnAction.busy || compositeRunBusy

internal fun DiagnosticsScreenActions.withNavigationCallbacks(
    viewModel: DiagnosticsViewModel,
    callbacks: DiagnosticsRouteCallbacks,
    homeDiagnostics: StatusDiagnosticsUiState,
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
                viewModel.selectionActions.selectStrategyProbeCandidate(candidate)
            } else {
                viewModel.selectionActions.selectSection(DiagnosticsSection.Scan)
            }
        },
        onCheckNetwork = callbacks.onCheckNetwork,
        onStartVerifiedVpn = callbacks.onStartVerifiedVpn,
        onDismissVerification = callbacks.onDismissVerification,
        onOpenOwnedStackBrowser = callbacks.onOpenOwnedStackBrowser,
        onOpenPcapCaptureList = callbacks.onOpenPcapCaptureList,
        onOpenPastReplays = callbacks.onOpenPastReplays,
        onReplayProbe = callbacks.onReplayProbe,
        onCancelScan = {
            if (homeDiagnostics.compositeRunBusy) {
                callbacks.onCancelHomeAnalysis()
            } else {
                viewModel.scanActions
                    .cancelScan()
            }
        },
    )
