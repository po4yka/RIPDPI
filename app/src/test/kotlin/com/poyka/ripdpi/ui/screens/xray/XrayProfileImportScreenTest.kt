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
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
                    uiState = XrayImportUiState(selectedOption = XrayServiceModeOption.XrayVpn),
                    onBack = {},
                    onSelectOption = { selected = it },
                    onRawInputChange = {},
                    onValidate = {},
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
                            rawInput = "vless://…",
                            errorMessage = redacted,
                            acceptedConfigReady = false,
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
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
    fun acceptedProfileShowsCapabilitiesAndEnablesFinish() {
        var confirmed = false
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.XrayVpn,
                            rawInput = "vless://…",
                            acceptedConfigReady = true,
                            capabilities =
                                listOf(
                                    XrayCapability.VPN_PRIVACY,
                                    XrayCapability.RELAY,
                                    XrayCapability.ANTI_DPI,
                                ),
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
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
    fun nativeOptionCanFinishWithoutProfile() {
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState = XrayImportUiState(selectedOption = XrayServiceModeOption.NativeDirect),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
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
