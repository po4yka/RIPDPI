package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
    fun `local bypass section renders mode listen dns and desync rows`() {
        setConfigScreen(initialModeSection = ConfigModeSection.LocalBypass)

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassMode).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassListenAddress).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync).assertExists()
    }

    @Test
    fun `local bypass desync and dns rows expose actions`() {
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertHasClickAction()
    }

    @Test
    fun `local bypass action rows expose accessibility labels`() {
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
            .assertContentDescription("Desync method")
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertContentDescription("DNS settings")
    }

    @Test
    fun `vpn section renders relay protocol credentials and dns rows`() {
        setConfigScreen(initialModeSection = ConfigModeSection.Vpn)

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
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
    fun `mode section selection survives state restoration`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = configUiState(),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenDnsSettings = {},
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

    private fun setConfigScreen(
        initialModeSection: ConfigModeSection,
        onModeSelected: (Mode) -> Unit = {},
        onEditCurrent: () -> Unit = {},
        onOpenDnsSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = configUiState(),
                    onModeSelected = onModeSelected,
                    onPresetSelected = {},
                    onEditCurrent = onEditCurrent,
                    onOpenDnsSettings = onOpenDnsSettings,
                    initialModeSection = initialModeSection,
                )
            }
        }
    }

    private fun configUiState(): ConfigUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConfigUiState(
            activeMode = draft.mode,
            presets = buildConfigPresets(draft),
            draft = draft,
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
}
