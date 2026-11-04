package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.poyka.ripdpi.ui.components.LifecycleEventEffect

internal data class ConnectionRuntimePresentation(
    val uiState: ConnectionUiState,
    val callbacks: ConnectionRuntimeCallbacks,
)

@Composable
internal fun connectionRuntimePresentation(
    viewModel: ConnectionViewModel,
    uiState: ConnectionUiState,
    integration: ConnectionRuntimeIntegration,
): ConnectionRuntimePresentation {
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPersistedSnapshot()
        onPauseOrDispose {}
    }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(viewModel.effects) { effect ->
        when (effect) {
            is ConnectionEffect.Message -> {
                feedbackMessage = effect.text
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
    return ConnectionRuntimePresentation(
        uiState =
            uiState.copy(
                runtimeOperation = uiState.runtimeOperation ?: integration.operation,
                runtimeError = runtimeError,
            ),
        callbacks =
            ConnectionRuntimeCallbacks(
                dismissError = {
                    feedbackMessage = null
                    integration.callbacks.dismissError()
                },
            ),
    )
}
