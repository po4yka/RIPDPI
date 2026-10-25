package com.poyka.ripdpi.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.proxyimport.PendingSubscriptionImportRequest
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
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
            onContinue = { request -> navController.navigateSubscriptionImport(request) },
        )
    }
    composable<Route.QrScanner> { backStackEntry ->
        QrScannerRoute(
            sessionId = backStackEntry.id,
            onBack = { navController.popBackStack() },
            onProfileScanned = { request ->
                navController.navigateProfileImport(request)
            },
            onSubscriptionScanned = { request -> navController.navigateSubscriptionImport(request) },
            onAmneziaWgConfScanned = { conf -> navController.navigateAmneziaWgImport(conf) },
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
 * Routes to the single-profile import-confirmation destination for a clipboard- or
 * scanner-sourced [request], staging the credential-bearing profile outside saved state.
 */
internal fun NavHostController.navigateProfileImport(request: ProxyImportRequest.Profile) {
    val importToken = PendingProxyImportStore.process.stage(request.profile)
    navigate(Route.ProfileImportConfirm(importToken = importToken)) {
        launchSingleTop = true
    }
}

/** Stages a subscription [request] outside saved state and opens its confirmation screen. */
internal fun NavHostController.navigateSubscriptionImport(request: PendingSubscriptionImportRequest) {
    val importToken = PendingProxyImportStore.process.stageSubscription(request) ?: return
    navigate(Route.SubscriptionImportConfirm(importToken = importToken)) {
        launchSingleTop = true
        popUpTo<Route.SubscriptionAdd> { inclusive = true }
    }
}

/** Stages a scanned AmneziaWG [conf] (it carries the private key) and opens the prefilled editor. */
internal fun NavHostController.navigateAmneziaWgImport(conf: String) {
    val importToken = PendingProxyImportStore.process.stageAmneziaWgConf(conf) ?: return
    navigate(Route.AmneziaWgProfile(importToken = importToken)) {
        launchSingleTop = true
        popUpTo<Route.QrScanner> { inclusive = true }
    }
}
