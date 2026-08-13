package com.poyka.ripdpi.activities

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.navigation.RipDpiNavHostActions
import com.poyka.ripdpi.ui.navigation.RipDpiNavHostLaunchRequests
import com.poyka.ripdpi.ui.navigation.Route

/**
 * "full" experience: the complete tabbed navigation surface. This is the seam that
 * [MainActivityContent] renders once startup is ready; the "simple" source set
 * provides a stripped-down two-action alternative with the same signature.
 */
@Composable
internal fun AppExperienceContent(
    startDestination: Route,
    isSessionAuthenticated: Boolean,
    viewModel: MainViewModel,
    controller: MainActivityShellController,
    shellState: MainActivityShellState,
    snackbarHostState: SnackbarHostState,
) {
    RipDpiNavHost(
        startDestination = startDestination,
        isSessionAuthenticated = isSessionAuthenticated,
        mainViewModel = viewModel,
        actions =
            RipDpiNavHostActions(
                onSaveLogs = controller::requestSaveLogs,
                onShareDebugBundle = controller::requestShareDebugBundle,
                onSaveDiagnosticsArchive = controller::requestSaveDiagnosticsArchive,
                onShareDiagnosticsArchive = controller::requestShareDiagnosticsArchive,
                onShareDiagnosticsSummary = controller::requestShareDiagnosticsSummary,
                onRepairPermission = { permission ->
                    viewModel.onRepairPermissionRequested(permission)
                },
            ),
        launchRequests =
            RipDpiNavHostLaunchRequests(
                launchHomeRequested = shellState.launchHomeRequested,
                onLaunchHomeHandled = controller::consumeLaunchHomeRequest,
                launchRouteRequested = shellState.launchRouteRequested,
                onLaunchRouteHandled = controller::consumeLaunchRouteRequest,
                sharedDiagnosticFragmentRequested = shellState.sharedDiagnosticFragmentRequested,
                onSharedDiagnosticFragmentHandled = controller::consumeDiagnosticShareFragmentRequest,
                importRouteRequested = shellState.importRouteRequested,
                onImportRouteHandled = controller::consumeImportRouteRequest,
                relockRequested = shellState.relockRequested,
                onRelockHandled = controller::consumeRelockRequest,
            ),
        snackbarHostState = snackbarHostState,
    )
}
