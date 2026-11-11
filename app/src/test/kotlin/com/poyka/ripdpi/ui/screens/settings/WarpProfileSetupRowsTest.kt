package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class WarpProfileSetupRowsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `registration requires confirmation before its saved result can appear`() {
        var saved by mutableStateOf(false)
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    WarpProfileSetupRows(
                        state = WarpEnrollmentUiState(saved = saved),
                        enabled = true,
                        onRegisterProfile = { saved = true },
                        onImportProfile = {},
                        onCancelSetup = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupStatus).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpCreateProfile).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupConfirmation).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupStatus).assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupConfirmation).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupStatus).assertDoesNotExist()

        composeRule.onNodeWithTag(RipDpiTestTags.WarpCreateProfile).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupConfirm).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupConfirmation).assertDoesNotExist()
        composeRule.onNodeWithText("Profile saved").assertExists()
    }

    @Test
    fun `cancelling registration disables new work and reports the uncertain remote result`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    WarpProfileSetupRows(
                        state =
                            WarpEnrollmentUiState(
                                operation = WarpSetupOperation.Registration,
                                cancellationRequested = true,
                            ),
                        enabled = true,
                        onRegisterProfile = {},
                        onImportProfile = {},
                        onCancelSetup = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.WarpCreateProfile).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpImportProfile).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.WarpSetupCancel).assertIsNotEnabled()
        composeRule
            .onNodeWithText("Waiting for the request to finish. The registration may already exist at Cloudflare.")
            .assertExists()
        composeRule.onNodeWithText("Profile saved").assertDoesNotExist()
    }
}
