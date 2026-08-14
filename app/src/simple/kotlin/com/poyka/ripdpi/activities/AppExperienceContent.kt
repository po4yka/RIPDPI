package com.poyka.ripdpi.activities

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
    @Suppress("UNUSED_PARAMETER") controller: MainActivityShellController,
    @Suppress("UNUSED_PARAMETER") shellState: MainActivityShellState,
    snackbarHostState: SnackbarHostState,
) {
    SimpleHomeScreen(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
    )
}
