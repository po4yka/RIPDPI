package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class ConnectionSummaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `direct section offers no connect or disconnect control while running`() {
        setConfigScreen(
            initialPath = ConnectionPath.Direct,
            activeMode = Mode.Proxy,
            runningMode = Mode.Proxy,
        )

        assertNoRuntimeControl()
    }

    @Test
    fun `relay section offers no connect or disconnect control while stopped`() {
        setConfigScreen(initialPath = ConnectionPath.Relay, activeMode = Mode.VPN)

        assertNoRuntimeControl()
    }

    private fun assertNoRuntimeControl() {
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
        composeRule.onNodeWithText("Disconnect").assertDoesNotExist()
        composeRule.onNodeWithText("Turn on").assertDoesNotExist()
        composeRule.onNodeWithText("Turn off").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).assertExists()
    }

    @Test
    fun `profile choices stay hidden until change`() {
        setConfigScreen(initialPath = ConnectionPath.Direct)

        composeRule.onNodeWithTag(RipDpiTestTags.connectionPreset("recommended")).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddProfile).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.connectionPreset("recommended")).assertHasClickAction()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddProfile).assertHasClickAction()
    }

    @Test
    fun `recommended preset asks before replacing manual settings`() {
        var applied = 0
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft().copy(proxyPort = "1081")
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState = ConnectionUiState(presets = buildConfigPresets(draft), draft = draft),
                    onModeSelected = {},
                    onPresetSelected = { applied += 1 },
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.connectionPreset("recommended")).performClick()
        composeRule.runOnIdle { assertEquals(0, applied) }
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionPresetResetConfirm).performClick()
        composeRule.runOnIdle { assertEquals(1, applied) }
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
                ConnectionScreen(
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

        composeRule.onNodeWithText("Recommended profile", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Proxy only · Direct", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Whole device · Direct", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("unsaved-secret-profile").assertDoesNotExist()
    }

    private fun setConfigScreen(
        initialPath: ConnectionPath,
        onModeSelected: (Mode) -> Unit = {},
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState = configUiState(activeMode, runningMode),
                    onModeSelected = onModeSelected,
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    initialPath = initialPath,
                )
            }
        }
    }

    private fun configUiState(
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ): ConnectionUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConnectionUiState(
            activeMode = activeMode,
            runningMode = runningMode,
            presets = buildConfigPresets(draft),
            draft = draft,
        )
    }
}
