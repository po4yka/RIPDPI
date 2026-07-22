package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric UI tests for [ConfigImportMenu]: the explicit "Import from clipboard"
 * overflow-menu action on the Profile screen.
 *
 * The decisive privacy assertion is that merely rendering the menu does not read the
 * clipboard — the read happens only when the user taps the menu item.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConfigImportMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rendering the menu does not request clipboard import`() {
        var importRequests = 0

        composeRule.setContent {
            RipDpiTheme {
                ConfigImportMenu(
                    unknownContentScheme = null,
                    clipboardEmpty = false,
                    onImportFromClipboard = { importRequests += 1 },
                    onDismissError = {},
                )
            }
        }

        composeRule.runOnIdle { assertEquals(0, importRequests) }
    }

    @Test
    fun `tapping the import menu item dispatches one import request`() {
        var importRequests = 0

        composeRule.setContent {
            RipDpiTheme {
                ConfigImportMenu(
                    unknownContentScheme = null,
                    clipboardEmpty = false,
                    onImportFromClipboard = { importRequests += 1 },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigOverflowMenuButton).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigImportClipboardMenuItem).performClick()

        composeRule.runOnIdle {
            assertEquals(1, importRequests)
        }
    }

    @Test
    fun `unknown clipboard content surfaces an error without dispatching import`() {
        var importRequests = 0

        composeRule.setContent {
            RipDpiTheme {
                ConfigImportMenu(
                    unknownContentScheme = "http",
                    clipboardEmpty = false,
                    onImportFromClipboard = { importRequests += 1 },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("Clipboard not a proxy link").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(0, importRequests)
        }
    }
}
