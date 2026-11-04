package com.poyka.ripdpi.ui.navigation

import androidx.navigation.NavHostController

internal fun NavHostController.navigateToConnectionFromHistory() {
    navigate(Route.Connection) {
        launchSingleTop = true
    }
}

internal fun NavHostController.navigateToDiagnosticsFromHistory() {
    navigate(Route.Diagnostics(autoStartScan = false)) {
        launchSingleTop = true
    }
}
