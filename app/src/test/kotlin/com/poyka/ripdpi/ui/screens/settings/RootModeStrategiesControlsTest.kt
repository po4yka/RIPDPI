package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class RootModeStrategiesControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `root strategies entry is reachable while root mode is disabled`() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsConnectivitySection(
                    uiState = SettingsUiState(rootModeEnabled = false, uiPersona = "advanced"),
                    actions = testActions(),
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsRootModeStrategies).assertExists()
    }

    @Test
    fun `root mode switch invokes enable callback`() {
        var enabled = false
        composeRule.setContent {
            RipDpiTheme {
                RootModeStrategiesScreen(
                    uiState = RootModeStrategiesUiState(rootModeEnabled = false),
                    onBack = {},
                    onOpenStrategyConfig = {},
                    onRootModeEnabledChange = { enabled = it },
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.RootModeToggle).assertIsOff().performClick()
        assertTrue(enabled)
    }

    private fun testActions() =
        SettingsScreenActions(
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onShareDebugBundle = {},
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
            onThemeSelected = {},
            onPersonaSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onStartOnBootChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
}
