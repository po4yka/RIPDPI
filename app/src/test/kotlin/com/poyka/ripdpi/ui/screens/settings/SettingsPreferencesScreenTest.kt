package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.R
import com.poyka.ripdpi.permissions.BackgroundGuidanceUiState
import com.poyka.ripdpi.permissions.PermissionItemUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    actions = testActions(onShareDebugBundle = { clicked = true }),
                    permissionSummary = PermissionSummaryUiState(),
                )
            }
        }

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.SettingsSupportBundle))
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSupportBundle).fetchSemanticsNode()
        composeRule
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.settings_support_debug_bundle_body))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsSupportBundle).performClick()

        assertTrue(clicked)
    }

    @Test
    fun backgroundGuidanceBannerRendersSeparatelyFromPermissionRows() {
        composeRule.setContent {
            RipDpiTheme {
                SettingsScreen(
                    uiState = SettingsUiState(),
                    actions = testActions(),
                    permissionSummary =
                        PermissionSummaryUiState(
                            backgroundGuidance =
                                BackgroundGuidanceUiState(
                                    title = "Background activity",
                                    message = "Vendor limits can still stop RIPDPI in the background.",
                                ),
                            items =
                                persistentListOf(
                                    PermissionItemUiState(
                                        kind = PermissionKind.BatteryOptimization,
                                        title = "Battery optimization",
                                        subtitle = "Doze exemption review.",
                                        statusLabel = "Recommended",
                                        actionLabel = "Review",
                                    ),
                                ),
                        ),
                )
            }
        }

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.SettingsBackgroundGuidanceBanner))
        composeRule
            .onNodeWithTag(RipDpiTestTags.SettingsBackgroundGuidanceBanner)
            .assertIsDisplayed()
            .assertHasNoClickAction()
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.settingsPermission(PermissionKind.BatteryOptimization)))
        composeRule
            .onNodeWithTag(RipDpiTestTags.settingsPermission(PermissionKind.BatteryOptimization))
            .assertIsDisplayed()
    }

    @Test
    fun vpnConsentPermissionRowInvokesRepairCallback() {
        var repairedKind: PermissionKind? = null
        var openedVpnDialog = false

        composeRule.setContent {
            RipDpiTheme {
                SettingsScreen(
                    uiState = SettingsUiState(),
                    actions =
                        testActions(
                            onRepairPermission = { repairedKind = it },
                            onOpenVpnPermissionDialog = { openedVpnDialog = true },
                        ),
                    permissionSummary =
                        PermissionSummaryUiState(
                            items =
                                persistentListOf(
                                    PermissionItemUiState(
                                        kind = PermissionKind.VpnConsent,
                                        title = "VPN consent",
                                        subtitle = "System VPN approval is missing.",
                                        statusLabel = "Missing",
                                        actionLabel = "Repair",
                                    ),
                                ),
                        ),
                )
            }
        }

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(RipDpiTestTags.settingsPermission(PermissionKind.VpnConsent)))
        composeRule.onNodeWithTag(RipDpiTestTags.settingsPermission(PermissionKind.VpnConsent)).performClick()

        assertEquals(PermissionKind.VpnConsent, repairedKind)
        assertFalse(openedVpnDialog)
    }

    private fun testActions(
        onShareDebugBundle: () -> Unit = {},
        onRepairPermission: (PermissionKind) -> Unit = {},
        onOpenVpnPermissionDialog: () -> Unit = {},
    ): SettingsScreenActions =
        SettingsScreenActions(
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onShareDebugBundle = onShareDebugBundle,
            onRepairPermission = onRepairPermission,
            onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onStartOnBootChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
}
