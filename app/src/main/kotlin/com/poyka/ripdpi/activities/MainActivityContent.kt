package com.poyka.ripdpi.activities

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.debug.RecompositionReportEffect
import com.poyka.ripdpi.ui.screens.crash.CrashReportDialog
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

    RecompositionReportEffect()

    MainActivityEffects(
        viewModel = viewModel,
        controller = controller,
        shellState = shellState,
        canHandleStartConfiguredModeRequest = startupState.isReady && uiState.settingsLoaded,
        connectionState = uiState.connectionState,
        snackbarHostState = snackbarHostState,
    )

    RipDpiTheme(themePreference = startupState.theme) {
        if (startupState.isReady) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .ripDpiAutomationTreeRoot(),
            ) {
                key(startupState.startDestination) {
                    // Flavor seam: the "full" source set renders the full nav host;
                    // the "simple" source set renders the two-action SimpleHomeScreen.
                    AppExperienceContent(
                        startDestination = startupState.startDestination,
                        viewModel = viewModel,
                        controller = controller,
                        shellState = shellState,
                        snackbarHostState = snackbarHostState,
                    )
                }
                MainActivityDialogs(
                    viewModel = viewModel,
                    controller = controller,
                    uiState = uiState,
                    shellState = shellState,
                )
            }
        }
    }
}

@Composable
private fun MainActivityEffects(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    shellState: MainActivityShellState,
    canHandleStartConfiguredModeRequest: Boolean,
    connectionState: ConnectionState,
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(viewModel) {
        viewModel.initialize()
    }

    LaunchedEffect(viewModel, controller) {
        viewModel.effects.collect { effect ->
            controller.onEffect(effect)
        }
    }

    val performHaptic = rememberRipDpiHapticPerformer()

    LaunchedEffect(controller) {
        controller.uiEvents.collect { event ->
            when (event) {
                is MainActivityUiEvent.ShowErrorSnackbar -> {
                    performHaptic(RipDpiHapticFeedback.Error)
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

    LaunchedEffect(shellState.startConfiguredModeRequested, canHandleStartConfiguredModeRequest) {
        if (shellState.startConfiguredModeRequested && canHandleStartConfiguredModeRequest) {
            viewModel.onPrimaryConnectionAction()
            controller.consumeStartConfiguredModeRequest()
        }
    }

    LaunchedEffect(shellState.stopConfiguredModeRequested) {
        if (shellState.stopConfiguredModeRequested) {
            viewModel.onStopRequested()
            controller.consumeStopConfiguredModeRequest()
        }
    }

    LaunchedEffect(connectionState) {
        controller.onConnectionStateChanged(connectionState)
    }
}

@Composable
private fun MainActivityDialogs(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    uiState: MainUiState,
    shellState: MainActivityShellState,
) {
    if (shellState.vpnPermissionDialogVisible) {
        VpnPermissionDialog(
            uiState = uiState,
            onDismiss = controller::dismissVpnPermissionDialog,
            onContinue = viewModel::onVpnPermissionContinueRequested,
        )
    }

    val pendingCrashReport by viewModel.pendingCrashReport.collectAsStateWithLifecycle()
    pendingCrashReport?.let { report ->
        CrashReportDialog(
            report = report,
            onShare = {
                val (title, body) = viewModel.crashReports.buildShareText(report)
                controller.requestShareDiagnosticsSummary(title, body)
                viewModel.crashReports.dismiss()
            },
            onDismiss = { viewModel.crashReports.dismiss() },
        )
    }
}
