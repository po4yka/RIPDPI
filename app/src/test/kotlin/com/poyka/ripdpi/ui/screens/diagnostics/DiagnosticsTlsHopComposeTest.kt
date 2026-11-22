package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.TlsHopComparisonConfig
import com.poyka.ripdpi.diagnostics.TlsHopEvidenceKey
import com.poyka.ripdpi.diagnostics.TlsHopLocalKey
import com.poyka.ripdpi.ui.screens.diagnostics.state.toTlsHopResult
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class DiagnosticsTlsHopComposeTest {
    @get:Rule val composeRule = createComposeRule()

    private fun result(evidence: String) =
        ProbeResult(
            probeType = "tls_hop_comparison",
            target = "203.0.113.7",
            outcome = "tls_hop_divergence",
            details =
                listOf(
                    ProbeDetail(TlsHopEvidenceKey, evidence),
                    ProbeDetail(
                        TlsHopLocalKey,
                        """{"controlSni":"control.example","testSni":"test.example","attempts":""" +
                            """[{"ttl":5,"role":"TEST","repetition":0,"outcome":"RST","elapsedMs":41}]}""",
                    ),
                ),
        )

    @Test
    fun divergenceShowsIntervalAsObservationWithLimitsAndAttempts() {
        val model =
            requireNotNull(
                result(
                    """{"status":"DIVERGENCE","firstHop":5,"lastHop":5,"pathLength":9,""" +
                        """"limits":["server_hello_near_client"],"stop":"COMPLETED",""" +
                        """"controlAttempts":18,"testAttempts":18}""",
                ).toTlsHopResult(),
            )
        composeRule.setContent { RipDpiTheme { TlsHopResultsCard(model) } }

        composeRule.onNodeWithText("Test SNI draws a reaction at hops 5–5").assertIsDisplayed()
        composeRule.onNodeWithText("An observed hop interval, not an identified device").assertIsDisplayed()
        composeRule.onNodeWithText("Server answers at hop 9").assertIsDisplayed()
        composeRule.onNodeWithText("Control check passed").assertIsDisplayed()
        composeRule
            .onNodeWithText("ServerHello within 2 hops: a local proxy or interception answers")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Attempts: control 18, test 18").assertIsDisplayed()
        composeRule.onNodeWithText("Show attempts (1)").performClick()
        composeRule.onNodeWithText("TTL 5 · Test SNI · Reset · 41 ms").assertIsDisplayed()
    }

    @Test
    fun unsupportedRunNamesTheReasonAndClaimsNoInterval() {
        val model =
            requireNotNull(
                result("""{"status":"UNSUPPORTED","reason":"ttl_setter"}""").toTlsHopResult(),
            )
        composeRule.setContent { RipDpiTheme { TlsHopResultsCard(model) } }

        composeRule.onNodeWithText("Not supported on this path").assertIsDisplayed()
        composeRule.onNodeWithText("Control check: no data").assertIsDisplayed()
        composeRule.onNodeWithText("This device cannot set the packet TTL").assertIsDisplayed()
        composeRule.onNodeWithText("An observed hop interval, not an identified device").assertDoesNotExist()
    }

    @Test
    fun inputCardFlagsEqualSnis() {
        composeRule.setContent {
            RipDpiTheme {
                TlsHopInputCard(
                    input = TlsHopComparisonConfig("203.0.113.7", "same.example", "same.example"),
                    enabled = true,
                    onInputChanged = {},
                )
            }
        }
        composeRule.onNodeWithText("Enter an endpoint and two different domain names").assertExists()
        assertNull(result("not json").toTlsHopResult())
    }
}
