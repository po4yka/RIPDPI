package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.MainViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Status reconnect panel for a settings screen. It exists only while the running session has not picked up the
 * saved settings, so an edit made while connected never looks applied.
 */
@Composable
internal fun savedChangesPanel(viewModel: MainViewModel): (@Composable () -> Unit)? {
    val applied = remember(viewModel) { viewModel.uiState.map { it.appliedConfiguration }.distinctUntilChanged() }
    val state by applied.collectAsStateWithLifecycle(viewModel.uiState.value.appliedConfiguration)
    if (!state.visible || !(state.pending || state.reconnecting)) return null
    return {
        StatusAppliedConfigurationPanel(
            state = state,
            onReconnect = viewModel.onReconnectSavedConfiguration,
            onCancelReconnect = viewModel.onCancelConfigurationReconnect,
        )
    }
}
