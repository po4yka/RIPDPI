package com.poyka.ripdpi.ui.navigation

import androidx.navigation.NavHostController

internal fun NavHostController.navigateToConnectionFromHistory() {
    navigate(Route.Config) {
        launchSingleTop = true
    }
}

internal fun NavHostController.navigateToDiagnosticsFromHistory() {
    navigate(Route.Diagnostics(autoStartScan = false)) {
        launchSingleTop = true
    }
}
