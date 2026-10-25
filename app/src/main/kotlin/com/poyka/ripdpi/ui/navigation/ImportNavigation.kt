package com.poyka.ripdpi.ui.navigation

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.poyka.ripdpi.proxyimport.ImportPayload
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileRoute
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileRoute
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileRoute
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmRoute
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileShareRoute
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionAddRoute
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmRoute
import com.poyka.ripdpi.ui.screens.scanner.QrScannerRoute
import com.poyka.ripdpi.ui.screens.ssh.SshProfileRoute
import com.poyka.ripdpi.ui.screens.support.SupportSettingsRoute
import com.poyka.ripdpi.ui.screens.xray.XrayProfileImportRoute

internal fun NavGraphBuilder.addImportRoutes(navController: NavHostController) {
    composable<Route.ProfileImportConfirm> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.ProfileImportConfirm>()
        ProfileImportConfirmRoute(
            importToken = route.importToken,
            onBack = { navController.popBackStack() },
            onImported = { navController.navigateHome() },
            onUnavailable = { navController.navigateHome() },
        )
    }
    composable<Route.SubscriptionImportConfirm> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.SubscriptionImportConfirm>()
        SubscriptionImportConfirmRoute(
            importToken = route.importToken,
            onBack = { navController.popBackStack() },
            onImported = { navController.navigateHome() },
            onUnavailable = { navController.navigateHome() },
        )
    }
    composable<Route.SupportSettings> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.SupportSettings>()
        SupportSettingsRoute(
            packageJson = route.packageJson,
            onBack = { navController.popBackStack() },
            onApplied = { navController.navigateHome() },
        )
    }
    composable<Route.ProfileShare> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.ProfileShare>()
        ProfileShareRoute(
            profileId = route.profileId,
            onBack = { navController.popBackStack() },
        )
    }
    addCapturedImportRoutes(navController)
    composable<Route.XrayImport> {
        XrayProfileImportRoute(
            onBack = { navController.popBackStack() },
            onFinished = { navController.popBackStack() },
        )
    }
    composable<Route.AnyTlsProfile> {
        AnyTlsProfileRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.MieruProfile> {
        MieruProfileRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.MieruProfileEdit> { entry ->
        MieruProfileRoute(
            onBack = { navController.popBackStack() },
            profileId = entry.toRoute<Route.MieruProfileEdit>().profileId,
        )
    }
    composable<Route.SshProfile> {
        SshProfileRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.SshProfileEdit> { entry ->
        SshProfileRoute(
            onBack = { navController.popBackStack() },
            profileId = entry.toRoute<Route.SshProfileEdit>().profileId,
        )
    }
}

/** In-app import entry points: subscription link entry, QR scanner, and the AmneziaWG editor they feed. */
private fun NavGraphBuilder.addCapturedImportRoutes(navController: NavHostController) {
    composable<Route.SubscriptionAdd> {
        SubscriptionAddRoute(
            onBack = { navController.popBackStack() },
            onContinue = { request -> navController.navigateImport(ImportPayload.Subscription(request)) },
        )
    }
    composable<Route.QrScanner> { backStackEntry ->
        QrScannerRoute(
            sessionId = backStackEntry.id,
            onBack = { navController.popBackStack() },
            onImport = navController::navigateImport,
        )
    }
    composable<Route.AmneziaWgProfile> { entry ->
        AmneziaWgProfileRoute(
            onBack = { navController.popBackStack() },
            importToken = entry.toRoute<Route.AmneziaWgProfile>().importToken,
        )
    }
}

/**
 * Opens the destination that consumes [payload]: the profile or subscription confirmation
 * screen, or the WireGuard / AmneziaWG editor. The credential-bearing payload is staged in
 * the process-local [PendingProxyImportStore]; navigation carries only an opaque token.
 *
 * An in-app entry point (the QR scanner or the subscription link screen) is replaced, so
 * Back returns to where the import started and the camera cannot re-import the same code.
 * A repeat call while the target is already shown (a double tap, a late camera frame) is
 * ignored, so it cannot stage a second copy of the secret.
 */
internal fun NavHostController.navigateImport(payload: ImportPayload) {
    val current = currentBackStackEntry?.destination
    val alreadyShown =
        when (payload) {
            is ImportPayload.Profile -> current?.hasRoute<Route.ProfileImportConfirm>()
            is ImportPayload.Subscription -> current?.hasRoute<Route.SubscriptionImportConfirm>()
            is ImportPayload.WireGuardConf -> current?.hasRoute<Route.AmneziaWgProfile>()
        } == true
    if (alreadyShown) return
    val store = PendingProxyImportStore.process
    val route =
        when (payload) {
            is ImportPayload.Profile -> {
                Route.ProfileImportConfirm(importToken = store.stage(payload.request.profile))
            }

            is ImportPayload.Subscription -> {
                store
                    .stageSubscription(
                        payload.request,
                    )?.let(Route::SubscriptionImportConfirm)
            }

            is ImportPayload.WireGuardConf -> {
                store.stageAmneziaWgConf(payload.conf)?.let(Route::AmneziaWgProfile)
            }
        } ?: return
    val replacedEntryId =
        current?.takeIf { it.hasRoute<Route.QrScanner>() || it.hasRoute<Route.SubscriptionAdd>() }?.id
    navigate(route) {
        launchSingleTop = true
        replacedEntryId?.let { popUpTo(it) { inclusive = true } }
    }
}
