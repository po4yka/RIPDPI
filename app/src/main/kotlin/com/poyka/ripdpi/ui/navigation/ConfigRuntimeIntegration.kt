package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.ConfigRuntimeOperation
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.ui.screens.config.ConfigRuntimeCallbacks
import com.poyka.ripdpi.ui.screens.config.ConfigRuntimeIntegration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun configRuntimeIntegration(viewModel: MainViewModel): ConfigRuntimeIntegration {
    val feedback =
        remember(viewModel) {
            viewModel.uiState.map { it.connectionState to it.errorMessage }.distinctUntilChanged()
        }
    val initialSnapshot = remember(viewModel) { viewModel.uiState.value.let { it.connectionState to it.errorMessage } }
    val snapshot by feedback.collectAsStateWithLifecycle(initialValue = initialSnapshot)
    return remember(viewModel, snapshot) {
        ConfigRuntimeIntegration(
            operation = ConfigRuntimeOperation.Starting.takeIf { snapshot.first == ConnectionState.Connecting },
            error = snapshot.second.takeIf { snapshot.first == ConnectionState.Error },
            callbacks =
                ConfigRuntimeCallbacks(
                    requestVpnPermission = { viewModel.onToggleVpn(true) },
                    dismissError = viewModel::dismissError,
                ),
        )
    }
}
