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
    fun `direct section offers no connect or disconnect control while running`() {
        setConfigScreen(
            initialModeSection = ConfigModeSection.LocalBypass,
            activeMode = Mode.Proxy,
            runningMode = Mode.Proxy,
        )

        assertNoRuntimeControl()
    }

    @Test
    fun `relay section offers no connect or disconnect control while stopped`() {
        setConfigScreen(initialModeSection = ConfigModeSection.Vpn, activeMode = Mode.VPN)

        assertNoRuntimeControl()
    }

    private fun assertNoRuntimeControl() {
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
        composeRule.onNodeWithText("Disconnect").assertDoesNotExist()
        composeRule.onNodeWithText("Turn on").assertDoesNotExist()
        composeRule.onNodeWithText("Turn off").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).assertExists()
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
        composeRule.onNodeWithText("Path: Direct").assertExists()
        composeRule.onNodeWithText("Scope: Whole device").assertExists()
        composeRule.onNodeWithText("unsaved-secret-profile").assertDoesNotExist()
    }

    private fun setConfigScreen(
        initialModeSection: ConfigModeSection,
        onModeSelected: (Mode) -> Unit = {},
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConfigScreen(
                    uiState = configUiState(activeMode, runningMode),
                    onModeSelected = onModeSelected,
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
