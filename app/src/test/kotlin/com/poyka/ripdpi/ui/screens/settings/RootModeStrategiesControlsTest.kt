package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test
    fun `strategies are listed while root mode is off and root status shows once checked`() {
        var state by mutableStateOf(RootModeStrategiesUiState(rootModeEnabled = false))
        composeRule.setContent {
            RipDpiTheme {
                RootModeStrategiesScreen(uiState = state, onBack = {}, onOpenStrategyConfig = {})
            }
        }
        val app = RuntimeEnvironment.getApplication()

        composeRule.onNodeWithText(app.getString(R.string.root_mode_strategy_fakerst_title)).assertExists()
        composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("root_mode_strategies_configure")
        composeRule.onNodeWithTag(RipDpiTestTags.RootModeStrategiesConfigure).assertIsNotEnabled()
        composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("root_mode_toggle")
        composeRule.onNodeWithText(app.getString(R.string.root_mode_access_denied)).assertDoesNotExist()

        state = RootModeStrategiesUiState(rootModeEnabled = true, rootAccess = RootAccessState.Denied)
        composeRule.onNodeWithText(app.getString(R.string.root_mode_access_denied)).assertExists()
        composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("root_mode_strategies_configure")
        composeRule.onNodeWithTag(RipDpiTestTags.RootModeStrategiesConfigure).assertIsEnabled()
    }

    private fun testActions() =
        SettingsScreenActions(
            onOpenProfileUtility = { error("Unexpected profile utility action") },
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
