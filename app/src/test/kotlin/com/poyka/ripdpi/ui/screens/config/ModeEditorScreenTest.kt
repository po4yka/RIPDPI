package com.poyka.ripdpi.ui.screens.config

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.poyka.ripdpi.activities.RelayPresetUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
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

    @Test
    fun relayProtocolGridUsesLabeledSectionsAndUniformChipHeights() {
        val presetIds =
            listOf(
                "ru-mobile-tuic",
                "ru-mobile-shadowtls",
                "ru-mobile-naiveproxy",
                "ru-mobile-relay",
            )
        val protocolKinds =
            listOf(
                RelayKindVlessReality,
                RelayKindCloudflareTunnel,
                RelayKindNaiveProxy,
                RelayKindShadowTlsV3,
                RelayKindHysteria2,
                RelayKindMasque,
                RelayKindTuicV5,
                RelayKindChainRelay,
                RelayKindSnowflake,
                RelayKindWebTunnel,
                RelayKindObfs4,
                RelayKindTor,
            )
        setScreen(
            initialDraft = defaultDraft().copy(relayEnabled = true),
            relayPresets =
                persistentListOf(
                    RelayPresetUiState(id = presetIds[0], title = "Russian mobile TUIC", selected = false),
                    RelayPresetUiState(id = presetIds[1], title = "Russian mobile ShadowTLS", selected = false),
                    RelayPresetUiState(id = presetIds[2], title = "Russian mobile NaiveProxy", selected = false),
                    RelayPresetUiState(id = presetIds[3], title = "Russian mobile relay", selected = false),
                ),
        )

        mapOf(
            "recommended" to "Recommended",
            "tls-transports" to "TLS transports",
            "quic-transports" to "QUIC transports",
            "pt-relays" to "PT relays",
        ).forEach { (section, label) ->
            composeRule.onNodeWithTag(RipDpiTestTags.modeEditorRelaySection(section)).performScrollTo().assertExists()
            composeRule.onNodeWithText(label).assertExists()
        }
        composeRule.onNodeWithTag(RipDpiTestTags.modeEditorRelaySection("tor")).performScrollTo().assertExists()
        composeRule.onAllNodesWithText("Tor").assertCountEquals(2)

        val chipTags = (presetIds + protocolKinds).map { kind -> RipDpiTestTags.modeEditorRelayChip(kind) }
        chipTags.forEach { tag ->
            composeRule.onAllNodesWithTag(tag).assertCountEquals(1)
        }
        val chipHeights =
            chipTags.map { tag ->
                composeRule.onNodeWithTag(tag).performScrollTo()
                composeRule.waitForIdle()
                composeRule
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .height
            }
        val expectedHeight = chipHeights.first()
        chipHeights.forEach { height -> assertEquals(expectedHeight, height, 0.5f) }
    }

    private fun setScreen(
        initialDraft: ConfigDraft = defaultDraft(),
        stateful: Boolean = false,
        relayPresets: ImmutableList<RelayPresetUiState> = persistentListOf(),
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
                            relayPresets = relayPresets,
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
