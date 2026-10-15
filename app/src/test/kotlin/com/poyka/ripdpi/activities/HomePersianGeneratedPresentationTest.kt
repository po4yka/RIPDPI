package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.ScanPathMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePersianGeneratedPresentationTest {
    @Test
    fun `normal outcome warnings confidence and coverage reproject with retained raw evidence`() {
        val raw =
            homeOutcome().copy(
                headline = "Analysis complete",
                summary =
                    "Completed 10 of 12 diagnostics stages. 2 stages finished with failures or were unavailable. " +
                        "DNS issues were detected during the audit. " +
                        "Network changed during analysis — results may not reflect current network.",
                confidenceSummary = "Confidence high (87)",
                coverageSummary = "Matrix 76% · winners 100%",
            )
        val english = projectHome(raw, "en")
        val persian = projectHome(raw, "fa")
        val sheet = persian.analysisSheet!!
        listOf(sheet.headline, sheet.summary, sheet.confidenceSummary, sheet.coverageSummary).forEach(::assertPersian)
        assertEquals(sheet.headline, persian.latestAudit!!.headline)
        assertEquals(sheet.summary, persian.latestAudit.summary)
        assertFalse(sheet.summary.contains("DNS issues"))
        assertEquals(english, projectHome(raw, "en"))
        assertEquals("Confidence high (87)", raw.confidenceSummary)
    }

    @Test
    fun `all full and quick stages localize without changing custom stages or summaries`() {
        val stages =
            stages().map { (key, label) ->
                stage(key, label, "Scan completed with partial results · Scan time limit reached")
            }
        val custom = stage("custom", "Customer stage", "Customer result: DNS is healthy")
        val raw = homeOutcome().copy(stageSummaries = stages + custom)
        val sheet = projectHome(raw, "fa").analysisSheet!!
        sheet.stageSummaries.dropLast(1).forEachIndexed { index, value ->
            if (stages[index].stageKey !in setOf("http3", "ip_family")) assertPersian(value.label)
            assertPersian(value.summary)
            assertFalse(value.summary.contains("Scan time limit"))
        }
        assertEquals("Customer stage", sheet.stageSummaries.last().label)
        assertEquals(custom.summary, sheet.stageSummaries.last().summary)
        assertEquals(stages.map { it.stageLabel } + custom.stageLabel, raw.stageSummaries.map { it.stageLabel })
    }

    @Test
    fun `recommendation and applied labels localize generated values and preserve identifiers`() {
        val raw =
            homeOutcome().copy(
                recommendationSummary =
                    "Detected TCP RST injection on 2 TCP probe(s) with 1 blocked service(s). " +
                        "Recommend switching to fake packets with adaptive TTL.",
                appliedSettings =
                    listOf(
                        DiagnosticsAppliedSetting("TCP/TLS lane", "TLS record split"),
                        DiagnosticsAppliedSetting("QUIC lane", "Quic burst"),
                        DiagnosticsAppliedSetting("Resolver", "https://dns.example/dns-query"),
                        DiagnosticsAppliedSetting("Protocol", "DOH"),
                        DiagnosticsAppliedSetting("WARP routing", "Rules"),
                        DiagnosticsAppliedSetting("WARP scanner", "4 parallel · 500 ms max RTT"),
                        DiagnosticsAppliedSetting("Customer setting", "Custom value"),
                    ),
            )
        val english = projectHome(raw, "en")
        val persian = projectHome(raw, "fa")
        val sheet = persian.analysisSheet!!
        assertPersian(sheet.recommendationSummary)
        assertEquals(sheet.recommendationSummary, persian.latestAudit!!.recommendationSummary)
        sheet.appliedSettings.dropLast(1).forEach { assertPersian(it.label) }
        listOf(0, 1, 4, 5).forEach { assertPersian(sheet.appliedSettings[it].value) }
        assertEquals(raw.appliedSettings[2].value, sheet.appliedSettings[2].value)
        assertEquals("DOH", sheet.appliedSettings[3].value)
        assertEquals(raw.appliedSettings.last(), sheet.appliedSettings.last())
        assertEquals(english, projectHome(raw, "en"))
    }

    @Test
    fun `resolver rationale translates template while retaining resolver protocol and IP`() {
        val rationale =
            "UDP DNS showed dns_mismatch while Custom Resolver returned matching encrypted answers over " +
                "DOH on 2 probe(s) with 33 ms average latency. Bootstrap reachability validated on 1 probe(s). " +
                "This network already preferred this encrypted DNS path. Avoided bootstrap IPs " +
                "blocked by DPI: 192.0.2.8."
        val raw = homeOutcome().copy(recommendationSummary = rationale)
        val projected = projectHome(raw, "fa").analysisSheet!!.recommendationSummary!!
        assertPersian(projected)
        listOf("dns_mismatch", "Custom Resolver", "DOH", "192.0.2.8").forEach { assertTrue(projected.contains(it)) }
        assertFalse(projected.contains("encrypted answers"))
        assertEquals(rationale, raw.recommendationSummary)
        assertEquals(
            "UDP DNS showed dns_mismatch while Custom Resolver returned matching encrypted answers over " +
                "DOH. Probe count: 2; average latency: 33 ms. Bootstrap reachability validated. Probe count: 1. " +
                "This network already preferred this encrypted DNS path. Avoided bootstrap IPs " +
                "blocked by DPI: 192.0.2.8.",
            projectHome(raw, "en").analysisSheet!!.recommendationSummary,
        )
    }

    @Test
    fun `unknown outcome recommendation and generated-looking custom setting remain raw`() {
        val raw =
            homeOutcome().copy(
                headline = "Customer result",
                summary = "Customer summary with Analysis complete inside",
                recommendationSummary = "Customer recommends a custom route",
                confidenceSummary = "Customer high priority",
                appliedSettings = listOf(DiagnosticsAppliedSetting("Customer", "Rules")),
            )
        val sheet = projectHome(raw, "fa").analysisSheet!!
        assertEquals(raw.headline, sheet.headline)
        assertEquals(raw.summary, sheet.summary)
        assertEquals(raw.recommendationSummary, sheet.recommendationSummary)
        assertEquals(raw.confidenceSummary, sheet.confidenceSummary)
        assertEquals(raw.appliedSettings, sheet.appliedSettings)
    }

    @Test
    fun `confidence grade stays semantic through locale changes and ignores custom high prose`() {
        listOf(
            "low" to HomeConfidenceLevel.LOW,
            "medium" to HomeConfidenceLevel.MEDIUM,
            "high" to HomeConfidenceLevel.HIGH,
        ).forEach { (level, grade) ->
            val raw = homeOutcome().copy(confidenceSummary = "Confidence $level (87)")
            val english = projectHome(raw, "en").analysisSheet!!
            val persian = projectHome(raw, "fa").analysisSheet!!
            assertEquals(grade, english.confidenceLevel)
            assertEquals(grade, persian.confidenceLevel)
            assertEquals(english, projectHome(raw, "en").analysisSheet)
            assertTrue(persian.confidenceSummary!!.contains("۸۷"))
        }
        val unknown = homeOutcome().copy(confidenceSummary = "Customer high priority")
        assertEquals(null, projectHome(unknown, "fa").analysisSheet!!.confidenceLevel)
    }

    @Test
    fun `generated detection progress summaries and scan counts localize without touching unknown details`() {
        val summaries =
            listOf(
                "Direct signs: Checking VPN transport and installed apps...",
                "Bypass: Popular ports: Port 1080 (25%)",
                "Detection signals were observed. 2 detection signals observed.",
                "No detection signals observed.",
                "2 completed · 1 healthy · 1 failed",
                "Raw-path runtime settlement inconclusive: cleanup_receipt_missing",
            )
        val raw =
            homeOutcome().copy(
                stageSummaries = summaries.map { stage("detection_signals", "Detection signals", it) },
            )
        val values = projectHome(raw, "fa").analysisSheet!!.stageSummaries.map { it.summary }
        values.forEach(::assertPersian)
        assertTrue(values[1].contains("1080"))
        assertTrue(values[1].contains("۲۵"))
        assertTrue(values[2].contains("۲"))
        assertTrue(values[5].contains("cleanup_receipt_missing"))
        val custom =
            raw.copy(
                stageSummaries = listOf(stage("detection_signals", "Detection signals", "Direct signs: Custom detail")),
            )
        assertTrue(
            projectHome(custom, "fa")
                .analysisSheet!!
                .stageSummaries
                .single()
                .summary
                .endsWith("Custom detail"),
        )
    }

    private fun stages() =
        listOf(
            "automatic_audit" to "Automatic audit",
            "detection_signals" to "Detection signals",
            "default_connectivity" to "Default diagnostics",
            "ip_family" to "IPv4, IPv6 and NAT64",
            "http3" to "HTTP/3",
            "pmtu" to "Active packet sizes / PMTU",
            "ru_throttling" to "Throttling check",
            "ru_circumvention" to "Sensitive services reachability",
            "dpi_full" to "DPI detector full",
            "path_comparison" to "Proxy vs direct path",
            "vpn_route_evidence" to "VPN route evidence",
            "dpi_strategy" to "DPI strategy probe",
        )

    private fun stage(
        key: String,
        label: String,
        summary: String,
    ) = DiagnosticsHomeCompositeStageSummary(
        stageKey = key,
        stageLabel = label,
        profileId = key,
        pathMode = ScanPathMode.RAW_PATH,
        status = DiagnosticsHomeCompositeStageStatus.COMPLETED,
        headline = "unused",
        summary = summary,
    )

    private fun assertPersian(value: String?) {
        assertTrue("Expected Persian presentation: $value", value.orEmpty().any { it in '\u0600'..'\u06ff' })
    }
}
