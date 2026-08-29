// Robolectric/Compose test for the Xray provider-selection + import screen.
// The underlying parse/validate/capability logic is additionally unit-tested in
// :core:data:catalog (XrayImportParserTest, XrayCapabilityTest) and
// :core:data:runtime-state (XrayServiceModeOptionTest).
package com.poyka.ripdpi.ui.screens.xray

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.subscription.XraySkippedNode
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
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
class XrayProfileImportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun selectingXrayOptionRevealsImportSectionAndGatesFinish() {
        var selected: XrayServiceModeOption? = null
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.XrayVpn,
                            restoreStatus = XrayImportRestoreStatus.Ready,
                        ),
                    onBack = {},
                    onSelectOption = { selected = it },
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = {},
                )
            }
        }

        // Import field is shown for the Xray option.
        composeRule.onNodeWithTag("xray_import_input").performScrollTo().assertIsDisplayed()
        // Finish is disabled until a profile is accepted.
        composeRule
            .onNodeWithText(string(R.string.xray_import_finish_action))
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag("xray_mode_NativeDirect").performScrollTo().performClick()
        assertEquals(XrayServiceModeOption.NativeDirect, selected)
    }

    @Test
    fun validationFailureSurfacesRedactedError() {
        val redacted = "Profile disables certificate checks, which is not allowed."
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.XrayVpn,
                            restoreStatus = XrayImportRestoreStatus.Ready,
                            rawInput = "vless://…",
                            errorMessage = redacted,
                            acceptedConfigReady = false,
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = {},
                )
            }
        }

        // The redacted message surfaces in the error banner (and, identically, as
        // the input-field error — so the text matches more than one node).
        composeRule.onNodeWithTag("xray_import_error").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(redacted).fetchSemanticsNodes().isNotEmpty())
        // Still cannot finish.
        composeRule
            .onNodeWithText(string(R.string.xray_import_finish_action))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun skippedNodesHideParserLabelAndDetail() {
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.XrayVpn,
                            restoreStatus = XrayImportRestoreStatus.Ready,
                            errorMessage = string(R.string.xray_import_error_no_supported),
                            skipped =
                                listOf(
                                    XraySkippedNode(
                                        index = 0,
                                        label = "secret-host.example",
                                        reason = XraySkipReason.UNSUPPORTED_PROTOCOL,
                                        detail = "vmess://secret-token",
                                    ),
                                ).toImmutableList(),
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag("xray_import_skipped").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Node 1: ${string(R.string.xray_skip_unsupported_protocol_safe)}")
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("secret-host.example", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("vmess://secret-token", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun acceptedProfileShowsCapabilitiesAndEnablesFinish() {
        var confirmed = false
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.XrayVpn,
                            restoreStatus = XrayImportRestoreStatus.Ready,
                            rawInput = "vless://…",
                            acceptedConfigReady = true,
                            capabilities =
                                listOf(
                                    XrayCapability.VPN_PRIVACY,
                                    XrayCapability.RELAY,
                                    XrayCapability.ANTI_DPI,
                                ).toImmutableList(),
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeRule
            .onNodeWithText(string(R.string.xray_capability_anti_dpi_title))
            .performScrollTo()
            .assertIsDisplayed()
        val finish = composeRule.onNodeWithText(string(R.string.xray_import_finish_action))
        finish.performScrollTo().assertIsEnabled()
        finish.performClick()
        assertTrue(confirmed)
    }

    @Test
    fun loadingRestoreDisablesFinish() {
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState = XrayImportUiState(selectedOption = XrayServiceModeOption.NativeDirect),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag("xray_import_restore_loading").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.xray_import_finish_action))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun restoreFailureShowsRetryAndBlocksFinish() {
        var retried = false
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.NativeDirect,
                            restoreStatus = XrayImportRestoreStatus.Failed,
                            restoreErrorMessage = string(R.string.xray_import_error_restore_failed),
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = { retried = true },
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithTag("xray_import_restore_failed").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.xray_import_restore_retry_action))
            .performScrollTo()
            .performClick()
        assertTrue(retried)
        composeRule
            .onNodeWithText(string(R.string.xray_import_finish_action))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun nativeOptionCanFinishWithoutProfile() {
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.NativeDirect,
                            restoreStatus = XrayImportRestoreStatus.Ready,
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = {},
                )
            }
        }

        composeRule
            .onNodeWithText(string(R.string.xray_import_finish_action))
            .performScrollTo()
            .assertIsEnabled()
    }
}
