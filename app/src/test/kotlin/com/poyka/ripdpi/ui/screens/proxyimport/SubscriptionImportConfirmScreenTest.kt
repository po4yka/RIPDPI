package com.poyka.ripdpi.ui.screens.proxyimport

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionImportConfirmScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `an imported bundle names its servers and offers Connect and Select for each`() {
        val picks = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            RipDpiTheme {
                SubscriptionImportConfirmScreen(
                    uiState =
                        SubscriptionImportConfirmUiState(
                            name = "RIPDPI",
                            localBundle = true,
                            servers = persistentListOf(ImportedServer("a", "Tokyo"), ImportedServer("b", "Paris")),
                        ),
                    onBack = {},
                    onConfirm = {},
                    onSelectServer = { id, connect -> picks += id to connect },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.import_done_added, "RIPDPI")).assertIsDisplayed()
        composeRule.onNodeWithText("Tokyo").assertIsDisplayed()
        composeRule.onNodeWithText("Paris").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.import_confirm_add_action)).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.importServerConnect("b")).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.importServerSelect("a")).performClick()

        assertEquals(listOf("b" to true, "a" to false), picks)
    }
}
