package com.poyka.ripdpi.ui.journeys.home

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.scrollAndClick
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import kotlinx.coroutines.runBlocking

internal typealias JourneyComposeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

internal class HomePage(
    provider: SemanticsNodeInteractionsProvider,
) : ComposeScreen<HomePage>(provider, { hasTestTag(RipDpiTestTags.screen(Route.Home)) }) {
    fun tagged(tag: String): KNode = onNode { hasTestTag(tag) }

    val modesHeader = tagged(RipDpiTestTags.HomeModesDiagnosticsHeader)
    val localBypassCard = tagged(RipDpiTestTags.homeModeCard("LocalDpiBypass"))
    val localBypassPrimary = tagged(RipDpiTestTags.homeModePrimaryAction("LocalDpiBypass"))
    val remoteVpnCard = tagged(RipDpiTestTags.homeModeCard("RemoteVpn"))
    val remoteVpnPrimary = tagged(RipDpiTestTags.homeModePrimaryAction("RemoteVpn"))
    val diagnosticCard = tagged(RipDpiTestTags.homeModeCard("Diagnostic"))
    val setupHealthRow = tagged(RipDpiTestTags.HomeSetupHealthRow)
    val advisoryPrimary = tagged(RipDpiTestTags.HomeAdvisoryPrimaryAction)
    val advisoryMore = tagged(RipDpiTestTags.HomeAdvisoryMore)
    val setupHealthDetails = tagged(RipDpiTestTags.HomeSetupHealthDetails)
    val actuatorRouteLabel = tagged(RipDpiTestTags.ConnectionActuatorRouteLabel)
    val actuatorTerminalLabel = tagged(RipDpiTestTags.ConnectionActuatorTerminalLabel)
    val vpnPermissionDialog = tagged(RipDpiTestTags.VpnPermissionDialog)
    val vpnPermissionDismiss = tagged(RipDpiTestTags.VpnPermissionDialogDismiss)
}

internal fun JourneyComposeRule.tagCount(tag: String): Int = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

private fun JourneyComposeRule.waitWithTree(
    what: String,
    timeoutMillis: Long,
    condition: () -> Boolean,
) {
    try {
        waitUntil(timeoutMillis, condition)
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("$what\n${onRoot(useUnmergedTree = true).printToString(maxDepth = 12)}", error)
    }
}

internal fun JourneyComposeRule.waitForTagGone(
    tag: String,
    timeoutMillis: Long = 5_000,
) = waitWithTree("tag $tag still present", timeoutMillis) { tagCount(tag) == 0 }

internal fun JourneyComposeRule.waitForTag(
    tag: String,
    timeoutMillis: Long = 10_000,
) = waitWithTree("tag $tag never appeared", timeoutMillis) { tagCount(tag) > 0 }

/** Opens the modes accordion unless the screen already shows it expanded. */
internal fun HomePage.expandModes(rule: JourneyComposeRule) {
    rule.waitForTag(RipDpiTestTags.HomeModesDiagnosticsHeader)
    if (rule.tagCount(RipDpiTestTags.HomeModesDiagnosticsCollapsed) > 0) {
        modesHeader.scrollAndClick()
    }
    rule.waitForTag(RipDpiTestTags.HomeModesDiagnosticsExpanded)
}

internal fun JourneyComposeRule.waitForServiceState(
    store: ServiceStateStore,
    status: AppStatus,
    mode: Mode,
    timeoutMillis: Long = 10_000,
) = waitUntil(timeoutMillis) { store.status.value == status to mode }

internal fun JourneyComposeRule.waitForSettings(
    repository: AppSettingsRepository,
    timeoutMillis: Long = 5_000,
    predicate: (AppSettings) -> Boolean,
) = waitWithTree("settings predicate never held", timeoutMillis) {
    predicate(runBlocking { repository.snapshot() })
}

/** Clicks the nth notice inside the expanded advisory disclosure. */
internal fun JourneyComposeRule.clickAdvisoryAt(index: Int) {
    val node = onAllNodes(hasTestTag(RipDpiTestTags.HomeSetupHealthAction))[index]
    node.performScrollTo()
    node.performClick()
}
