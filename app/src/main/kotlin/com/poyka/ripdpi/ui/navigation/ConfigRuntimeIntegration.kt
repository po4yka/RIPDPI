package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.ui.screens.connection.ConnectionRuntimeCallbacks
import com.poyka.ripdpi.ui.screens.connection.ConnectionRuntimeIntegration
import com.poyka.ripdpi.ui.screens.connection.ConnectionRuntimeOperation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun configRuntimeIntegration(viewModel: MainViewModel): ConnectionRuntimeIntegration {
    val feedback =
        remember(viewModel) {
            viewModel.uiState.map { it.connectionState to it.errorMessage }.distinctUntilChanged()
        }
    val initialSnapshot = remember(viewModel) { viewModel.uiState.value.let { it.connectionState to it.errorMessage } }
    val snapshot by feedback.collectAsStateWithLifecycle(initialValue = initialSnapshot)
    return remember(viewModel, snapshot) {
        ConnectionRuntimeIntegration(
            operation = ConnectionRuntimeOperation.Starting.takeIf { snapshot.first == ConnectionState.Connecting },
            error = snapshot.second.takeIf { snapshot.first == ConnectionState.Error },
            callbacks =
                ConnectionRuntimeCallbacks(
                    dismissError = viewModel::dismissError,
                ),
        )
    }
}
