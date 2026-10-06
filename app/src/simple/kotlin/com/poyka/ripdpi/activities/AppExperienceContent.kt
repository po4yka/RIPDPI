package com.poyka.ripdpi.activities

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.simple.SimpleHomeScreen

/**
 * "simple" experience: a two-action surface (connect with the compiled-in config,
 * run a network diagnostic report) for field testers. Same signature as the "full"
 * seam; the nav-host parameters are unused here by design.
 */
@Composable
internal fun AppExperienceContent(
    @Suppress("UNUSED_PARAMETER") startDestination: Route,
    viewModel: MainViewModel,
    pauseViewModel: HomePauseViewModel,
    @Suppress("UNUSED_PARAMETER") controller: MainActivityShellController,
    @Suppress("UNUSED_PARAMETER") shellState: MainActivityShellState,
    snackbarHostState: SnackbarHostState,
    onExportEligibilityChanged: (Boolean) -> Unit,
    exportPreviewContent: @Composable () -> Unit,
) {
    ExportPreviewContentGate(
        unlocked = true,
        onEligibilityChanged = onExportEligibilityChanged,
        content = exportPreviewContent,
    )
    SimpleHomeScreen(
        viewModel = viewModel,
        pauseViewModel = pauseViewModel,
        snackbarHostState = snackbarHostState,
    )
}
