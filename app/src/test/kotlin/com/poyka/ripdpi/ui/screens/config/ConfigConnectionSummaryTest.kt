package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
class ConfigConnectionSummaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `opening vpn settings keeps primary start bound to saved proxy mode`() {
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.Vpn,
            activeMode = Mode.Proxy,
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(Mode.Proxy to true), runtimeToggles)
        }
    }

    @Test
    fun `local settings primary action starts saved vpn when disabled`() {
        val selectedModes = mutableListOf<Mode>()
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
            activeMode = Mode.VPN,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        assertEquals(LocalBypassToggleAction.TurnOn, localBypassToggleAction(localBypassEnabled = false))
        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
            assertEquals(listOf(Mode.VPN to true), runtimeToggles)
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
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
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
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
            assertEquals(listOf(Mode.VPN to false), runtimeToggles)
        }
    }

    @Test
    fun `vpn settings primary action stops saved proxy without selecting vpn`() {
        val selectedModes = mutableListOf<Mode>()
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.Vpn,
            onModeSelected = { selectedModes += it },
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
            activeMode = Mode.Proxy,
            runningMode = Mode.Proxy,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
            assertEquals(listOf(Mode.Proxy to false), runtimeToggles)
        }
    }

    @Test
    fun `primary action stops running vpn after saved mode changes to proxy`() {
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.Vpn,
            activeMode = Mode.Proxy,
            runningMode = Mode.VPN,
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(Mode.VPN to false), runtimeToggles)
        }
    }

    @Test
    fun `primary action stops running proxy after saved mode changes to vpn`() {
        val runtimeToggles = mutableListOf<Pair<Mode, Boolean>>()
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            activeMode = Mode.VPN,
            runningMode = Mode.Proxy,
            onRuntimeModeToggle = { mode, enabled -> runtimeToggles += mode to enabled },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigRuntimeAction)
            .assertTextEquals("Disconnect")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(Mode.Proxy to false), runtimeToggles)
        }
    }

    @Test
    fun `profile choices stay hidden until change and can be hidden again`() {
        setConfigScreen(initialModeSection = ConfigModeSection.LocalBypass)

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigSavedProfile).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.configPreset("recommended")).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddProfile).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.configPreset("recommended")).assertHasClickAction()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddProfile).assertHasClickAction()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChoices).assertDoesNotExist()
    }

    @Test
    fun `saved profile summary ignores unsaved relay editor draft`() {
        val state =
            configUiState().copy(
                activeRelayEnabled = false,
                draft =
                    AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                        mode = Mode.Proxy,
                        relayEnabled = true,
                        relayProfileId = "unsaved-secret-profile",
                    ),
            )
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = state,
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigSavedProfile).assertTextEquals("Recommended profile")
        composeRule.onNodeWithText("Path: direct connection").assertExists()
        composeRule.onNodeWithText("Traffic: apps selected for the local VPN").assertExists()
        composeRule.onNodeWithText("unsaved-secret-profile").assertDoesNotExist()
    }

    private fun setConfigScreen(
        initialModeSection: ConfigModeSection,
        onModeSelected: (Mode) -> Unit = {},
        onRuntimeModeToggle: (Mode, Boolean) -> Unit = { _, _ -> },
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = configUiState(activeMode, runningMode),
                    onModeSelected = onModeSelected,
                    onRuntimeModeToggle = onRuntimeModeToggle,
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    initialModeSection = initialModeSection,
                )
            }
        }
    }

    private fun configUiState(
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ): ConfigUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConfigUiState(
            activeMode = activeMode,
            runningMode = runningMode,
            presets = buildConfigPresets(draft),
            draft = draft,
        )
    }
}
