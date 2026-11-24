package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSessionDetailUiFactory
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.ui.screens.diagnostics.state.toFatHeaderEvidence
import com.poyka.ripdpi.ui.screens.history.DiagnosticsDetailContent
import com.poyka.ripdpi.ui.screens.history.historyDiagnosticsDetail
import com.poyka.ripdpi.ui.screens.history.historyProbeResult
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class DiagnosticsFatHeaderEvidenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `short series keeps its final HEAD response and incomplete byte coverage in history`() {
        val result =
            historyProbeResult("inconclusive").copy(
                probeType = "tcp_fat_header",
                details =
                    listOf(
                        ProbeDetail("requestsPlanned", "1"),
                        ProbeDetail("requestsWritten", "1"),
                        ProbeDetail("responsesSeen", "1"),
                        ProbeDetail("bytesSent", "2048"),
                        ProbeDetail("bytesAtLastResponse", "2048"),
                        ProbeDetail("thresholdCovered", "false"),
                        ProbeDetail("failureStage", "coverage"),
                    ),
            )
        val detail = historyDiagnosticsDetail().copy(results = listOf(result))
        val factory =
            DiagnosticsSessionDetailUiFactory(DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication()))
        val ui = factory.toSessionDetailUiModel(detail, showSensitiveDetails = false)

        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DiagnosticsDetailContent(ui, matrixPairing = null, onCompareMatrixSession = {})
                }
            }
        }
        listOf(
            "TCP16 fat header",
            "Insufficient evidence",
            "HEAD requests written / planned: 1 / 1",
            "Final HEAD responses: 1",
            "Bytes written at last response: 2048",
            "Successful response after the byte target: No",
        ).forEach { text ->
            composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `write failure reports measured zero without inventing a last response`() {
        val result =
            historyProbeResult("inconclusive").copy(
                probeType = "tcp_fat_header",
                details =
                    listOf(
                        ProbeDetail("requestsPlanned", "16"),
                        ProbeDetail("requestsWritten", "0"),
                        ProbeDetail("responsesSeen", "0"),
                        ProbeDetail("bytesSent", "0"),
                        ProbeDetail("bytesAtLastResponse", "0"),
                        ProbeDetail("thresholdCovered", "false"),
                        ProbeDetail("failureStage", "request_write"),
                    ),
            )
        val strings = AndroidStringResolver(RuntimeEnvironment.getApplication())
        assertEquals(
            listOf(
                "HEAD requests written / planned: 0 / 16",
                "Final HEAD responses: 0",
                "Bytes written: 0",
                "Successful response after the byte target: No",
                "Failure stage: Writing HEAD request",
            ),
            result.toFatHeaderEvidence(strings),
        )
    }

    @Test
    fun `legacy result preserves old measurements with unknown coverage`() {
        val result =
            historyProbeResult("tcp_ok").copy(
                probeType = "tcp_fat_header",
                details = listOf(ProbeDetail("responsesSeen", "2"), ProbeDetail("bytesSent", "8192")),
            )
        val strings = AndroidStringResolver(RuntimeEnvironment.getApplication())
        assertEquals(
            listOf("Final HEAD responses: 2", "Bytes written: 8192", "Coverage unknown"),
            result.toFatHeaderEvidence(strings),
        )
    }
}
