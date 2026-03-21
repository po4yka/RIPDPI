package com.poyka.ripdpi.activities

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.screens.permissions.VpnPermissionDialog
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.theme.RipDpiTheme

@Composable
internal fun MainActivityContent(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
) {
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shellState by controller.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }

    LaunchedEffect(viewModel, controller) {
        viewModel.effects.collect { effect ->
            controller.onEffect(effect)
        }
    }

    LaunchedEffect(controller) {
        controller.uiEvents.collect { event ->
            when (event) {
                is MainActivityUiEvent.ShowErrorSnackbar -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = event.message,
                        tone = RipDpiSnackbarTone.Error,
                        duration = SnackbarDuration.Short,
                        testTag = RipDpiTestTags.MainErrorSnackbar,
                    )
                }
            }
        }
    }

    LaunchedEffect(shellState.startConfiguredModeRequested) {
        if (shellState.startConfiguredModeRequested) {
            viewModel.onPrimaryConnectionAction()
            controller.consumeStartConfiguredModeRequest()
        }
    }

    LaunchedEffect(uiState.connectionState) {
        controller.onConnectionStateChanged(uiState.connectionState)
    }

    RipDpiTheme(themePreference = startupState.theme) {
        if (startupState.isReady) {
            val initialStartDestination = remember { startupState.startDestination }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .ripDpiAutomationTreeRoot(),
            ) {
                RipDpiNavHost(
                    startDestination = initialStartDestination,
                    onSaveLogs = controller::requestSaveLogs,
                    onShareDebugBundle = controller::requestShareDebugBundle,
                    onSaveDiagnosticsArchive = controller::requestSaveDiagnosticsArchive,
                    onShareDiagnosticsArchive = controller::requestShareDiagnosticsArchive,
                    onShareDiagnosticsSummary = controller::requestShareDiagnosticsSummary,
                    mainViewModel = viewModel,
                    launchHomeRequested = shellState.launchHomeRequested,
                    onLaunchHomeHandled = controller::consumeLaunchHomeRequest,
                    launchRouteRequested = shellState.launchRouteRequested,
                    onLaunchRouteHandled = controller::consumeLaunchRouteRequest,
                    onStartConfiguredMode = viewModel::onPrimaryConnectionAction,
                    onRepairPermission = { permission -> viewModel.onRepairPermissionRequested(permission) },
                    snackbarHostState = snackbarHostState,
                )
                if (shellState.vpnPermissionDialogVisible) {
                    VpnPermissionDialog(
                        uiState = uiState,
                        onDismiss = controller::dismissVpnPermissionDialog,
                        onContinue = viewModel::onVpnPermissionContinueRequested,
                    )
                }
            }
        }
    }
}
