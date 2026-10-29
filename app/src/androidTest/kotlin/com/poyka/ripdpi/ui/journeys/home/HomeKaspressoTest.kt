package com.poyka.ripdpi.ui.journeys.home

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationServicePreset
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.integration.scrollAndClick
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class HomeIdleKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            servicePreset = AutomationServicePreset.Idle,
        ),
    ) {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Test
    fun modeCardsAppearAfterDiagnosticsDisclosureExpands() =
        run {
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Expand the modes disclosure") { expandModes(composeRule) }
                step("Local DPI bypass, remote VPN and diagnostic cards are shown") {
                    listOf(localBypassCard, remoteVpnCard, diagnosticCard).forEach {
                        it.performScrollTo()
                        it.assertIsDisplayed()
                    }
                }
                step("No error snackbar shows during normal operation") {
                    assertEquals(0, composeRule.tagCount(RipDpiTestTags.MainErrorSnackbar))
                }
            }
        }

    @Test
    fun localBypassToggleStartsAndStopsProxyService() =
        run {
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Service starts halted") {
                    assertEquals(AppStatus.Halted, serviceStateStore.status.value.first)
                    expandModes(composeRule)
                }
                step("Enabling local bypass dispatches a running proxy service") {
                    localBypassPrimary.scrollAndClick()
                    composeRule.waitForServiceState(serviceStateStore, AppStatus.Running, Mode.Proxy)
                }
                step("Disabling local bypass returns the service to halted") {
                    localBypassPrimary.scrollAndClick()
                    composeRule.waitUntil(timeoutMillis = 10_000) {
                        serviceStateStore.status.value.first == AppStatus.Halted
                    }
                }
            }
        }
}

@HiltAndroidTest
class HomeConnectedProxyKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            servicePreset = AutomationServicePreset.ConnectedProxy,
        ),
    ) {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Test
    fun connectedProxyPresetShowsLocalBypassActiveAndToggleReturnsToIdle() =
        run {
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Preset reports a running proxy and Home shows the route labels") {
                    assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
                    actuatorRouteLabel.performScrollTo()
                    actuatorRouteLabel.assertIsDisplayed()
                    actuatorTerminalLabel.assertIsDisplayed()
                }
                step("Local bypass card is shown") {
                    expandModes(composeRule)
                    localBypassCard.performScrollTo()
                    localBypassCard.assertIsDisplayed()
                }
                step("Stopping returns to idle, enabling again runs the proxy") {
                    localBypassPrimary.scrollAndClick()
                    composeRule.waitUntil(timeoutMillis = 10_000) {
                        serviceStateStore.status.value.first == AppStatus.Halted
                    }
                    localBypassPrimary.scrollAndClick()
                    composeRule.waitForServiceState(serviceStateStore, AppStatus.Running, Mode.Proxy)
                }
            }
        }
}

@HiltAndroidTest
class HomeConnectedVpnKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            servicePreset = AutomationServicePreset.ConnectedVpn,
        ),
    ) {
    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Test
    fun connectedVpnPresetShowsRemoteVpnCardAndRouteLabels() =
        run {
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Preset reports a running VPN service") {
                    assertEquals(AppStatus.Running to Mode.VPN, serviceStateStore.status.value)
                    expandModes(composeRule)
                }
                step("Remote VPN card and its primary action are shown") {
                    remoteVpnCard.performScrollTo()
                    remoteVpnCard.assertIsDisplayed()
                    remoteVpnPrimary.performScrollTo()
                    remoteVpnPrimary.assertIsDisplayed()
                }
                step("Connection actuator shows route and terminal labels") {
                    actuatorRouteLabel.performScrollTo()
                    actuatorRouteLabel.assertIsDisplayed()
                    actuatorTerminalLabel.assertIsDisplayed()
                }
            }
        }
}

@HiltAndroidTest
class HomeLiveServiceKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            servicePreset = AutomationServicePreset.Live,
        ),
    ) {
    @Test
    fun liveServicePresetStillRendersHomeModeCards() =
        run {
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Expand modes and see the cards") {
                    expandModes(composeRule)
                    localBypassCard.performScrollTo()
                    localBypassCard.assertIsDisplayed()
                    diagnosticCard.performScrollTo()
                    diagnosticCard.assertIsDisplayed()
                }
            }
        }
}
