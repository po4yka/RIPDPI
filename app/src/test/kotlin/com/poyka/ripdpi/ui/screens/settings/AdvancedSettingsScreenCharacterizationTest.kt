package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdvancedSettingsScreenCharacterizationTest {
    @get:Rule
    val composeRule = createComposeRule()

    // -- Section presence --

    @Test
    fun `diagnostics section renders`() {
        setScreen()

        scrollToKey("advanced_diagnostics_history")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("diagnostics_history")).assertExists()
    }

    @Test
    fun `overrides section renders`() {
        setScreen()

        scrollToKey("advanced_overrides")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("overrides")).assertExists()
    }

    @Test
    fun `proxy section renders`() {
        setScreen()

        scrollToKey("advanced_proxy")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("proxy")).assertExists()
    }

    @Test
    fun `desync section renders`() {
        setScreen()

        scrollToKey("advanced_desync")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("desync")).assertExists()
    }

    @Test
    fun `protocols section renders`() {
        setScreen()

        scrollToKey("advanced_protocols")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("protocols")).assertExists()
    }

    @Test
    fun `host learning section renders`() {
        setScreen()

        scrollToKey("advanced_host_autolearn")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("host_autolearn")).assertExists()
    }

    @Test
    fun `network strategy memory section renders`() {
        setScreen()

        scrollToKey("advanced_network_strategy_memory")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedSection("network_strategy_memory")).assertExists()
    }

    // -- Command-line override banner --

    @Test
    fun `command line enabled shows restricted banner`() {
        setScreen(uiState = SettingsUiState(enableCmdSettings = true))

        scrollToKey("advanced_settings_warning")
        composeRule.onNodeWithTag(RipDpiTestTags.AdvancedCommandLineWarning).assertExists()
    }

    @Test
    fun `command line disabled hides restricted banner`() {
        setScreen(uiState = SettingsUiState(enableCmdSettings = false))

        composeRule.onNodeWithTag(RipDpiTestTags.AdvancedCommandLineWarning).assertDoesNotExist()
    }

    // -- Toggle callback --

    @Test
    fun `diagnostics toggle fires callback`() {
        val toggles = mutableListOf<Pair<AdvancedToggleSetting, Boolean>>()

        setScreen(
            uiState = SettingsUiState(diagnosticsMonitorEnabled = false),
            onToggleChanged = { setting, value -> toggles.add(setting to value) },
        )

        scrollToKey("advanced_diagnostics_history")
        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled))
            .performClick()

        assertEquals(1, toggles.size)
        assertEquals(AdvancedToggleSetting.DiagnosticsMonitorEnabled, toggles[0].first)
        assertTrue(toggles[0].second)
    }

    // -- Diagnostics monitor toggle reflects state --

    @Test
    fun `diagnostics monitor on state`() {
        setScreen(uiState = SettingsUiState(diagnosticsMonitorEnabled = true))

        scrollToKey("advanced_diagnostics_history")
        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled))
            .assertIsOn()
    }

    @Test
    fun `diagnostics monitor off state`() {
        setScreen(uiState = SettingsUiState(diagnosticsMonitorEnabled = false))

        scrollToKey("advanced_diagnostics_history")
        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled))
            .assertIsOff()
    }

    // -- Command-line toggle state --

    @Test
    fun `command line toggle is on when enabled`() {
        setScreen(uiState = SettingsUiState(enableCmdSettings = true))

        scrollToKey("advanced_overrides")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.UseCommandLine)).assertIsOn()
    }

    // -- Protocol toggles --

    @Test
    fun `protocol toggle state reflects ui state`() {
        setScreen(
            uiState = SettingsUiState(desyncHttp = true, desyncHttps = false, desyncUdp = false),
        )

        scrollToKey("advanced_protocols")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp)).assertIsOn()
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttps)).assertIsOff()
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncUdp)).assertIsOff()
    }

    @Test
    fun `protocol toggles enabled when visual editing active`() {
        setScreen(
            uiState = SettingsUiState(enableCmdSettings = false, desyncHttp = true),
        )

        scrollToKey("advanced_protocols")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp)).assertIsEnabled()
    }

    @Test
    fun `protocol toggles disabled under command line mode`() {
        setScreen(
            uiState = SettingsUiState(enableCmdSettings = true, desyncHttp = true),
        )

        scrollToKey("advanced_protocols")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp)).assertIsNotEnabled()
    }

    // -- Host editors show based on mode --

    @Test
    fun `blacklist editor shown when mode is blacklist`() {
        setScreen(
            uiState = SettingsUiState(hostsMode = "blacklist", hostsBlacklist = "example.com"),
        )

        scrollToKey("advanced_hosts")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedInput(AdvancedTextSetting.HostsBlacklist)).assertExists()
    }

    @Test
    fun `whitelist editor shown when mode is whitelist`() {
        setScreen(
            uiState = SettingsUiState(hostsMode = "whitelist", hostsWhitelist = "example.com"),
        )

        scrollToKey("advanced_hosts")
        composeRule.onNodeWithTag(RipDpiTestTags.advancedInput(AdvancedTextSetting.HostsWhitelist)).assertExists()
    }

    // -- Notice banner --

    @Test
    fun `notice banner shown when present`() {
        setScreen(
            notice =
                AdvancedNotice(
                    title = "Test notice title",
                    message = "Test notice message",
                    tone = com.poyka.ripdpi.ui.components.feedback.WarningBannerTone.Info,
                ),
        )

        scrollToKey("advanced_settings_notice")
        composeRule.onNodeWithTag(RipDpiTestTags.AdvancedNoticeBanner).assertExists()
    }

    @Test
    fun `notice banner hidden when null`() {
        setScreen(notice = null)

        composeRule.onNodeWithTag(RipDpiTestTags.AdvancedNoticeBanner).assertDoesNotExist()
    }

    // -- Helper --

    private fun scrollToKey(key: String) {
        composeRule
            .onNode(hasScrollToKeyAction())
            .performScrollToKey(key)
    }

    private fun setScreen(
        uiState: SettingsUiState = SettingsUiState(),
        hostPackCatalog: HostPackCatalogUiState = HostPackCatalogUiState(),
        notice: AdvancedNotice? = null,
        onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit = { _, _ -> },
        onTextConfirmed: (AdvancedTextSetting, String) -> Unit = { _, _ -> },
        onOptionSelected: (AdvancedOptionSetting, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            RipDpiTheme {
                AdvancedSettingsScreen(
                    uiState = uiState,
                    hostPackCatalog = hostPackCatalog,
                    notice = notice,
                    actions =
                        AdvancedSettingsActions(
                            onBack = {},
                            onToggleChanged = onToggleChanged,
                            onTextConfirmed = onTextConfirmed,
                            onOptionSelected = onOptionSelected,
                            onApplyHostPackPreset = { _, _, _ -> },
                            onRefreshHostPackCatalog = {},
                            onForgetLearnedHosts = {},
                            onClearRememberedNetworks = {},
                            onWsTunnelModeChanged = {},
                            onRotateTelemetrySalt = {},
                            onSaveActivationRange = { _, _, _ -> },
                            onResetAdaptiveSplit = {},
                            onResetAdaptiveFakeTtlProfile = {},
                            onResetActivationWindow = {},
                            onResetHttpParserEvasions = {},
                            onResetFakePayloadLibrary = {},
                            onResetFakeTlsProfile = {},
                            onRoutingPolicyModeSelected = {},
                            onDhtMitigationModeSelected = {},
                            onAntiCorrelationEnabledChanged = {},
                            onAppRoutingPresetEnabledChanged = { _, _ -> },
                        ),
                )
            }
        }
    }
}
