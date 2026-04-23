package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.MainViewModel

@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    onOpenDiagnostics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    viewModel: MainViewModel,
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    HomeScreen(
        uiState = uiState,
        modifier = modifier,
        onToggleConnection = remember(viewModel) { { viewModel.onPrimaryConnectionAction() } },
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenHistory = onOpenHistory,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenModeEditor = onOpenModeEditor,
        onRepairPermission = viewModel::onRepairPermissionRequested,
        onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
        onDismissBatteryBanner = viewModel::onDismissBatteryBanner,
        onDismissBackgroundGuidance = viewModel::onDismissBackgroundGuidance,
        onRunFullAnalysis = viewModel::onRunHomeFullAnalysis,
        onRunQuickAnalysis = viewModel::onRunHomeQuickAnalysis,
        onStartVerifiedVpn = viewModel::onStartVerifiedVpn,
        onShareAnalysis = viewModel::onShareHomeAnalysis,
        onDismissAnalysisSheet = viewModel::dismissHomeAnalysisSheet,
        onDismissVerificationSheet = viewModel::dismissHomeVerificationSheet,
        onTogglePcapRecording = viewModel::onToggleHomePcapRecording,
    )
}
