package com.poyka.ripdpi.ui.journeys.home

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.integration.scrollAndClick
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Test

@HiltAndroidTest
class SetupHealthKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            permissionPreset = AutomationPermissionPreset.VpnMissing,
        ),
    ) {
    @Test
    fun missingVpnPermissionRowOpensInAppDialogThatDismissesBackToTheRow() =
        run {
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Setup health row offers the repair action") {
                    setupHealthRow.assertIsDisplayed()
                    advisoryPrimary.assertIsDisplayed()
                }
                step("Acting on it opens the in-app VPN permission dialog") {
                    advisoryPrimary.scrollAndClick()
                    composeRule.waitForTag(RipDpiTestTags.VpnPermissionDialog)
                    vpnPermissionDialog.assertIsDisplayed()
                }
                step("Dismissing it leaves the repair action in place") {
                    vpnPermissionDismiss.scrollAndClick()
                    composeRule.waitForTagGone(RipDpiTestTags.VpnPermissionDialog)
                    advisoryPrimary.assertIsDisplayed()
                }
                step("The mode cards stay available while setup is incomplete") {
                    expandModes(composeRule)
                    localBypassCard.performScrollTo()
                    localBypassCard.assertIsDisplayed()
                    remoteVpnCard.performScrollTo()
                    remoteVpnCard.assertIsDisplayed()
                    diagnosticCard.performScrollTo()
                    diagnosticCard.assertIsDisplayed()
                }
            }
        }
}
