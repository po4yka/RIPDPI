package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.navigation.Route
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
class ConnectionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `mode section switcher changes selected summary without changing stored mode`() {
        val selectedModes = mutableListOf<Mode>()

        setConfigScreen(
            initialPath = ConnectionPath.Direct,
            onModeSelected = { selectedModes += it },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Direct.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelaySummary).assertDoesNotExist()

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Relay.stableKey))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Relay.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelaySummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectSummary).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
        }
    }

    @Test
    fun `mode section tabs expose tab semantics`() {
        setConfigScreen(initialPath = ConnectionPath.Direct)

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Direct.stableKey))
            .assertHasRole(Role.Tab)
            .assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Relay.stableKey))
            .assertHasRole(Role.Tab)
            .assertIsNotSelected()
    }

    @Test
    fun `connection card exposes visible group labels without duplicate descriptions`() {
        setConfigScreen(initialPath = ConnectionPath.Direct)

        // One label per control group: no card heading and no extra label above the section tabs.
        composeRule.onNodeWithText("Connection settings").assertDoesNotExist()
        composeRule.onNodeWithText("Configure").assertDoesNotExist()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionSectionNavigation)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithText("Choose traffic mode").assertDoesNotExist()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionScopeSelection)
            .assertContentDescription("Choose traffic mode")
    }

    @Test
    fun `mode chips expose radio button semantics`() {
        setConfigScreen(initialPath = ConnectionPath.Direct)

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionScope(Mode.VPN.name))
            .assertHasRole(Role.RadioButton)
            .assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionScope(Mode.Proxy.name))
            .assertHasRole(Role.RadioButton)
            .assertIsNotSelected()
    }

    @Test
    fun `traffic endpoint choice invokes mode callback without changing section`() {
        val selectedModes = mutableListOf<Mode>()
        setConfigScreen(
            initialPath = ConnectionPath.Direct,
            onModeSelected = { selectedModes += it },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionScope(Mode.Proxy.name))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Direct.stableKey))
            .assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(Mode.Proxy), selectedModes)
        }
    }

    @Test
    fun `traffic endpoint choice can return from proxy to vpn without changing section`() {
        val selectedModes = mutableListOf<Mode>()
        setConfigScreen(
            initialPath = ConnectionPath.Direct,
            onModeSelected = { selectedModes += it },
            activeMode = Mode.Proxy,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionScope(Mode.VPN.name))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Direct.stableKey))
            .assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(Mode.VPN), selectedModes)
        }
    }

    @Test
    fun `local bypass section renders listen dns and desync rows`() {
        setConfigScreen(
            initialPath = ConnectionPath.Direct,
            uiPersona = "advanced",
            activeMode = Mode.Proxy,
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        // The mode chips are the one place that shows the mode.
        composeRule.onAllNodesWithText("Proxy only").assertCountEquals(1)
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectListenAddress).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectDesync).assertExists()
    }

    @Test
    fun `vpn mode hides the internal proxy listen address`() {
        setConfigScreen(initialPath = ConnectionPath.Direct, activeMode = Mode.VPN)

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectListenAddress).assertDoesNotExist()
    }

    @Test
    fun `direct section offers the strategy test without an edit shortcut`() {
        setConfigScreen(initialPath = ConnectionPath.Direct)

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionEditCurrentButton).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectRetest).assertHasClickAction()
    }

    @Test
    fun `local bypass desync and dns rows expose actions`() {
        var legacyEditorClicks = 0
        var strategyClicks = 0
        var dnsClicks = 0
        setConfigScreen(
            initialPath = ConnectionPath.Direct,
            onEditCurrent = { legacyEditorClicks += 1 },
            onOpenLocalBypassEditor = { strategyClicks += 1 },
            onOpenDnsSettings = { dnsClicks += 1 },
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionDirectDesync)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings)
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
            initialPath = ConnectionPath.Direct,
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionDirectDesync)
            .assertContentDescription("Bypass strategy")
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings)
            .assertContentDescription("DNS settings")
    }

    @Test
    fun `dns action exposes its current value to accessibility services`() {
        setConfigScreen(initialPath = ConnectionPath.Relay, uiPersona = "advanced")

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Encrypted DNS · AdGuard DNS (DoH)",
                ),
            )
    }

    @Test
    fun `vpn section with the relay off shows one relay setup row and dns`() {
        setConfigScreen(initialPath = ConnectionPath.Relay, uiPersona = "advanced")

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelaySummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionRelayRelay)
            .assert(hasContentDescription("Relay off", substring = true))
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayProtocol).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayCredentials).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddServerPaste).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddServerScan).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayProfileList).assertExists()
    }

    @Test
    fun `vpn add profile menu opens every dedicated editor`() {
        val opened = mutableListOf<Route>()
        setConfigScreen(
            initialPath = ConnectionPath.Relay,
            profileActions = ConnectionProfileActions(create = { opened += it }),
        )

        val editors =
            listOf(
                Route.AnyTlsProfile,
                Route.MieruProfile,
                Route.SshProfile,
                Route.AmneziaWgProfile(),
                Route.SubscriptionAdd,
            )
        editors.forEach { route ->
            composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
            composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddProfile).performScrollTo().performClick()
            composeRule.onNodeWithTag(RipDpiTestTags.connectionRelayCreateProfile(route)).performClick()
        }

        composeRule.runOnIdle { assertEquals(editors, opened) }
    }

    @Test
    fun `relay off row opens profile choices and dns row invokes its callback`() {
        var dnsClicks = 0
        setConfigScreen(
            initialPath = ConnectionPath.Relay,
            onOpenDnsSettings = { dnsClicks += 1 },
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionDnsSettings)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionRelayRelay)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChoices).assertExists()

        composeRule.runOnIdle {
            assertEquals(1, dnsClicks)
        }
    }

    @Test
    fun `mode section selection survives state restoration`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState = configUiState(),
                    onModeSelected = {},
                    onPresetSelected = {},
                    onEditCurrent = {},
                    onOpenConfigEditor = {},
                    onRetestStrategies = {},
                    onPasteServerLink = {},
                    initialPath = ConnectionPath.Direct,
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Relay.stableKey))
            .performScrollTo()
            .performClick()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Relay.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelaySummary).assertExists()
    }

    @Test
    fun `vpn route initial section starts on vpn summary`() {
        setConfigScreen(initialPath = ConnectionPath.Relay)

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionPath(ConnectionPath.Relay.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelaySummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionDirectSummary).assertDoesNotExist()
    }

    @Test
    fun `vpn simple add server actions are prominent`() {
        var pasteClicks = 0
        var scanClicks = 0
        val profileShareClicks = mutableListOf<String>()
        setConfigScreen(
            initialPath = ConnectionPath.Relay,
            onPasteServerLink = { pasteClicks += 1 },
            onScanServer = { scanClicks += 1 },
            profileActions = ConnectionProfileActions(share = { profileShareClicks += it }),
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

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithText("Add profile").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.connectionRelayProfile("default")).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionRelayProfileRow("default"))
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionRelayAddServerPaste)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionRelayAddServerScan)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionRelayProfileShare("default"))
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("paste action should use the existing clipboard import callback", 1, pasteClicks)
            assertEquals("scan action should use the existing QR scanner callback", 1, scanClicks)
            assertEquals(listOf("default"), profileShareClicks)
        }
    }

    @Test
    fun `unknown relay can be selected without offering unsupported editing`() {
        val selected = mutableListOf<String>()
        setConfigScreen(
            initialPath = ConnectionPath.Relay,
            vpnProfiles = persistentListOf(RelayProfileUiState("unknown-imported", "unknown", "Unknown", "", "")),
            profileActions = ConnectionProfileActions(select = { selected += it }),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionProfileChange).performScrollTo().performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionRelayProfileRow("unknown-imported"))
            .assertHasNoClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionRelayProfileSelect("unknown-imported"))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf("unknown-imported"), selected) }
    }

    @Test
    fun `profile selection follows saved settings rather than an unsaved editor draft`() {
        val state =
            configUiState(
                vpnProfiles =
                    persistentListOf(
                        RelayProfileUiState("active", "vless_reality", "VLESS", "", ""),
                        RelayProfileUiState("draft", "vless_reality", "VLESS", "", ""),
                    ),
            ).copy(
                activeRelayProfileId = "active",
                activeRelayEnabled = true,
                draft =
                    AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                        relayProfileId = "draft",
                        relayEnabled = true,
                    ),
            )
        composeRule.setContent {
            RipDpiTheme {
                ConnectionRelayProfileChoices(state, ConnectionProfileActions(), {})
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionRelayProfileSelect("active"))
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(RipDpiTestTags.connectionRelayProfileSelect("draft"))
            .assertIsEnabled()
    }

    @Test
    fun `relay profile ID is hidden while the relay is off`() {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft().copy(relayEnabled = false)
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorRelaySection(draft, configUiState(), NoOpModeEditorActions)
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorRelayEnabled).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ModeEditorRelayProfileId).assertDoesNotExist()
    }

    @Test
    fun `saved relay profile ID is read only in mode editor`() {
        val draft =
            AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                relayEnabled = true,
                relayProfileId = "saved",
                editingRelayProfileId = "saved",
            )
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorRelaySection(draft, configUiState(), NoOpModeEditorActions)
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ModeEditorRelayProfileId)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        composeRule
            .onNodeWithTag(RipDpiTestTags.modeEditorRelayChip("vless_reality"))
            .assertIsNotEnabled()
    }

    @Test
    fun `Apps Script cannot be selected for VPN and explains existing profiles`() {
        val draft =
            AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                mode = Mode.VPN,
                relayEnabled = true,
                relayKind = "google_apps_script",
                relayProfileId = "saved-apps-script",
                editingRelayProfileId = "saved-apps-script",
            )
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorRelaySection(draft, configUiState(), NoOpModeEditorActions)
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.modeEditorRelayChip("google_apps_script"))
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Apps Script is unavailable in VPN mode").assertExists()
    }

    @Test
    fun `Apps Script chip is available only for new proxy profiles`() {
        val draft =
            mutableStateOf(
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    mode = Mode.VPN,
                    relayEnabled = true,
                ),
            )
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorRelaySection(draft.value, configUiState(), NoOpModeEditorActions)
            }
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.modeEditorRelayChip("google_apps_script"))
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Apps Script is unavailable in VPN mode").assertExists()
        composeRule.runOnIdle { draft.value = draft.value.copy(mode = Mode.Proxy) }
        composeRule
            .onNodeWithTag(RipDpiTestTags.modeEditorRelayChip("google_apps_script"))
            .assertIsEnabled()
        composeRule.onNodeWithText("Apps Script is unavailable in VPN mode").assertDoesNotExist()
    }

    private fun setConfigScreen(
        initialPath: ConnectionPath,
        onModeSelected: (Mode) -> Unit = {},
        onEditCurrent: () -> Unit = {},
        onOpenLocalBypassEditor: () -> Unit = {},
        onOpenDnsSettings: () -> Unit = {},
        onRetestStrategies: () -> Unit = {},
        onPasteServerLink: () -> Unit = {},
        onScanServer: () -> Unit = {},
        profileActions: ConnectionProfileActions = ConnectionProfileActions(),
        uiPersona: String = "simple",
        vpnProfiles: ImmutableList<RelayProfileUiState> = persistentListOf(),
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ) {
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState =
                        configUiState(
                            uiPersona = uiPersona,
                            vpnProfiles = vpnProfiles,
                            activeMode = activeMode,
                            runningMode = runningMode,
                        ),
                    onModeSelected = onModeSelected,
                    onPresetSelected = {},
                    onEditCurrent = onEditCurrent,
                    onOpenConfigEditor = { path ->
                        when (path) {
                            ConnectionEditorTarget.Bypass -> onOpenLocalBypassEditor()

                            ConnectionEditorTarget.Resolver -> onOpenDnsSettings()

                            ConnectionEditorTarget.SubscriptionStatus,
                            ConnectionEditorTarget.SubscriptionFailover,
                            -> Unit
                        }
                    },
                    onRetestStrategies = onRetestStrategies,
                    onPasteServerLink = onPasteServerLink,
                    profileActions = profileActions.copy(scanServer = onScanServer),
                    initialPath = initialPath,
                )
            }
        }
    }

    private fun configUiState(
        uiPersona: String = "simple",
        vpnProfiles: ImmutableList<RelayProfileUiState> = persistentListOf(),
        activeMode: Mode = Mode.VPN,
        runningMode: Mode? = null,
    ): ConnectionUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft().copy(mode = activeMode)
        return ConnectionUiState(
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
