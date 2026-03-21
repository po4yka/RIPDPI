package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.HostAutolearnUiState
import com.poyka.ripdpi.activities.ProxyNetworkUiState
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
class AdvancedSettingsSectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    // -- Diagnostics History --

    @Test
    fun `diagnostics section renders monitor toggle and text fields`() {
        setDiagnosticsSection()

        composeRule.onNodeWithText("HISTORY").assertExists()
        composeRule.onNodeWithText("Record runtime diagnostics history").assertExists()
    }

    @Test
    fun `diagnostics monitor toggle fires callback`() {
        val toggles = mutableListOf<Pair<AdvancedToggleSetting, Boolean>>()
        setDiagnosticsSection(
            uiState = SettingsUiState(diagnosticsMonitorEnabled = false),
            onToggleChanged = { s, v -> toggles.add(s to v) },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled))
            .performClick()
        assertEquals(AdvancedToggleSetting.DiagnosticsMonitorEnabled, toggles.single().first)
        assertTrue(toggles.single().second)
    }

    @Test
    fun `diagnostics export toggle renders`() {
        setDiagnosticsSection(
            uiState = SettingsUiState(diagnosticsExportIncludeHistory = true),
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsExportIncludeHistory))
            .assertIsOn()
    }

    // -- Command-Line Overrides --

    @Test
    fun `overrides section renders toggle and text field`() {
        setOverridesSection()

        composeRule.onNodeWithText("OVERRIDES").assertExists()
        composeRule.onNodeWithText("Use command line settings").assertExists()
    }

    @Test
    fun `overrides toggle on when enabled`() {
        setOverridesSection(uiState = SettingsUiState(enableCmdSettings = true))

        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.UseCommandLine)).assertIsOn()
    }

    @Test
    fun `overrides toggle off when disabled`() {
        setOverridesSection(uiState = SettingsUiState(enableCmdSettings = false))

        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.UseCommandLine)).assertIsOff()
    }

    // -- Proxy --

    @Test
    fun `proxy section renders header`() {
        setProxySection()

        composeRule.onNodeWithText("PROXY").assertExists()
    }

    @Test
    fun `proxy no domain toggle reflects state`() {
        setProxySection(uiState = SettingsUiState(proxy = ProxyNetworkUiState(noDomain = true)))
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.NoDomain)).assertIsOn()
    }

    @Test
    fun `proxy tcp fast open toggle reflects state`() {
        setProxySection(uiState = SettingsUiState(proxy = ProxyNetworkUiState(tcpFastOpen = true)))
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.TcpFastOpen)).assertIsOn()
    }

    @Test
    fun `proxy controls disabled under command line mode`() {
        setProxySection(uiState = SettingsUiState(enableCmdSettings = true))

        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.NoDomain)).assertIsNotEnabled()
    }

    // -- Protocols --

    @Test
    fun `protocols section renders all three toggles`() {
        setProtocolsSection()

        composeRule.onNodeWithText("PROTOCOLS").assertExists()
        composeRule.onNodeWithText("Desync HTTP").assertExists()
        composeRule.onNodeWithText("Desync HTTPS").assertExists()
        composeRule.onNodeWithText("Desync UDP").assertExists()
    }

    @Test
    fun `protocols toggles reflect state`() {
        setProtocolsSection(
            uiState = SettingsUiState(desyncHttp = true, desyncHttps = false, desyncUdp = true),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp)).assertIsOn()
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttps)).assertIsOff()
        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncUdp)).assertIsOn()
    }

    @Test
    fun `protocols toggles disabled under command line mode`() {
        setProtocolsSection(uiState = SettingsUiState(enableCmdSettings = true))

        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp)).assertIsNotEnabled()
    }

    @Test
    fun `protocols toggle fires callback`() {
        val toggles = mutableListOf<Pair<AdvancedToggleSetting, Boolean>>()
        setProtocolsSection(
            uiState = SettingsUiState(desyncHttp = false),
            onToggleChanged = { s, v -> toggles.add(s to v) },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp)).performClick()
        assertEquals(AdvancedToggleSetting.DesyncHttp, toggles.single().first)
    }

    // -- Network Strategy Memory --

    @Test
    fun `network strategy memory section renders toggle and clear button`() {
        setNetworkStrategyMemorySection()

        composeRule.onNodeWithText("NETWORK STRATEGY MEMORY").assertExists()
        composeRule.onNodeWithText("Remember policy per network").assertExists()
    }

    @Test
    fun `network strategy memory toggle fires callback`() {
        val toggles = mutableListOf<Pair<AdvancedToggleSetting, Boolean>>()
        setNetworkStrategyMemorySection(
            uiState = SettingsUiState(autolearn = HostAutolearnUiState(networkStrategyMemoryEnabled = false)),
            onToggleChanged = { s, v -> toggles.add(s to v) },
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.NetworkStrategyMemoryEnabled))
            .performClick()
        assertEquals(AdvancedToggleSetting.NetworkStrategyMemoryEnabled, toggles.single().first)
    }

    @Test
    fun `network strategy memory clear button fires callback`() {
        var cleared = false
        setNetworkStrategyMemorySection(
            uiState = SettingsUiState(autolearn = HostAutolearnUiState(rememberedNetworkCount = 1)),
            onClearRememberedNetworks = { cleared = true },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.AdvancedClearRememberedNetworks).performClick()
        assertTrue(cleared)
    }

    @Test
    fun `network strategy memory clear button disabled when not clearable`() {
        setNetworkStrategyMemorySection(
            uiState = SettingsUiState(autolearn = HostAutolearnUiState(rememberedNetworkCount = 0)),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.AdvancedClearRememberedNetworks).assertIsNotEnabled()
    }

    @Test
    fun `network strategy memory toggle enabled when visual editing active`() {
        setNetworkStrategyMemorySection(
            uiState = SettingsUiState(enableCmdSettings = false),
        )

        composeRule
            .onNodeWithTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.NetworkStrategyMemoryEnabled))
            .assertIsEnabled()
    }

    // -- Helpers --

    private fun setDiagnosticsSection(
        uiState: SettingsUiState = SettingsUiState(),
        onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit = { _, _ -> },
        onTextConfirmed: (AdvancedTextSetting, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            RipDpiTheme {
                LazyColumn(modifier = Modifier.height(TALL_VIEWPORT)) {
                    diagnosticsHistorySection(
                        uiState = uiState,
                        onToggleChanged = onToggleChanged,
                        onTextConfirmed = onTextConfirmed,
                    )
                }
            }
        }
    }

    private fun setOverridesSection(
        uiState: SettingsUiState = SettingsUiState(),
        onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit = { _, _ -> },
        onTextConfirmed: (AdvancedTextSetting, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            RipDpiTheme {
                LazyColumn(modifier = Modifier.height(TALL_VIEWPORT)) {
                    commandLineOverridesSection(
                        uiState = uiState,
                        onToggleChanged = onToggleChanged,
                        onTextConfirmed = onTextConfirmed,
                    )
                }
            }
        }
    }

    private fun setProxySection(
        uiState: SettingsUiState = SettingsUiState(),
        onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit = { _, _ -> },
        onTextConfirmed: (AdvancedTextSetting, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            RipDpiTheme {
                LazyColumn(modifier = Modifier.height(TALL_VIEWPORT)) {
                    proxySection(
                        uiState = uiState,
                        visualEditorEnabled = !uiState.enableCmdSettings,
                        onToggleChanged = onToggleChanged,
                        onTextConfirmed = onTextConfirmed,
                    )
                }
            }
        }
    }

    private fun setProtocolsSection(
        uiState: SettingsUiState = SettingsUiState(),
        onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            RipDpiTheme {
                LazyColumn(modifier = Modifier.height(TALL_VIEWPORT)) {
                    protocolsSection(
                        uiState = uiState,
                        visualEditorEnabled = !uiState.enableCmdSettings,
                        onToggleChanged = onToggleChanged,
                    )
                }
            }
        }
    }

    private fun setNetworkStrategyMemorySection(
        uiState: SettingsUiState = SettingsUiState(),
        onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit = { _, _ -> },
        onClearRememberedNetworks: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                LazyColumn(modifier = Modifier.height(TALL_VIEWPORT)) {
                    networkStrategyMemorySection(
                        uiState = uiState,
                        visualEditorEnabled = !uiState.enableCmdSettings,
                        onToggleChanged = onToggleChanged,
                        onClearRememberedNetworks = onClearRememberedNetworks,
                    )
                }
            }
        }
    }

    companion object {
        private val TALL_VIEWPORT = 2000.dp
    }
}
