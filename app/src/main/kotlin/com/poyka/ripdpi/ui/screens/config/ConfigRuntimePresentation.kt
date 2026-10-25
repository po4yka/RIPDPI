package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.poyka.ripdpi.activities.ConfigEffect
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer

internal data class ConfigRuntimePresentation(
    val uiState: ConfigUiState,
    val callbacks: ConfigRuntimeCallbacks,
)

@Composable
internal fun configRuntimePresentation(
    viewModel: ConfigViewModel,
    uiState: ConfigUiState,
    integration: ConfigRuntimeIntegration,
): ConfigRuntimePresentation {
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    val performHaptic = rememberRipDpiHapticPerformer()
    LifecycleEventEffect(viewModel.effects) { effect ->
        when (effect) {
            is ConfigEffect.Message -> {
                feedbackMessage = effect.text
            }

            is ConfigEffect.RuntimeConnected -> {
                if (effect.hapticFeedbackEnabled) performHaptic(RipDpiHapticFeedback.Success)
            }

            is ConfigEffect.RuntimeFailed -> {
                if (effect.hapticFeedbackEnabled) performHaptic(RipDpiHapticFeedback.Error)
            }

            else -> {}
        }
    }
    val runtimeError =
        if (integration.operation ==
            null
        ) {
            integration.error ?: uiState.runtimeError ?: feedbackMessage
        } else {
            null
        }
    return ConfigRuntimePresentation(
        uiState =
            uiState.copy(
                runtimeOperation = uiState.runtimeOperation ?: integration.operation,
                runtimeError = runtimeError,
            ),
        callbacks =
            ConfigRuntimeCallbacks(
                requestVpnPermission = integration.callbacks.requestVpnPermission,
                dismissError = {
                    feedbackMessage = null
                    viewModel.dismissRuntimeError()
                    integration.callbacks.dismissError()
                },
            ),
    )
}
