package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
internal fun ModeEditorEffects(
    viewModel: ConnectionViewModel,
    snackbarHostState: SnackbarHostState,
    onHydrationFailure: () -> Unit,
    onBack: () -> Unit,
) {
    val validationMessage = stringResource(R.string.config_validation_fix)
    val performHaptic = rememberRipDpiHapticPerformer()
    LifecycleEventEffect(viewModel.effects) { effect ->
        when (effect) {
            ConnectionEffect.SaveSuccess -> {
                performHaptic(RipDpiHapticFeedback.Success)
                onBack()
            }

            ConnectionEffect.ValidationFailed -> {
                performHaptic(RipDpiHapticFeedback.Error)
                snackbarHostState.showRipDpiSnackbar(
                    message = validationMessage,
                    tone = RipDpiSnackbarTone.Warning,
                    duration = SnackbarDuration.Short,
                    testTag = RipDpiTestTags.ModeEditorValidationSnackbar,
                )
            }

            is ConnectionEffect.EditorHydrationFailed -> {
                if (viewModel.editorHydrationFailures.abort(effect.sessionId)) {
                    onHydrationFailure()
                    performHaptic(RipDpiHapticFeedback.Error)
                }
            }

            is ConnectionEffect.Message -> {
                snackbarHostState.showRipDpiSnackbar(
                    message = effect.text,
                    tone = RipDpiSnackbarTone.Warning,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}
