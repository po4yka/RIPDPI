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
import com.poyka.ripdpi.ui.testing.WarpTestTags
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

        composeRule.onNodeWithTag(WarpTestTags.WarpSetupStatus).assertDoesNotExist()
        composeRule.onNodeWithTag(WarpTestTags.WarpCreateProfile).performClick()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupConfirmation).assertExists()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupStatus).assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupConfirmation).assertDoesNotExist()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupStatus).assertDoesNotExist()

        composeRule.onNodeWithTag(WarpTestTags.WarpCreateProfile).performClick()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupConfirm).performClick()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupConfirmation).assertDoesNotExist()
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

        composeRule.onNodeWithTag(WarpTestTags.WarpCreateProfile).assertIsNotEnabled()
        composeRule.onNodeWithTag(WarpTestTags.WarpImportProfile).assertIsNotEnabled()
        composeRule.onNodeWithTag(WarpTestTags.WarpSetupCancel).assertIsNotEnabled()
        composeRule
            .onNodeWithText("Waiting for the request to finish. The registration may already exist at Cloudflare.")
            .assertExists()
        composeRule.onNodeWithText("Profile saved").assertDoesNotExist()
    }
}
