package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConfigScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `mode section switcher changes selected summary without changing stored mode`() {
        val selectedModes = mutableListOf<Mode>()

        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.LocalBypass.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertDoesNotExist()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.Vpn.stableKey))
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.Vpn.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
        }
    }

    @Test
    fun `mode section chips expose radio button semantics`() {
        setConfigScreen(initialModeSection = ConfigModeSection.LocalBypass)

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.LocalBypass.stableKey))
            .assertHasRole(Role.RadioButton)
            .assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.Vpn.stableKey))
            .assertHasRole(Role.RadioButton)
    }

    @Test
    fun `mode chips expose radio button semantics`() {
        setConfigScreen(initialModeSection = ConfigModeSection.LocalBypass)

        composeRule
            .onNodeWithTag(RipDpiTestTags.configMode(Mode.VPN.name))
            .assertHasRole(Role.RadioButton)
            .assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configMode(Mode.Proxy.name))
            .assertHasRole(Role.RadioButton)
    }

    @Test
    fun `local bypass section renders mode listen dns and desync rows`() {
        setConfigScreen(initialModeSection = ConfigModeSection.LocalBypass, uiPersona = "advanced")

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSimple).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassMode).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassListenAddress).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync).assertExists()
    }

    @Test
    fun `simple local bypass shows auto strategy retest and precedence note`() {
        setConfigScreen(initialModeSection = ConfigModeSection.LocalBypass)

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSimple).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigEditCurrentButton).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassStrategyAuto).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassPrecedenceNote).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassRetest)
            .assertHasClickAction()
    }

    @Test
    fun `simple local bypass toggle starts proxy when disabled`() {
        val selectedModes = mutableListOf<Mode>()
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
            activeMode = Mode.VPN,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassToggle)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        assertEquals(LocalBypassToggleAction.TurnOn, localBypassToggleAction(localBypassEnabled = false))
        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
            assertEquals(listOf(Mode.Proxy to true), runtimeToggles)
        }
    }

    @Test
    fun `simple local bypass toggle stops proxy when enabled without selecting vpn`() {
        val selectedModes = mutableListOf<Mode>()
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
            activeMode = Mode.Proxy,
            runningMode = Mode.Proxy,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassToggle)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        assertEquals(LocalBypassToggleAction.TurnOff, localBypassToggleAction(localBypassEnabled = true))
        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
            assertEquals(listOf(Mode.Proxy to false), runtimeToggles)
        }
    }

    @Test
    fun `local bypass desync and dns rows expose actions`() {
        var legacyEditorClicks = 0
        var strategyClicks = 0
        var dnsClicks = 0
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            onEditCurrent = { legacyEditorClicks += 1 },
            onOpenLocalBypassEditor = { strategyClicks += 1 },
            onOpenDnsSettings = { dnsClicks += 1 },
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(0, legacyEditorClicks)
            assertEquals(1, strategyClicks)
            assertEquals(1, dnsClicks)
        }
    }

    @Test
    fun `local bypass action rows expose accessibility labels`() {
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
            .assertContentDescription("Bypass strategy")
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertContentDescription("DNS settings")
    }

    @Test
    fun `vpn section renders relay protocol credentials and dns rows`() {
        setConfigScreen(initialModeSection = ConfigModeSection.Vpn, uiPersona = "advanced")

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSimple).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerPaste).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerScan).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).assertExists()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnRelay).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProtocol).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnCredentials).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigDnsSettings).assertExists()
    }

    @Test
    fun `vpn relay and dns rows invoke configuration callbacks`() {
        var relayClicks = 0
        var dnsClicks = 0
        setConfigScreen(
            initialModeSection = ConfigModeSection.Vpn,
            onEditCurrent = { relayClicks += 1 },
            onOpenDnsSettings = { dnsClicks += 1 },
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnRelay)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, relayClicks)
            assertEquals(1, dnsClicks)
        }
    }

    @Test
    fun `vpn simple toggle stops vpn runtime without selecting local bypass`() {
        val selectedModes = mutableListOf<Mode>()
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.Vpn,
            onModeSelected = { selectedModes += it },
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
            activeMode = Mode.VPN,
            runningMode = Mode.VPN,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnToggle)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
            assertEquals(listOf(Mode.VPN to false), runtimeToggles)
        }
    }

    @Test
    fun `mode section selection survives state restoration`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = configUiState(),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    onScanServer = {},
                    initialModeSection = ConfigModeSection.LocalBypass,
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.Vpn.stableKey))
            .performClick()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.Vpn.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
    }

    @Test
    fun `vpn route initial section starts on vpn summary`() {
        setConfigScreen(initialModeSection = ConfigModeSection.Vpn)

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConfigModeSection.Vpn.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertDoesNotExist()
    }

    @Test
    fun `vpn simple add server actions are prominent`() {
        var pasteClicks = 0
        var scanClicks = 0
        val profileShareClicks = mutableListOf<String>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.Vpn,
            onPasteServerLink = { pasteClicks += 1 },
            onScanServer = { scanClicks += 1 },
            onProfileShare = { profileShareClicks += it },
            vpnProfiles =
                persistentListOf(
                    RelayProfileUiState(
                        id = "default",
                        kind = "vless_reality",
                        kindLabel = "VLESS + Reality",
                        jurisdiction = "",
                        operatorName = "",
                    ),
                ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSimple).assertExists()
        composeRule.onNodeWithText("Add profile").assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileRow("default"))
            .assertHasNoClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerPaste)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerScan)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileShare("default"))
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("paste action should use the existing clipboard import callback", 1, pasteClicks)
            assertEquals("scan action should use the existing QR scanner callback", 1, scanClicks)
            assertEquals(listOf("default"), profileShareClicks)
        }
    }

    private fun setConfigScreen(
        initialModeSection: ConfigModeSection,
        onModeSelected: (Mode) -> Unit = {},
        onRuntimeModeToggle: (Mode, Boolean) -> Unit = { _, _ -> },
        onEditCurrent: () -> Unit = {},
        onOpenLocalBypassEditor: () -> Unit = {},
        onOpenDnsSettings: () -> Unit = {},
        onRetestStrategies: () -> Unit = {},
        onPasteServerLink: () -> Unit = {},
        onScanServer: () -> Unit = {},
        onProfileShare: (String) -> Unit = {},
        uiPersona: String = "simple",
        vpnProfiles: ImmutableList<RelayProfileUiState> = persistentListOf(),
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState =
                        configUiState(
                            uiPersona = uiPersona,
                            vpnProfiles = vpnProfiles,
                            activeMode = activeMode,
                            runningMode = runningMode,
                        ),
                    onModeSelected = onModeSelected,
                    onRuntimeModeToggle = onRuntimeModeToggle,
                    onPresetSelected = {},
                    onEditCurrent = onEditCurrent,
                    onOpenConfigEditor = { path ->
                        when (path) {
                            ConfigEditorTarget.Bypass -> onOpenLocalBypassEditor()
                            ConfigEditorTarget.Resolver -> onOpenDnsSettings()
                        }
                    },
                    onRetestStrategies = onRetestStrategies,
                    onPasteServerLink = onPasteServerLink,
                    onScanServer = onScanServer,
                    onProfileShare = onProfileShare,
                    initialModeSection = initialModeSection,
                )
            }
        }
    }

    private fun configUiState(
        uiPersona: String = "simple",
        vpnProfiles: ImmutableList<RelayProfileUiState> = persistentListOf(),
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ): ConfigUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConfigUiState(
            activeMode = activeMode,
            runningMode = runningMode,
            uiPersona = uiPersona,
            presets = buildConfigPresets(draft),
            draft = draft,
            vpnProfiles = vpnProfiles,
        )
    }

    private fun SemanticsNodeInteraction.assertContentDescription(expected: String) {
        assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(expected),
            ),
        )
    }

    private fun SemanticsNodeInteraction.assertHasRole(expected: Role): SemanticsNodeInteraction =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, expected))
}
