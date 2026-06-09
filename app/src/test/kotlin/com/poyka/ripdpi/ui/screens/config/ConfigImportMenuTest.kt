package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.proxyimport.ClipboardReader
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
import com.poyka.ripdpi.ui.screens.proxyimport.ClipboardImportViewModel
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
    fun `rendering the menu does not read the clipboard`() {
        val clipboard = RecordingClipboardReader("vless://uuid@example.com:443#Clip")
        val viewModel = ClipboardImportViewModel(clipboard)

        composeRule.setContent {
            RipDpiTheme {
                ConfigImportMenu(onProfileImport = {}, viewModel = viewModel)
            }
        }

        composeRule.runOnIdle { assertEquals(0, clipboard.readCount) }
    }

    @Test
    fun `tapping the import menu item reads the clipboard once and routes`() {
        val clipboard = RecordingClipboardReader("vless://uuid@example.com:443#Clip")
        val viewModel = ClipboardImportViewModel(clipboard)
        var routed: ProxyImportRequest.Profile? = null

        composeRule.setContent {
            RipDpiTheme {
                ConfigImportMenu(onProfileImport = { routed = it }, viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigOverflowMenuButton).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigImportClipboardMenuItem).performClick()

        composeRule.runOnIdle {
            assertEquals(1, clipboard.readCount)
            assertEquals("example.com", (routed?.profile as? com.poyka.ripdpi.data.ProxyProfile.Vless)?.server)
        }
    }

    @Test
    fun `unknown clipboard content surfaces an error and does not route`() {
        val clipboard = RecordingClipboardReader("http://malicious.example.com/leak")
        val viewModel = ClipboardImportViewModel(clipboard)
        var routed: ProxyImportRequest.Profile? = null

        composeRule.setContent {
            RipDpiTheme {
                ConfigImportMenu(onProfileImport = { routed = it }, viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.ConfigOverflowMenuButton).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigImportClipboardMenuItem).performClick()

        composeRule.runOnIdle {
            assertEquals(null, routed)
            assertEquals("http", viewModel.uiState.value.unknownContentScheme)
        }
    }
}

/** [ClipboardReader] double that counts reads so tests can assert the no-watch contract. */
private class RecordingClipboardReader(
    private val contents: String?,
) : ClipboardReader {
    var readCount: Int = 0
        private set

    override fun readPrimaryClipText(): String? {
        readCount += 1
        return contents
    }

    override fun clearPrimaryClip() = Unit
}
