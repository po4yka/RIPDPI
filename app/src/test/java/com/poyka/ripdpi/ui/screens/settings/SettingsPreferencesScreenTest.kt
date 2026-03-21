package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
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
class SettingsPreferencesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun supportBundleActionIsRenderedAndInvokesCallback() {
        var clicked = false

        composeRule.setContent {
            RipDpiTheme {
                SettingsScreen(
                    uiState = SettingsUiState(),
                    onOpenDnsSettings = {},
                    onOpenAdvancedSettings = {},
                    onOpenCustomization = {},
                    onOpenAbout = {},
                    onOpenDataTransparency = {},
                    onShareDebugBundle = { clicked = true },
                    permissionSummary = PermissionSummaryUiState(),
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onThemeSelected = {},
                    onWebRtcProtectionChanged = {},
                    onBiometricChanged = {},
                    onSaveBackupPin = {},
                )
            }
        }

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.SettingsSupportBundle))
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSupportBundle).fetchSemanticsNode()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSupportBundle).performClick()

        assertTrue(clicked)
    }
}
