package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class ConnectionPathSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()

    @Test
    fun `path control follows the active relay and switches the path`() {
        val selected = mutableListOf<String>()
        var directSelections = 0
        var relayActive by mutableStateOf(true)
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState =
                        ConnectionUiState(
                            activeRelayEnabled = relayActive,
                            activeRelayProfileId = "saved",
                            vpnProfiles =
                                persistentListOf(
                                    RelayProfileUiState(
                                        id = "saved",
                                        kind = "trojan",
                                        kindLabel = "Trojan",
                                        jurisdiction = "",
                                        operatorName = "",
                                    ),
                                ),
                        ),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    profileActions =
                        ConnectionProfileActions(
                            select = { selected += it },
                            selectDirect = { directSelections += 1 },
                        ),
                )
            }
        }
        val directTag = RipDpiTestTags.configPath(ConnectionPath.Direct.stableKey)
        val relayTag = RipDpiTestTags.configPath(ConnectionPath.Relay.stableKey)

        composeRule.onNodeWithTag(relayTag).assertIsSelected()
        composeRule.onNodeWithTag(directTag).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, directSelections)
            relayActive = false
        }
        composeRule.onNodeWithTag(directTag).assertIsSelected()

        composeRule.onNodeWithTag(relayTag).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("saved"), selected) }
        composeRule.onNodeWithTag(directTag).assertIsSelected()
    }

    @Test
    fun `relay without a saved profile browses and returns to direct after recommended`() {
        val manualDraft = defaultDraft.copy(proxyPort = "1081")
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState = ConnectionUiState(presets = buildConfigPresets(manualDraft), draft = manualDraft),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.configPath("relay")).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.configPath("relay")).assertIsSelected()

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.configPreset("recommended")).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigPresetResetConfirm).performClick()

        composeRule.onNodeWithTag(RipDpiTestTags.configPath("direct")).assertIsSelected()
    }

    @Test
    fun `strategy row shows auto only for an untouched guided chain`() {
        val default = AppSettingsSerializer.defaultValue.toConfigDraft()
        var uiPersona by mutableStateOf("simple")
        var draft by mutableStateOf(default)
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState =
                        ConnectionUiState(
                            uiPersona = uiPersona,
                            presets = buildConfigPresets(draft),
                            draft = draft,
                        ),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                )
            }
        }

        fun assertStrategy(value: String) =
            composeRule
                .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

        assertStrategy("Auto (recommended)")

        composeRule.runOnIdle { draft = default.withChainDsl("[tcp]\nfake host") }
        assertStrategy("tcp: fake(host)")

        composeRule.runOnIdle {
            draft = default
            uiPersona = "advanced"
        }
        assertStrategy(default.chainSummary)
    }

    @Test
    fun `command line override warning is visible without expanding anything`() {
        val cliDraft = AppSettingsSerializer.defaultValue.toConfigDraft().copy(useCommandLineSettings = true)
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState = ConnectionUiState(presets = buildConfigPresets(cliDraft), draft = cliDraft),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigCliOverrideBanner).assertExists()
    }
}
