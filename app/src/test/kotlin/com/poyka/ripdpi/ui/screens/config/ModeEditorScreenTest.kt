package com.poyka.ripdpi.ui.screens.config

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeStreamOne
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
    fun loadingHydrationHidesEditableContentAndActions() {
        setScreen(isEditorLoading = true)

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorLoading).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorProxyIp).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorCancel).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorSave).assertDoesNotExist()
    }

    @Test
    fun savingDisablesAllExitActionsAndRepeatedSave() {
        setScreen(isEditorSaving = true)

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorSave).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorCancel).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Navigate back").assertIsNotEnabled()
    }

    @Test
    fun recoveryPersistenceFailureStaysVisibleWhileAutomaticRetryRuns() {
        setScreen(hasEditorRecoveryPersistenceError = true)

        composeRule.onNodeWithText("Draft not saved").assertExists()
        composeRule
            .onNodeWithText(
                "RIPDPI is retrying local draft storage. Keep this screen open until the warning clears.",
            ).assertExists()
    }

    @Test
    fun explicitCancelUsesDedicatedDiscardAction() {
        var backCalls = 0
        var cancelCalls = 0
        setScreen(
            actions =
                NoOpModeEditorActions.copy(
                    onBack = { backCalls += 1 },
                    onCancel = { cancelCalls += 1 },
                ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorCancel).performClick()

        assertEquals(0, backCalls)
        assertEquals(1, cancelCalls)
    }

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
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainBlockList, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorCommandLineArgs, useUnmergedTree = true).assertExists()
    }

    @Test
    fun rawToggleRoundTripsChainTextWithoutRewritingBlocks() {
        var observedDraft = defaultDraft()
        setScreen(
            stateful = true,
            onStatefulDraftChanged = { observedDraft = it },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bypass strategy: tcp: split(midsld)").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainVisual).assertIsEnabled()

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainRawToggle).performScrollTo().performClick()
        composeRule.waitForIdle()
        val chainField = composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true)
        chainField.assertTextContains("[tcp]\nsplit midsld")
        chainField.performTextClearance()
        chainField.performTextInput("[tcp]\nfake host")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bypass strategy: tcp: fake(host)").assertExists()
        composeRule.runOnIdle {
            assertEquals("fake", observedDraft.desyncMethod)
            assertEquals("tcp: fake(host)", observedDraft.chainSummary)
        }
    }

    @Test
    fun blockEditsRewriteRawChainTextThroughSingleSource() {
        var observedDraft = defaultDraft()
        setScreen(
            stateful = true,
            onStatefulDraftChanged = { observedDraft = it },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(chainAddTag("fake")).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(chainMoveUpTag("tcp", 1)).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("[tcp]\nfake host\nsplit midsld", observedDraft.chainDsl)
            assertEquals("tcp: fake(host) -> split(midsld)", observedDraft.chainSummary)
        }
    }

    @Test
    fun invalidRawChainIsFlaggedAndKeepsRawEditorVisible() {
        setScreen(initialDraft = defaultDraft().copy(chainDsl = "[tcp]\nsplit host\ntlsrec sniext+1"))

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(RipDpiTestTags.ModeEditorChainValidation)
            .assertTextContains("Invalid chain", substring = true)
        composeRule
            .onNodeWithText("Open the raw DSL view to repair syntax or ordering before block editing resumes.")
            .assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true).assertExists()
    }

    @Test
    fun commandLineOverrideMarksChainSourceOverridden() {
        setScreen(initialDraft = defaultDraft().copy(useCommandLineSettings = true))

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorAdvanced).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("CLI overrides bypass strategy").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainVisual).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainRawToggle, useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorChainDsl, useUnmergedTree = true).assertDoesNotExist()
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

    @Test
    fun relayFinalmaskOptionsRenderInlineHelp() {
        setScreen(
            initialDraft =
                defaultDraft().copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayVlessTransport = RelayVlessTransportXhttp,
                ),
        )

        listOf(
            "Do not wrap the xHTTP TCP stream.",
            "Send configured header/trailer bytes, plus optional random bytes, once before the real payload.",
            "Encode each payload byte as four seeded entropy-hint bytes; the peer decodes them back to " +
                "the original stream.",
            "Split each payload write into the configured number of TCP frames when the size fits the byte range.",
            "Send one random byte prelude from the configured range before the real payload.",
        ).forEach { helpText ->
            composeRule.onNodeWithText(helpText).performScrollTo().assertExists()
        }
    }

    @Test
    fun relayXhttpModeIsUserSelectable() {
        var observedDraft: ConfigDraft? = null

        setScreen(
            initialDraft =
                defaultDraft().copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayVlessTransport = RelayVlessTransportXhttp,
                ),
            stateful = true,
            onStatefulDraftChanged = { observedDraft = it },
        )

        composeRule.onNodeWithText("xHTTP mode").performScrollTo().assertExists()
        composeRule.onNodeWithText("stream-one").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(RelayXhttpModeStreamOne, observedDraft?.relayXhttpMode)
    }

    private fun setScreen(
        initialDraft: ConfigDraft = defaultDraft(),
        stateful: Boolean = false,
        isEditorLoading: Boolean = false,
        isEditorSaving: Boolean = false,
        hasEditorRecoveryPersistenceError: Boolean = false,
        relayPresets: ImmutableList<RelayPresetUiState> = persistentListOf(),
        onStatefulDraftChanged: (ConfigDraft) -> Unit = {},
        actions: ModeEditorActions = NoOpModeEditorActions,
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
                            isEditorLoading = isEditorLoading,
                            isEditorSaving = isEditorSaving,
                            hasEditorRecoveryPersistenceError = hasEditorRecoveryPersistenceError,
                        ),
                    snackbarHostState = SnackbarHostState(),
                    actions =
                        if (stateful) {
                            actions.copy(
                                onChainDslChanged = {
                                    val updatedDraft = draft.withChainDsl(it)
                                    draft = updatedDraft
                                    onStatefulDraftChanged(updatedDraft)
                                },
                                onRelayXhttpModeChanged = {
                                    val updatedDraft = draft.copy(relayXhttpMode = it)
                                    draft = updatedDraft
                                    onStatefulDraftChanged(updatedDraft)
                                },
                            )
                        } else {
                            actions
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

    private fun chainAddTag(stepId: String): String = RipDpiTestTags.ModeEditorChainAddPrefix + stepId

    private fun chainMoveUpTag(
        section: String,
        index: Int,
    ): String = RipDpiTestTags.ModeEditorChainMoveUpPrefix + section + "-" + index
}
