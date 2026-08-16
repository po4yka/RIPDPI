package com.poyka.ripdpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.chrome.RipDpiRemediationCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.debug.RecompositionReportEffect
import com.poyka.ripdpi.ui.screens.crash.CrashReportDialog
import com.poyka.ripdpi.ui.screens.permissions.VpnPermissionDialog
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun MainActivityContent(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
) {
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()

    RecompositionReportEffect()

    RipDpiTheme(themePreference = startupState.theme) {
        when (startupState.readiness) {
            AppStartupReadinessState.Pending -> {
                StartupRecoveryGateContent(failed = false, onRetry = {})
            }

            AppStartupReadinessState.Failed -> {
                StartupRecoveryGateContent(failed = true, onRetry = viewModel.retryStartupRecovery)
            }

            AppStartupReadinessState.Ready -> {
                ReadyMainActivityContent(
                    viewModel = viewModel,
                    controller = controller,
                    startupState = startupState,
                )
            }
        }
    }
}

@Composable
private fun StartupRecoveryGateContent(
    failed: Boolean,
    onRetry: () -> Unit,
) {
    RipDpiContentScreenScaffold(
        title =
            androidx.compose.ui.res
                .stringResource(R.string.app_name),
        contentWidth = RipDpiScaffoldWidth.Form,
    ) {
        RipDpiRemediationCard(
            title =
                androidx.compose.ui.res.stringResource(
                    if (failed) R.string.startup_recovery_failed_title else R.string.startup_recovery_preparing_title,
                ),
            summary =
                androidx.compose.ui.res.stringResource(
                    if (failed) R.string.startup_recovery_failed_body else R.string.startup_recovery_preparing_body,
                ),
            steps = persistentListOf(),
            tone = if (failed) StatusIndicatorTone.Error else StatusIndicatorTone.Active,
            // Startup recovery is still running until it either succeeds or reports failure.
            pulsing = !failed,
            actionLabel =
                if (failed) {
                    androidx.compose.ui.res
                        .stringResource(R.string.startup_recovery_retry)
                } else {
                    null
                },
            onAction = if (failed) onRetry else null,
            actionVariant = RipDpiButtonVariant.Primary,
            cardTestTag =
                if (failed) RipDpiTestTags.StartupRecoveryFailure else RipDpiTestTags.StartupRecoveryPending,
            actionTestTag = if (failed) RipDpiTestTags.StartupRecoveryRetry else null,
        )
    }
}

@Composable
private fun ReadyMainActivityContent(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    startupState: MainStartupState,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shellState by controller.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    MainActivityEffects(
        viewModel = viewModel,
        controller = controller,
        shellState = shellState,
        canHandleStartConfiguredModeRequest = uiState.settingsLoaded,
        connectionState = uiState.connectionState,
        snackbarHostState = snackbarHostState,
    )
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

@Composable
private fun MainActivityEffects(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    shellState: MainActivityShellState,
    canHandleStartConfiguredModeRequest: Boolean,
    connectionState: ConnectionState,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val copyActionLabel = stringResource(R.string.home_diagnostics_copy_support_code)
    val clipboardLabel = stringResource(R.string.clipboard_label_error)
    LaunchedEffect(viewModel) {
        viewModel.initialize()
        viewModel.onForeground()
    }

    LifecycleEventEffect(viewModel.effects, controller::onEffect)

    val performHaptic = rememberRipDpiHapticPerformer()

    LifecycleEventEffect(controller.uiEvents) { event ->
        when (event) {
            is MainActivityUiEvent.ShowErrorSnackbar -> {
                performHaptic(RipDpiHapticFeedback.Error)
                val result =
                    snackbarHostState.showRipDpiSnackbar(
                        message = event.message.withSupportCode(event.supportCode),
                        tone = RipDpiSnackbarTone.Error,
                        actionLabel = event.supportText?.let { copyActionLabel },
                        duration =
                            if (event.supportText != null) {
                                SnackbarDuration.Long
                            } else {
                                SnackbarDuration.Short
                            },
                        testTag = RipDpiTestTags.MainErrorSnackbar,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    event.supportText?.let { supportText ->
                        context
                            .getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(clipboardLabel, supportText))
                        Toast.makeText(context, R.string.diagnostics_support_details_copied, Toast.LENGTH_SHORT).show()
                    }
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

private fun String.withSupportCode(supportCode: String?): String = supportCode?.let { "$this\n$it" } ?: this

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
