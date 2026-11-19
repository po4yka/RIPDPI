package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.QuicVersionEvidenceKey
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixConfig
import com.poyka.ripdpi.ui.screens.diagnostics.state.toQuicVersionRow
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class DiagnosticsQuicVersionComposeTest {
    @get:Rule val composeRule = createComposeRule()

    private val limits = """"limits":["initial_stage_only","local_port_separation_only"]"""

    private fun row(
        probeType: String,
        evidence: String,
    ) = requireNotNull(
        ProbeResult(probeType, "quic.example", "quic_version", listOf(ProbeDetail(QuicVersionEvidenceKey, evidence)))
            .toQuicVersionRow(),
    )

    @Test
    fun versionDifferenceShowsEachVersionAndStatesTheLimits() {
        val rows =
            listOf(
                row("quic_version_v1", """{"status":"TEST_NOT_ANSWERED","version":"V1",$limits}"""),
                row("quic_version_v2", """{"status":"UNSUPPORTED_BY_ENDPOINT","version":"V2",$limits}"""),
                row("quic_version_comparison", """{"status":"INCONCLUSIVE",$limits}"""),
                row("quic_version_residual", """{"status":"SUSPECTED","version":"V1","limits":["network_changed"]}"""),
            )
        composeRule.setContent { RipDpiTheme { QuicVersionResultsCard(rows) } }

        composeRule.onNodeWithText("QUIC v1: test SNI not answered, control answered").assertIsDisplayed()
        composeRule.onNodeWithText("QUIC v2: endpoint does not offer this version").assertIsDisplayed()
        composeRule.onNodeWithText("Version difference not determined").assertIsDisplayed()
        composeRule.onNodeWithText("Same socket: control unanswered after the test SNI").assertIsDisplayed()
        composeRule.onNodeWithText("Initial stage only: not a handshake or HTTP/3 result").assertIsDisplayed()
        composeRule.onNodeWithText("A new local port does not prove a separate NAT mapping").assertIsDisplayed()
        composeRule.onNodeWithText("The network changed after the run").assertIsDisplayed()
    }

    @Test
    fun unsupportedRunNamesTheReason() {
        val rows = listOf(row("quic_version_matrix", """{"status":"UNSUPPORTED","reason":"proxy_path"}"""))
        composeRule.setContent { RipDpiTheme { QuicVersionResultsCard(rows) } }

        composeRule
            .onNodeWithText("Not supported on this path · Works only on the direct network path")
            .assertIsDisplayed()
    }

    @Test
    fun inputCardFlagsEqualSnisAndTogglesTheResidualCell() {
        var edited: QuicVersionMatrixConfig? = null
        composeRule.setContent {
            RipDpiTheme {
                QuicVersionInputCard(
                    input = QuicVersionMatrixConfig("quic.example", null, "same.example", "same.example"),
                    enabled = true,
                    onInputChanged = { edited = it },
                )
            }
        }
        composeRule.onNodeWithText("Enter an endpoint and two different domain names").assertExists()
        composeRule.onNodeWithText("Same-socket check").performClick()
        assertEquals(1, edited?.residualVersion)
        assertNull(
            ProbeResult("quic_version_v1", "quic.example", "x", listOf(ProbeDetail(QuicVersionEvidenceKey, "not json")))
                .toQuicVersionRow(),
        )
    }
}
