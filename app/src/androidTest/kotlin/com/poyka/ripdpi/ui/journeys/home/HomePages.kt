package com.poyka.ripdpi.ui.journeys.home

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

internal class HomePage(
    rule: JourneyRule,
) : JourneyPage<HomePage>(rule, RipDpiTestTags.screen(Route.Home)) {
    val modesHeader = RipDpiTestTags.HomeModesDiagnosticsHeader
    val localBypassCard = RipDpiTestTags.homeModeCard("LocalDpiBypass")
    val localBypassPrimary = RipDpiTestTags.homeModePrimaryAction("LocalDpiBypass")
    val remoteVpnCard = RipDpiTestTags.homeModeCard("RemoteVpn")
    val remoteVpnPrimary = RipDpiTestTags.homeModePrimaryAction("RemoteVpn")
    val diagnosticCard = RipDpiTestTags.homeModeCard("Diagnostic")
    val setupHealthRow = RipDpiTestTags.HomeSetupHealthRow
    val setupHealthAction = RipDpiTestTags.HomeSetupHealthAction
    val advisoryPrimary = RipDpiTestTags.HomeAdvisoryPrimaryAction
    val advisoryMore = RipDpiTestTags.HomeAdvisoryMore
    val setupHealthDetails = RipDpiTestTags.HomeSetupHealthDetails
    val actuatorRouteLabel = RipDpiTestTags.ConnectionActuatorRouteLabel
    val actuatorTerminalLabel = RipDpiTestTags.ConnectionActuatorTerminalLabel
    val errorSnackbar = RipDpiTestTags.MainErrorSnackbar

    /** Opens the modes accordion unless the screen already shows it expanded. */
    fun expandModes() {
        waitFor(RipDpiTestTags.HomeModesDiagnosticsHeader)
        if (count(RipDpiTestTags.HomeModesDiagnosticsCollapsed) > 0) {
            click(modesHeader)
        }
        waitFor(RipDpiTestTags.HomeModesDiagnosticsExpanded)
    }

    fun waitForServiceState(
        store: ServiceStateStore,
        status: AppStatus,
        mode: Mode,
    ) = rule.waitUntil(10_000) { store.status.value == status to mode }

    fun waitForHalted(store: ServiceStateStore) =
        rule.waitUntil(timeoutMillis = 10_000) { store.status.value.first == AppStatus.Halted }

    /** Clicks the nth notice inside the expanded advisory disclosure. */
    fun clickAdvisoryAt(index: Int) {
        val node = rule.onAllNodes(hasTestTag(setupHealthAction))[index]
        node.performScrollTo()
        node.performClick()
    }
}

internal class SettingsPage(
    rule: JourneyRule,
) : JourneyPage<SettingsPage>(rule, RipDpiTestTags.screen(Route.Settings)) {
    val guidanceBanner = RipDpiTestTags.SettingsBackgroundGuidanceBanner
    val bannerDismiss = RipDpiTestTags.WarningBannerDismiss
    val batteryPermission = RipDpiTestTags.settingsPermission(PermissionKind.BatteryOptimization)
}
