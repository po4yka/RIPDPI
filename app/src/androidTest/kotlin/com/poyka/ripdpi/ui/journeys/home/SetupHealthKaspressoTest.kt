package com.poyka.ripdpi.ui.journeys.home

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class SetupHealthKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            permissionPreset = AutomationPermissionPreset.VpnMissing,
        ),
    ) {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    // The automation service preset intercepts start, so the in-app VPN permission dialog is not
    // reachable from a Home tap here; MainActivityShellInstrumentedTest owns that dialog.
    @Test
    fun missingVpnConsentKeepsSetupHealthAndAllModeCardsAvailable() =
        run {
            with(HomePage(composeRule)) {
                step("Setup health row is shown and the service is halted") {
                    node(setupHealthRow).assertIsDisplayed()
                    node(advisoryPrimary).assertIsDisplayed()
                    assertEquals(AppStatus.Halted, serviceStateStore.status.value.first)
                }
                step("Expanding the disclosure shows all three mode cards") {
                    expandModes()
                    awaitDisplayed(localBypassCard)
                    awaitDisplayed(remoteVpnCard)
                    awaitDisplayed(diagnosticCard)
                }
            }
        }
}
