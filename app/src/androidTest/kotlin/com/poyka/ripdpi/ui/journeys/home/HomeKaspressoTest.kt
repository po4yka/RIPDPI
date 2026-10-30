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
    fun localBypassToggleStartsAndStopsProxyService() =
        run {
            with(HomePage(composeRule)) {
                step("Service starts halted") {
                    assertEquals(AppStatus.Halted, serviceStateStore.status.value.first)
                    expandModes()
                }
                step("Enabling local bypass dispatches a running proxy service") {
                    click(localBypassPrimary)
                    waitForServiceState(serviceStateStore, AppStatus.Running, Mode.Proxy)
                }
                step("Disabling local bypass returns the service to halted") {
                    click(localBypassPrimary)
                    waitForHalted(serviceStateStore)
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
            with(HomePage(composeRule)) {
                step("Preset reports a running proxy and Home shows the route labels") {
                    assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
                    awaitDisplayed(actuatorRouteLabel)
                    node(actuatorTerminalLabel).assertIsDisplayed()
                }
                step("Local bypass card is shown") {
                    expandModes()
                    awaitDisplayed(localBypassCard)
                }
                step("Stopping returns to idle, enabling again runs the proxy") {
                    click(localBypassPrimary)
                    waitForHalted(serviceStateStore)
                    click(localBypassPrimary)
                    waitForServiceState(serviceStateStore, AppStatus.Running, Mode.Proxy)
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
                step("Remote VPN card and its primary action are shown") {
                    awaitDisplayed(remoteVpnCard)
                    awaitDisplayed(remoteVpnPrimary)
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
