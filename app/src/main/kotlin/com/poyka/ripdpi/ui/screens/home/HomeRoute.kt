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
    onOpenConnectionHealth: () -> Unit,
    onOpenSubscriptionStatus: () -> Unit = {},
    onOpenAdvancedSettings: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    onOpenLocalBypassConfig: () -> Unit,
    onOpenVpnConfig: () -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    viewModel: MainViewModel,
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val homeDiagnostics = viewModel.homeDiagnosticsUiState.collectAsStateWithLifecycle().value
    val homeDiagnosticCard = viewModel.homeDiagnosticCard.collectAsStateWithLifecycle().value
    val subscriptionExpiry = viewModel.subscriptionExpiryUiState.collectAsStateWithLifecycle().value
    val modesDisclosure = viewModel.homeModesDisclosure
    val modesExpanded = modesDisclosure.expanded.collectAsStateWithLifecycle().value
    HomeScreen(
        uiState = uiState,
        initialModesExpanded = modesExpanded,
        onModesExpandedChange = remember(viewModel) { modesDisclosure::onExpandedChange },
        homeDiagnostics = homeDiagnostics,
        diagnosticCard = homeDiagnosticCard,
        subscriptionExpiry = subscriptionExpiry,
        modifier = modifier,
        onToggleConnection = remember(viewModel) { { viewModel.onPrimaryConnectionAction() } },
        onBypassToggle = remember(viewModel) { { enabled -> viewModel.onToggleLocalBypass(enabled) } },
        onVpnToggle = remember(viewModel) { { enabled -> viewModel.onToggleVpn(enabled) } },
        onDiagnosticRun = remember(viewModel) { viewModel::onRunHomeFullAnalysis },
        onBypassCardClick = onOpenLocalBypassConfig,
        onVpnCardClick = onOpenVpnConfig,
        onDiagnosticCardClick = onOpenDiagnostics,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenHistory = onOpenHistory,
        onOpenConnectionHealth = onOpenConnectionHealth,
        onOpenSubscriptionStatus = onOpenSubscriptionStatus,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenModeEditor = onOpenModeEditor,
        onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
        onRepairPermission = remember(viewModel) { viewModel::onRepairPermissionRequested },
        onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
        onTogglePcapRecording = remember(viewModel) { viewModel::onToggleHomePcapRecording },
        onDismissBatteryBanner = remember(viewModel) { viewModel::onDismissBatteryBanner },
        onDismissBackgroundGuidance = remember(viewModel) { viewModel::onDismissBackgroundGuidance },
        onShareAnalysis = remember(viewModel) { viewModel.onShareHomeAnalysis },
        onDismissAnalysisSheet = remember(viewModel) { viewModel.dismissHomeAnalysisSheet },
        onDismissVerificationSheet = remember(viewModel) { viewModel.dismissHomeVerificationSheet },
    )
}
