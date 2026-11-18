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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.withoutBidiIsolates
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.chrome.RipDpiRemediationCard
import com.poyka.ripdpi.ui.components.feedback.LocalRipDpiHandOff
import com.poyka.ripdpi.ui.components.feedback.RipDpiHandOffReveal
import com.poyka.ripdpi.ui.components.feedback.RipDpiHandOffState
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.debug.RecompositionReportEffect
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.navigation.RipDpiNavHostActions
import com.poyka.ripdpi.ui.navigation.RipDpiNavHostLaunchRequests
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.crash.CrashReportDialog
import com.poyka.ripdpi.ui.screens.diagnostics.state.summary
import com.poyka.ripdpi.ui.screens.permissions.VpnPermissionDialog
import com.poyka.ripdpi.ui.screens.status.StatusPauseViewModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberSystemContrastLevel
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun MainActivityContent(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    exportPreview: ExportPreviewViewModel,
    pauseViewModelFactory: @Composable () -> StatusPauseViewModel = { hiltViewModel() },
) {
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()

    RecompositionReportEffect()

    RipDpiTheme(
        themePreference = startupState.theme,
        contrastLevel = rememberSystemContrastLevel(),
        dynamicColor = startupState.dynamicColor,
        hapticFeedbackDisabled = startupState.hapticFeedbackDisabled || !startupState.isReady,
    ) {
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
                    exportPreview = exportPreview,
                    pauseViewModelFactory = pauseViewModelFactory,
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
    exportPreview: ExportPreviewViewModel,
    pauseViewModelFactory: @Composable () -> StatusPauseViewModel,
) {
    val runtimeState by viewModel.activityRuntimeState.collectAsStateWithLifecycle(
        initialValue = remember(viewModel) { viewModel.uiState.value.toActivityRuntimeUiState() },
    )
    val shellState by controller.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val onboardingHandOff = remember { RipDpiHandOffState() }
    val localeTags = LocalConfiguration.current.locales.toLanguageTags()

    LaunchedEffect(viewModel, localeTags) {
        viewModel.onLocaleChanged(localeTags)
    }

    MainActivityEffects(
        viewModel = viewModel,
        controller = controller,
        shellState = shellState,
        canHandleStartConfiguredModeRequest = runtimeState.settingsLoaded,
        connectionState = runtimeState.connectionState,
        snackbarHostState = snackbarHostState,
        onboardingHandOff = onboardingHandOff,
    )
    // Above the gate below, so a hand-off is not lost if the settings reload while onboarding completes.
    OnboardingHandOffTrigger(onboardingHandOff, startupState.startDestination)
    if (!runtimeState.settingsLoaded) {
        StartupRecoveryGateContent(failed = false, onRetry = {})
        return
    }
    val pauseViewModel = pauseViewModelFactory()
    val performHaptic = rememberRipDpiHapticPerformer()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .ripDpiAutomationTreeRoot(),
    ) {
        CompositionLocalProvider(LocalRipDpiHandOff provides onboardingHandOff) {
            key(
                startupState.startDestination,
                navigationStateKey(startupState.startDestination, viewModel.navigationSessionKey),
            ) {
                MainNavHost(
                    startDestination = startupState.startDestination,
                    viewModel = viewModel,
                    pauseViewModel = pauseViewModel,
                    controller = controller,
                    shellState = shellState,
                    snackbarHostState = snackbarHostState,
                    onExportEligibilityChanged = exportPreview::setContentUnlocked,
                    exportPreviewContent = {
                        val previewState by exportPreview.state.collectAsStateWithLifecycle()
                        previewState?.let { preview ->
                            key(preview.token) {
                                com.poyka.ripdpi.ui.components.export.ExportPreviewSheet(
                                    presentation = preview.presentation,
                                    onConfirm = { exportPreview.confirm(preview.token) },
                                    onCancel = { exportPreview.cancel(preview.token) },
                                    onDismiss = { exportPreview.cancel(preview.token) },
                                )
                            }
                        }
                    },
                )
            }
        }
        RipDpiHandOffReveal(
            state = onboardingHandOff,
            // The line has arrived in the hero. The button that ended onboarding gave its own haptic at the press.
            onLanded = { performHaptic(RipDpiHapticFeedback.Confirm) },
        )
        MainActivityDialogs(
            viewModel = viewModel,
            controller = controller,
            shellState = shellState,
        )
    }
}

/**
 * The content is replaced, not navigated, when the start destination leaves onboarding, so the reveal that
 * joins the two screens lives above it. The reveal starts in the composition that replaces the content, not in
 * an effect after it: the two changes reach the screen in one snapshot, so no frame shows the main screen
 * without the veil.
 */
@Composable
internal fun OnboardingHandOffTrigger(
    handOff: RipDpiHandOffState,
    startDestination: Route,
) {
    val animated = RipDpiThemeTokens.motion.animationsEnabled
    val previous = remember { mutableStateOf(startDestination) }
    // Not observed: the reveal progress changes on every frame, and this composable must not recompose with it.
    Snapshot.withoutReadObservation {
        if (previous.value == Route.Onboarding && startDestination != Route.Onboarding) {
            handOff.start(animated)
        }
        if (previous.value != startDestination) previous.value = startDestination
    }
}

internal fun navigationStateKey(
    startDestination: Route,
    sessionKey: String,
): String = if (startDestination == Route.BiometricPrompt || startDestination == Route.Onboarding) sessionKey else ""

@Composable
private fun MainActivityEffects(
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    shellState: MainActivityShellState,
    canHandleStartConfiguredModeRequest: Boolean,
    connectionState: ConnectionState,
    snackbarHostState: SnackbarHostState,
    onboardingHandOff: RipDpiHandOffState,
) {
    val context = LocalContext.current
    val copyActionLabel = stringResource(R.string.status_diagnostics_copy_support_code)
    val clipboardLabel = stringResource(R.string.clipboard_label_error)
    LaunchedEffect(viewModel) {
        viewModel.initialize()
        viewModel.onForeground()
    }

    LifecycleEventEffect(viewModel.effects, controller::onEffect)

    val performHaptic = rememberRipDpiHapticPerformer()

    LifecycleEventEffect(controller.uiEvents) { event ->
        when (event) {
            MainActivityUiEvent.ConnectionEstablished -> {
                // One haptic if the connection comes up while the hand-off runs.
                onboardingHandOff.muteLanding()
                performHaptic(RipDpiHapticFeedback.Success)
            }

            is MainActivityUiEvent.ShowErrorSnackbar -> {
                if (event.hapticFeedbackEnabled) performHaptic(RipDpiHapticFeedback.Error)
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
                            ?.setPrimaryClip(ClipData.newPlainText(clipboardLabel, supportText.withoutBidiIsolates()))
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
    shellState: MainActivityShellState,
) {
    if (shellState.vpnPermissionDialogVisible) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                controller.requestShareText(title, body)
                viewModel.crashReports.dismiss()
            },
            onDismiss = { viewModel.crashReports.dismiss() },
        )
    }
}

@Composable
private fun MainNavHost(
    startDestination: Route,
    viewModel: MainViewModel,
    pauseViewModel: StatusPauseViewModel,
    controller: MainActivityShellController,
    shellState: MainActivityShellState,
    snackbarHostState: SnackbarHostState,
    onExportEligibilityChanged: (Boolean) -> Unit,
    exportPreviewContent: @Composable () -> Unit,
) {
    RipDpiNavHost(
        startDestination = startDestination,
        mainViewModel = viewModel,
        pauseViewModel = pauseViewModel,
        actions =
            RipDpiNavHostActions(
                onSaveLogs = controller::requestSaveLogs,
                onShareDebugBundle = controller::requestShareDebugBundle,
                onPrepareDiagnosticsExport = controller::requestPrepareDiagnosticsExport,
                onShareText = controller::requestShareText,
                onRepairPermission = { permission ->
                    viewModel.onRepairPermissionRequested(permission)
                },
            ),
        launchRequests =
            RipDpiNavHostLaunchRequests(
                launchHomeRequested = shellState.launchHomeRequested,
                onLaunchHomeHandled = controller::consumeLaunchHomeRequest,
                launchRouteRequested = shellState.launchRouteRequested,
                onLaunchRouteHandled = controller::consumeLaunchRouteRequest,
                sharedDiagnosticFragmentRequested = shellState.sharedDiagnosticFragmentRequested,
                onSharedDiagnosticFragmentHandled = controller::consumeDiagnosticShareFragmentRequest,
                importRouteRequested = shellState.importRouteRequested,
                onImportRouteHandled = controller::consumeImportRouteRequest,
                relockRequested = shellState.relockRequested,
                onRelockHandled = controller::consumeRelockRequest,
            ),
        snackbarHostState = snackbarHostState,
        onExportEligibilityChanged = onExportEligibilityChanged,
        exportPreviewContent = exportPreviewContent,
    )
}
