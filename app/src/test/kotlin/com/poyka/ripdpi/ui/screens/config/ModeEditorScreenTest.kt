package com.poyka.ripdpi.ui.screens.config

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
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
class ModeEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun advancedFieldsAreCollapsedByDefault() {
        setScreen()

        composeRule.onNodeWithText("Advanced").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorProxyIp).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorProxyPort).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorMaxConnections, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorBufferSize, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorDefaultTtl, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorCommandLineArgs, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun advancedFieldsRenderAfterExpandingAdvancedSection() {
        setScreen()

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorMaxConnections, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorBufferSize, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorDefaultTtl, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorCommandLineArgs, useUnmergedTree = true).assertExists()
    }

    private fun setScreen() {
        val draft =
            AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                mode = Mode.VPN,
                proxyIp = "127.0.0.1",
                proxyPort = "1080",
                maxConnections = "512",
                bufferSize = "16384",
                chainDsl = "[tcp]\nsplit midsld",
                defaultTtl = "8",
                commandLineArgs = "--fake --ttl 8",
            )
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorScreen(
                    uiState =
                        ConfigUiState(
                            activeMode = draft.mode,
                            presets = buildConfigPresets(draft),
                            editingPreset = ConfigPreset(id = "custom", kind = ConfigPresetKind.Custom, draft = draft),
                            draft = draft,
                        ),
                    snackbarHostState = SnackbarHostState(),
                    actions = NoOpModeEditorActions,
                )
            }
        }
    }
}
