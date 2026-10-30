package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.espresso.Espresso
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.journeys.waitForTag
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class ConfigScreenKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun sectionsAndModeChipsSwitchTheVisibleConfiguration() =
        run {
            step("Config opens on the local bypass section with both mode chips") {
                with(ConfigPage(composeRule)) {
                    awaitScreen()
                    node(vpnChip).assertIsDisplayed()
                    node(proxyChip).assertIsDisplayed()
                    require(localBypassSummary)
                }
            }
            step("The server section shows the VPN summary and relay row") {
                with(ConfigPage(composeRule)) {
                    click(modeSection("vpn"))
                    waitFor(vpnSummary)
                    awaitDisplayed(vpnRelay)
                }
            }
            step("Selecting the proxy chip selects it, deselects VPN and persists proxy mode") {
                with(ConfigPage(composeRule)) {
                    click(modeSection("local_bypass"))
                    click(proxyChip)
                    awaitSelected(proxyChip)
                    composeRule.waitForSettings(appSettingsRepository) { it.ripdpiMode == Mode.Proxy.preferenceValue }
                    require(localBypassListenAddress)
                    require(localBypassDesync)
                }
            }
            step("Selecting the VPN chip switches back") {
                with(ConfigPage(composeRule)) {
                    click(vpnChip)
                    awaitSelected(vpnChip)
                    composeRule.waitForSettings(appSettingsRepository) { it.ripdpiMode == Mode.VPN.preferenceValue }
                }
            }
        }

    @Test
    fun dnsRowOpensDnsSettingsAndBackReturnsToConfig() =
        run {
            step("Open DNS settings from the local bypass section") {
                with(ConfigPage(composeRule)) { click(dnsSettings) }
                DnsSettingsPage(composeRule).awaitScreen()
            }
            step("Back returns to the config screen") {
                Espresso.pressBack()
                ConfigPage(composeRule).awaitScreen()
            }
        }

    @Test
    fun presetSelectionAppliesProxyPresetAndCustomOpensTheModeEditor() =
        run {
            step("Open the profile choices and select the proxy preset") {
                with(ConfigPage(composeRule)) {
                    click(profileChange)
                    waitFor(profileChoices)
                    click(preset("proxy"))
                }
            }
            step("The proxy preset persists proxy mode and stays visible") {
                with(ConfigPage(composeRule)) {
                    composeRule.waitForSettings(appSettingsRepository) { it.ripdpiMode == Mode.Proxy.preferenceValue }
                    node(preset("proxy")).assertIsDisplayed()
                }
            }
            step("The custom preset opens the mode editor with its fields") {
                with(ConfigPage(composeRule)) { click(preset("custom")) }
                with(ModeEditorPage(composeRule)) {
                    awaitScreen()
                    node(proxyIp).assertIsDisplayed()
                    node(proxyPort).assertIsDisplayed()
                    require(save)
                }
            }
        }

    @Test
    fun runtimeActionStartsAndStopsTheSavedProxyMode() =
        run {
            step("Select proxy mode; the runtime action offers Connect") {
                with(ConfigPage(composeRule)) {
                    click(proxyChip)
                    composeRule.waitForSettings(appSettingsRepository) { it.ripdpiMode == Mode.Proxy.preferenceValue }
                    require(runtimeAction)
                    awaitRuntimeAction("Connect")
                }
            }
            step("Connecting flips the action to Disconnect") {
                with(ConfigPage(composeRule)) {
                    click(runtimeAction)
                    awaitRuntimeAction("Disconnect", 30_000)
                }
            }
            step("Disconnecting returns the action to Connect") {
                with(ConfigPage(composeRule)) {
                    click(runtimeAction)
                    awaitRuntimeAction("Connect", 30_000)
                }
            }
        }

    @Test
    fun localBypassShowsAutoStrategyAndRetestOpensDiagnostics() =
        run {
            step("Select proxy mode and open the connection details") {
                with(ConfigPage(composeRule)) {
                    click(proxyChip)
                    awaitSelected(proxyChip)
                    click(connectionDetails)
                }
            }
            step("The details show the automatic strategy and the precedence note") {
                with(ConfigPage(composeRule)) {
                    awaitDisplayed(localBypassStrategyAuto)
                    awaitDisplayed(localBypassPrecedenceNote)
                }
            }
            step("Retest strategies opens diagnostics") {
                with(ConfigPage(composeRule)) { click(localBypassRetest) }
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Diagnostics()))
            }
        }
}
