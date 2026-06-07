package com.poyka.ripdpi.ui.screens.config

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.poyka.ripdpi.activities.ConfigDraft
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

    @Test
    fun chainTextAndSummaryUseSameBackingValue() {
        setScreen(stateful = true)

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bypass strategy: tcp: split(midsld)").assertExists()

        val chainField = composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true)
        chainField.performTextClearance()
        chainField.performTextInput("[tcp]\nfake host")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bypass strategy: tcp: fake(host)").assertExists()
    }

    @Test
    fun commandLineOverrideMarksChainSourceOverridden() {
        setScreen(initialDraft = defaultDraft().copy(useCommandLineSettings = true))

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("CLI overrides chain").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true).assertIsNotEnabled()
    }

    private fun setScreen(
        initialDraft: ConfigDraft = defaultDraft(),
        stateful: Boolean = false,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                var draft by remember { mutableStateOf(initialDraft) }
                val screenDraft = if (stateful) draft else initialDraft
                ModeEditorScreen(
                    uiState =
                        ConfigUiState(
                            activeMode = screenDraft.mode,
                            presets = buildConfigPresets(screenDraft),
                            editingPreset =
                                ConfigPreset(
                                    id = "custom",
                                    kind = ConfigPresetKind.Custom,
                                    draft = screenDraft,
                                ),
                            draft = screenDraft,
                        ),
                    snackbarHostState = SnackbarHostState(),
                    actions =
                        if (stateful) {
                            NoOpModeEditorActions.copy(onChainDslChanged = { draft = draft.withChainDsl(it) })
                        } else {
                            NoOpModeEditorActions
                        },
                )
            }
        }
    }

    private fun defaultDraft(): ConfigDraft =
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
}
