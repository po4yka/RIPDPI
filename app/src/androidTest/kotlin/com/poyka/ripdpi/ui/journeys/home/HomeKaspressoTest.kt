package com.poyka.ripdpi.ui.journeys.home

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationServicePreset
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
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
            with(HomePage(composeRule)) {
                step("Expand the modes disclosure") { expandModes() }
                step("Local DPI bypass, remote VPN and diagnostic cards are shown") {
                    listOf(localBypassCard, remoteVpnCard, diagnosticCard).forEach {
                        awaitDisplayed(it)
                    }
                }
                step("No error snackbar shows during normal operation") {
                    assertEquals(0, count(errorSnackbar))
                }
            }
        }

    @Test
    fun actuatorStartsAndStopsConfiguredModeService() =
        run {
            with(HomePage(composeRule)) {
                step("Service starts halted") {
                    assertEquals(AppStatus.Halted, serviceStateStore.status.value.first)
                    awaitDisplayed(actuatorButton)
                }
                step("Pressing the actuator dispatches the configured Whole device mode") {
                    click(actuatorButton)
                    waitForServiceState(serviceStateStore, AppStatus.Running, Mode.VPN)
                }
                step("Pressing the actuator again and confirming the dialog returns the service to halted") {
                    click(actuatorButton)
                    click(disconnectConfirm)
                    waitForHalted(serviceStateStore)
                }
            }
        }

    @Test
    fun modeCardsOfferNoConnectAction() =
        run {
            with(HomePage(composeRule)) {
                step("Service starts halted and the modes are expanded") {
                    assertEquals(AppStatus.Halted, serviceStateStore.status.value.first)
                    expandModes()
                }
                step("Mode cards expose configure only; the actuator is the single connect control") {
                    awaitDisplayed(localBypassCard)
                    assertEquals(0, count(localBypassPrimary))
                    assertEquals(0, count(remoteVpnPrimary))
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
    fun connectedProxyPresetShowsProxyOnlyCardActive() =
        run {
            with(HomePage(composeRule)) {
                step("Preset reports a running proxy and Home shows the route labels") {
                    assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
                    awaitDisplayed(actuatorRouteLabel)
                    node(actuatorTerminalLabel).assertIsDisplayed()
                }
                step("Proxy only card is shown") {
                    expandModes()
                    awaitDisplayed(localBypassCard)
                }
                step("The card carries no connect action") {
                    assertEquals(0, count(localBypassPrimary))
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
            with(HomePage(composeRule)) {
                step("Preset reports a running VPN service") {
                    assertEquals(AppStatus.Running to Mode.VPN, serviceStateStore.status.value)
                    expandModes()
                }
                step("Whole device card is shown without a connect action") {
                    awaitDisplayed(remoteVpnCard)
                    assertEquals(0, count(remoteVpnPrimary))
                }
                step("Connection actuator shows route and terminal labels") {
                    awaitDisplayed(actuatorRouteLabel)
                    node(actuatorTerminalLabel).assertIsDisplayed()
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
            with(HomePage(composeRule)) {
                step("Expand modes and see the cards") {
                    expandModes()
                    awaitDisplayed(localBypassCard)
                    awaitDisplayed(diagnosticCard)
                }
            }
        }
}
