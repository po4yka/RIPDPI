package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class PcapViewerRouteTest {
    @get:Rule val composeRule = createComposeRule()

    private val captureDirectory =
        File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "pcap").apply { mkdirs() }

    @Test
    fun `missing capture explains why and offers only Back without a packet count`() {
        var backs = 0
        composeRule.setContent { RipDpiTheme { PcapViewerRoute("missing.pcap", onBack = { backs++ }) } }

        awaitText("The capture file no longer exists. It may have been deleted.")
        composeRule.onNodeWithText("Packets: 0").assertDoesNotExist()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `unreadable capture can be read again after it is fixed`() {
        val file = File(captureDirectory, "broken.pcap").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        composeRule.setContent { RipDpiTheme { PcapViewerRoute(file.name, onBack = {}) } }

        awaitText("The file is damaged or is not a supported PCAP capture.")
        composeRule.onNodeWithText("Packets: 0").assertDoesNotExist()

        // Raw-IP pcap header (linktype 101) with no packet records.
        file.writeBytes(
            "d4c3b2a1 02000400 00000000 00000000 00000100 65000000"
                .filterNot { it.isWhitespace() }
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray(),
        )
        composeRule.onNodeWithText("Try again").performClick()
        awaitText("Packets: 0")
        composeRule.onNodeWithText("This capture has no packets.").assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }
}
