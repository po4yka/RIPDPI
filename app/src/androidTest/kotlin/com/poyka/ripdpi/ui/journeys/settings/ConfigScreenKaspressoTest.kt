package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
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

    private val vpnChip = RipDpiTestTags.configMode(Mode.VPN.name)
    private val proxyChip = RipDpiTestTags.configMode(Mode.Proxy.name)

    @Test
    fun sectionsAndModeChipsSwitchTheVisibleConfiguration() =
        run {
            step("Config opens on the local bypass section with both mode chips") {
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
                composeRule.onNodeWithTag(vpnChip).assertIsDisplayed()
                composeRule.onNodeWithTag(proxyChip).assertIsDisplayed()
                composeRule.scrollToTag(RipDpiTestTags.ConfigLocalBypassSummary)
            }
            step("The server section shows the VPN summary and relay row") {
                composeRule.clickTag(RipDpiTestTags.configModeSection("vpn"))
                composeRule.awaitTag(RipDpiTestTags.ConfigVpnSummary)
                composeRule.awaitDisplayed(RipDpiTestTags.ConfigVpnRelay)
            }
            step("Selecting the proxy chip selects it, deselects VPN and persists proxy mode") {
                composeRule.clickTag(RipDpiTestTags.configModeSection("local_bypass"))
                composeRule.clickTag(proxyChip)
                composeRule.awaitSelected(proxyChip)
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    it.ripdpiMode ==
                        Mode.Proxy.preferenceValue
                }
                composeRule.scrollToTag(RipDpiTestTags.ConfigLocalBypassListenAddress)
                composeRule.scrollToTag(RipDpiTestTags.ConfigLocalBypassDesync)
            }
            step("Selecting the VPN chip switches back") {
                composeRule.clickTag(vpnChip)
                composeRule.awaitSelected(vpnChip)
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    it.ripdpiMode ==
                        Mode.VPN.preferenceValue
                }
            }
        }

    @Test
    fun dnsRowOpensDnsSettingsAndBackReturnsToConfig() =
        run {
            step("Open DNS settings from the local bypass section") {
                composeRule.clickTag(RipDpiTestTags.ConfigDnsSettings)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.DnsSettings))
            }
            step("Back returns to the config screen") {
                Espresso.pressBack()
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
            }
        }

    @Test
    fun presetSelectionAppliesProxyPresetAndCustomOpensTheModeEditor() =
        run {
            step("Open the profile choices and select the proxy preset") {
                composeRule.clickTag(RipDpiTestTags.ConfigProfileChange)
                composeRule.awaitTag(RipDpiTestTags.ConfigProfileChoices)
                composeRule.clickTag(RipDpiTestTags.configPreset("proxy"))
            }
            step("The proxy preset persists proxy mode and stays visible") {
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    it.ripdpiMode ==
                        Mode.Proxy.preferenceValue
                }
                composeRule.onNodeWithTag(RipDpiTestTags.configPreset("proxy")).assertIsDisplayed()
            }
            step("The custom preset opens the mode editor with its fields") {
                composeRule.clickTag(RipDpiTestTags.configPreset("custom"))
                composeRule.awaitTag(RipDpiTestTags.screen(Route.ModeEditor))
                composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorProxyIp).assertIsDisplayed()
                composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorProxyPort).assertIsDisplayed()
                composeRule.scrollToTag(RipDpiTestTags.ModeEditorSave)
            }
        }

    @Test
    fun runtimeActionStartsAndStopsTheSavedProxyMode() =
        run {
            val action = RipDpiTestTags.ConfigRuntimeAction
            step("Select proxy mode; the runtime action offers Connect") {
                composeRule.clickTag(proxyChip)
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    it.ripdpiMode ==
                        Mode.Proxy.preferenceValue
                }
                composeRule.scrollToTag(action)
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(hasTestTag(action) and hasText("Connect")).fetchSemanticsNodes().isNotEmpty()
                }
            }
            step("Connecting flips the action to Disconnect") {
                composeRule.clickTag(action)
                composeRule.waitUntil(30_000) {
                    composeRule
                        .onAllNodes(
                            hasTestTag(action) and hasText("Disconnect"),
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }
            step("Disconnecting returns the action to Connect") {
                composeRule.clickTag(action)
                composeRule.waitUntil(30_000) {
                    composeRule.onAllNodes(hasTestTag(action) and hasText("Connect")).fetchSemanticsNodes().isNotEmpty()
                }
            }
        }

    @Test
    fun localBypassShowsAutoStrategyAndRetestOpensDiagnostics() =
        run {
            step("Select proxy mode and open the connection details") {
                composeRule.clickTag(proxyChip)
                composeRule.awaitSelected(proxyChip)
                composeRule.clickTag(RipDpiTestTags.ConfigConnectionDetails)
            }
            step("The details show the automatic strategy and the precedence note") {
                composeRule.awaitDisplayed(RipDpiTestTags.ConfigLocalBypassStrategyAuto)
                composeRule.awaitDisplayed(RipDpiTestTags.ConfigLocalBypassPrecedenceNote)
            }
            step("Retest strategies opens diagnostics") {
                composeRule.clickTag(RipDpiTestTags.ConfigLocalBypassRetest)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Diagnostics()))
            }
        }
}
