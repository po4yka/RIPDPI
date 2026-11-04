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
) : JourneyPage<HomePage>(rule, RipDpiTestTags.screen(Route.Status)) {
    val modesHeader = RipDpiTestTags.StatusModesDiagnosticsHeader
    val localBypassCard = RipDpiTestTags.statusModeCard("LocalDpiBypass")
    val localBypassPrimary = RipDpiTestTags.statusModePrimaryAction("LocalDpiBypass")
    val remoteVpnCard = RipDpiTestTags.statusModeCard("RemoteVpn")
    val remoteVpnPrimary = RipDpiTestTags.statusModePrimaryAction("RemoteVpn")
    val diagnosticCard = RipDpiTestTags.statusModeCard("Diagnostic")
    val setupHealthRow = RipDpiTestTags.StatusAdvisoryCard
    val setupHealthAction = RipDpiTestTags.StatusSetupHealthAction
    val advisoryPrimary = RipDpiTestTags.StatusAdvisoryPrimaryAction
    val advisoryMore = RipDpiTestTags.StatusAdvisoryMore
    val setupHealthDetails = RipDpiTestTags.StatusSetupHealthDetails
    val actuatorButton = RipDpiTestTags.ConnectionActuatorButton
    val actuatorRouteLabel = RipDpiTestTags.ConnectionActuatorRouteLabel
    val actuatorTerminalLabel = RipDpiTestTags.ConnectionActuatorTerminalLabel
    val errorSnackbar = RipDpiTestTags.MainErrorSnackbar
    val disconnectConfirm = RipDpiTestTags.ConnectionActuatorDisconnectConfirm

    /** Opens the modes accordion unless the screen already shows it expanded. */
    fun expandModes() {
        waitFor(RipDpiTestTags.StatusModesDiagnosticsHeader)
        if (count(RipDpiTestTags.StatusModesDiagnosticsCollapsed) > 0) {
            click(modesHeader)
        }
        waitFor(RipDpiTestTags.StatusModesDiagnosticsExpanded)
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
