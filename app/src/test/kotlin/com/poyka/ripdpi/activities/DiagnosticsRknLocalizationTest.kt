package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.diagnostics.rkn.RknCheckResult
import com.poyka.ripdpi.diagnostics.rkn.RknConfidence
import com.poyka.ripdpi.diagnostics.rkn.RknVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticsRknLocalizationTest {
    @Test
    fun `Chinese aggregate branches translate headline and explanatory note`() {
        val control = listOf(result(RknVerdict.OK))
        val scenarios =
            listOf(
                listOf(result(RknVerdict.DOWN)) to listOf(result(RknVerdict.DNS_BLOCK)),
                control to listOf(result(RknVerdict.TIMEOUT)),
                control to listOf(result(RknVerdict.OK)),
                control to listOf(result(RknVerdict.DNS_BLOCK)),
                control to listOf(result(RknVerdict.TLS_BLOCK, RknConfidence.MEDIUM)),
                control to listOf(result(RknVerdict.OK), result(RknVerdict.DNS_BLOCK)),
            )
        scenarios.forEach { (controls, tests) ->
            val model = model(controls, tests, "zh-CN")
            assertDiagnosticsChinese(model.headline)
            assertDiagnosticsChinese(model.confidenceNote)
        }
        val high = model(control, listOf(result(RknVerdict.DNS_BLOCK)), "zh-CN")
        assertTrue(high.confidenceNote.contains("1/1"))
    }

    @Test
    fun `Chinese verdict confidences translate likelihood while preserving custom evidence`() {
        RknVerdict.entries.forEach { verdict ->
            RknConfidence.entries.forEach { confidence ->
                val chinese = model(emptyList(), listOf(result(verdict, confidence)), "zh-CN")
                val english = model(emptyList(), listOf(result(verdict, confidence)), "en")
                val row = chinese.rows.single()
                assertEquals("example.com", row.name)
                assertEquals("custom raw evidence; TLS_ERROR_900", row.notes)
                assertEquals(english.rows.single().tone, row.tone)
                assertEquals(
                    english.rows
                        .single()
                        .verdict
                        .first(),
                    row.verdict.first(),
                )
                if (verdict != RknVerdict.DNS_BLOCK && verdict != RknVerdict.TLS_BLOCK) {
                    assertDiagnosticsChinese(row.verdict)
                }
                if (confidence == RknConfidence.MEDIUM && verdict == RknVerdict.DNS_BLOCK) {
                    assertDiagnosticsChinese(row.verdict)
                }
            }
        }
    }

    @Test
    fun `English RKN aggregate preserves conclusion and block label confidence`() {
        val model =
            model(
                listOf(result(RknVerdict.OK)),
                listOf(result(RknVerdict.TCP_RESET, RknConfidence.MEDIUM)),
                "en",
            )
        assertEquals("In blocked zone: medium confidence", model.headline)
        assertEquals("~ LIKELY TCP RESET", model.rows.last().verdict)
        assertEquals("✗ TCP RESET", model.blockTypes.single().label)
    }

    @Test
    fun `unknown RKN result retains raw fallback errors without translating identifiers`() {
        val target =
            result(RknVerdict.UNKNOWN).copy(
                notes = emptyList(),
                dnsError = "custom_dns_error_900",
                tcpError = "ECONN_EXAMPLE_42",
            )
        val row = model(emptyList(), listOf(target), "zh-CN").rows.single()
        assertDiagnosticsChinese(row.verdict)
        assertEquals("custom_dns_error_900; ECONN_EXAMPLE_42", row.notes)
    }

    private fun model(
        controls: List<RknCheckResult>,
        tests: List<RknCheckResult>,
        language: String,
    ) = buildRknBlockDiagnosisUiModel(
        RknDiagnosisRunResult(controls, tests, null, false, false),
        diagnosticsToolLocaleResolver(language),
    )

    private fun result(
        verdict: RknVerdict,
        confidence: RknConfidence = RknConfidence.HIGH,
    ) = RknCheckResult(
        name = "example.com",
        url = "https://example.com/custom",
        verdict = verdict,
        confidence = confidence,
        notes = listOf("custom raw evidence", "TLS_ERROR_900"),
    )
}
