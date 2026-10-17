package com.poyka.ripdpi.ui.screens.config

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigEffect
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
internal fun ModeEditorEffects(
    viewModel: ConfigViewModel,
    snackbarHostState: SnackbarHostState,
    onHydrationFailure: () -> Unit,
    onBack: () -> Unit,
) {
    val validationMessage = stringResource(R.string.config_validation_fix)
    val connectedMessage = stringResource(R.string.vpn_connected)
    val performHaptic = rememberRipDpiHapticPerformer()
    LifecycleEventEffect(viewModel.effects) { effect ->
        when (effect) {
            is ConfigEffect.RuntimeConnected -> {
                if (effect.hapticFeedbackEnabled) performHaptic(RipDpiHapticFeedback.Success)
                snackbarHostState.showRipDpiSnackbar(
                    message = connectedMessage,
                    tone = RipDpiSnackbarTone.Default,
                    duration = SnackbarDuration.Short,
                )
            }

            is ConfigEffect.RuntimeFailed -> {
                if (effect.hapticFeedbackEnabled) performHaptic(RipDpiHapticFeedback.Error)
                snackbarHostState.showRipDpiSnackbar(
                    message = effect.message,
                    tone = RipDpiSnackbarTone.Error,
                    duration = SnackbarDuration.Short,
                )
            }

            ConfigEffect.SaveSuccess -> {
                performHaptic(RipDpiHapticFeedback.Success)
                onBack()
            }

            ConfigEffect.ValidationFailed -> {
                performHaptic(RipDpiHapticFeedback.Error)
                snackbarHostState.showRipDpiSnackbar(
                    message = validationMessage,
                    tone = RipDpiSnackbarTone.Warning,
                    duration = SnackbarDuration.Short,
                    testTag = RipDpiTestTags.ModeEditorValidationSnackbar,
                )
            }

            is ConfigEffect.EditorHydrationFailed -> {
                if (viewModel.editorHydrationFailures.abort(effect.sessionId)) {
                    onHydrationFailure()
                    performHaptic(RipDpiHapticFeedback.Error)
                }
            }

            is ConfigEffect.Message -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = effect.text,
                    tone = RipDpiSnackbarTone.Warning,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}
