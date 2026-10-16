package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.poyka.ripdpi.activities.ConfigEffect
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel

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
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is ConfigEffect.Message) feedbackMessage = effect.text
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
