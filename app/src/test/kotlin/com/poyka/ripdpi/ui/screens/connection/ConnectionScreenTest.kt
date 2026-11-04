package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
            initialModeSection = ConnectionModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.LocalBypass.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertDoesNotExist()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(emptyList<Mode>(), selectedModes)
        }
    }

    @Test
    fun `mode section tabs expose tab semantics`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.LocalBypass)

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.LocalBypass.stableKey))
            .assertHasRole(Role.Tab)
            .assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey))
            .assertHasRole(Role.Tab)
            .assertIsNotSelected()
    }

    @Test
    fun `connection card exposes visible group labels without duplicate descriptions`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.LocalBypass)

        // One label per control group: no card heading and no extra label above the section tabs.
        composeRule.onNodeWithText("Connection settings").assertDoesNotExist()
        composeRule.onNodeWithText("Configure").assertDoesNotExist()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigSectionNavigation)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithText("Choose traffic mode").assertDoesNotExist()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigTrafficEndpointSelection)
            .assertContentDescription("Choose traffic mode")
    }

    @Test
    fun `mode chips expose radio button semantics`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.LocalBypass)

        composeRule
            .onNodeWithTag(RipDpiTestTags.configMode(Mode.VPN.name))
            .assertHasRole(Role.RadioButton)
            .assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configMode(Mode.Proxy.name))
            .assertHasRole(Role.RadioButton)
            .assertIsNotSelected()
    }

    @Test
    fun `traffic endpoint choice invokes mode callback without changing section`() {
        val selectedModes = mutableListOf<Mode>()
        setConfigScreen(
            initialModeSection = ConnectionModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.configMode(Mode.Proxy.name))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.LocalBypass.stableKey))
            .assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(Mode.Proxy), selectedModes)
        }
    }

    @Test
    fun `traffic endpoint choice can return from proxy to vpn without changing section`() {
        val selectedModes = mutableListOf<Mode>()
        setConfigScreen(
            initialModeSection = ConnectionModeSection.LocalBypass,
            onModeSelected = { selectedModes += it },
            activeMode = Mode.Proxy,
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.configMode(Mode.VPN.name))
            .performScrollTo()
            .performClick()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.LocalBypass.stableKey))
            .assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(Mode.VPN), selectedModes)
        }
    }

    @Test
    fun `local bypass section renders listen dns and desync rows`() {
        setConfigScreen(
            initialModeSection = ConnectionModeSection.LocalBypass,
            uiPersona = "advanced",
            activeMode = Mode.Proxy,
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        // The mode chips are the one place that shows the mode.
        composeRule.onAllNodesWithText("Proxy only").assertCountEquals(1)
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassListenAddress).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync).assertExists()
    }

    @Test
    fun `vpn mode hides the internal proxy listen address`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.LocalBypass, activeMode = Mode.VPN)

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigDnsSettings).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassListenAddress).assertDoesNotExist()
    }

    @Test
    fun `simple local bypass shows auto strategy retest and precedence note`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.LocalBypass)

        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigEditCurrentButton).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigConnectionDetails).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassStrategyAuto).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassPrecedenceNote).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassRetest)
            .assertTextEquals("Open Diagnostics to re-test")
            .assertHasClickAction()
    }

    @Test
    fun `local bypass desync and dns rows expose actions`() {
        var legacyEditorClicks = 0
        var strategyClicks = 0
        var dnsClicks = 0
        setConfigScreen(
            initialModeSection = ConnectionModeSection.LocalBypass,
            onEditCurrent = { legacyEditorClicks += 1 },
            onOpenLocalBypassEditor = { strategyClicks += 1 },
            onOpenDnsSettings = { dnsClicks += 1 },
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
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
            initialModeSection = ConnectionModeSection.LocalBypass,
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigLocalBypassDesync)
            .assertContentDescription("Bypass strategy")
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertContentDescription("DNS settings")
    }

    @Test
    fun `dns action exposes its current value to accessibility services`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.Vpn, uiPersona = "advanced")

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Encrypted DNS · AdGuard DNS (DoH)",
                ),
            )
    }

    @Test
    fun `vpn section with the relay off shows one relay setup row and dns`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.Vpn, uiPersona = "advanced")

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerPaste).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerScan).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).assertExists()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnRelay)
            .assert(hasContentDescription("Relay off", substring = true))
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProtocol).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnCredentials).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigDnsSettings).assertExists()
    }

    @Test
    fun `vpn add profile menu opens every dedicated editor`() {
        val opened = mutableListOf<Route>()
        setConfigScreen(
            initialModeSection = ConnectionModeSection.Vpn,
            profileActions = ConnectionProfileActions(create = { opened += it }),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        val editors =
            listOf(
                Route.AnyTlsProfile,
                Route.MieruProfile,
                Route.SshProfile,
                Route.AmneziaWgProfile(),
                Route.SubscriptionAdd,
            )
        editors.forEach { route ->
            composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnAddProfile).performScrollTo().performClick()
            composeRule.onNodeWithTag(RipDpiTestTags.configVpnCreateProfile(route)).performClick()
        }

        composeRule.runOnIdle { assertEquals(editors, opened) }
    }

    @Test
    fun `relay off row opens profile choices and dns row invokes its callback`() {
        var dnsClicks = 0
        setConfigScreen(
            initialModeSection = ConnectionModeSection.Vpn,
            onOpenDnsSettings = { dnsClicks += 1 },
            uiPersona = "advanced",
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnRelay)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChoices).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigDnsSettings)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, dnsClicks)
        }
    }

    @Test
    fun `strategy row shows auto only for an untouched guided chain`() {
        val default = AppSettingsSerializer.defaultValue.toConfigDraft()
        var uiPersona by mutableStateOf("simple")
        var draft by mutableStateOf(default)
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState = configUiState(uiPersona = uiPersona).copy(draft = draft),
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
    fun `path control follows the active relay and switches the path`() {
        val selected = mutableListOf<String>()
        var directSelections = 0
        var relayActive by mutableStateOf(true)
        composeRule.setContent {
            RipDpiTheme {
                ConnectionScreen(
                    uiState =
                        configUiState().copy(
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
        val directTag = RipDpiTestTags.configModeSection(ConnectionModeSection.LocalBypass.stableKey)
        val relayTag = RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey)

        composeRule.onNodeWithTag(relayTag).assertIsSelected()
        composeRule.onNodeWithTag(directTag).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, directSelections)
            relayActive = false
        }
        composeRule.onNodeWithTag(directTag).assertIsSelected()

        composeRule.onNodeWithTag(relayTag).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("saved"), selected) }
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
                    initialModeSection = ConnectionModeSection.LocalBypass,
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey))
            .performScrollTo()
            .performClick()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
    }

    @Test
    fun `vpn route initial section starts on vpn summary`() {
        setConfigScreen(initialModeSection = ConnectionModeSection.Vpn)

        composeRule
            .onNodeWithTag(RipDpiTestTags.configModeSection(ConnectionModeSection.Vpn.stableKey))
            .assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnSummary).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigLocalBypassSummary).assertDoesNotExist()
    }

    @Test
    fun `vpn simple add server actions are prominent`() {
        var pasteClicks = 0
        var scanClicks = 0
        val profileShareClicks = mutableListOf<String>()
        setConfigScreen(
            initialModeSection = ConnectionModeSection.Vpn,
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
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        composeRule.onNodeWithText("Add profile").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.configVpnProfile("default")).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileRow("default"))
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerPaste)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnAddServerScan)
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileShare("default"))
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
            initialModeSection = ConnectionModeSection.Vpn,
            vpnProfiles = persistentListOf(RelayProfileUiState("unknown-imported", "unknown", "Unknown", "", "")),
            profileActions = ConnectionProfileActions(select = { selected += it }),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigProfileChange).performScrollTo().performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileRow("unknown-imported"))
            .assertHasNoClickAction()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileSelect("unknown-imported"))
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
                VpnConfigScreen(state, {}, {}, {}, {})
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileSelect("active"))
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileSelect("draft"))
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
        initialModeSection: ConnectionModeSection,
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
                    initialModeSection = initialModeSection,
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
