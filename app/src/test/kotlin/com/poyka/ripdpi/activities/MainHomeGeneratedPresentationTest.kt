package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.HomeBufferbloatGrade
import com.poyka.ripdpi.diagnostics.HomeBufferbloatResult
import com.poyka.ripdpi.diagnostics.HomeDnsCharacterization
import com.poyka.ripdpi.diagnostics.HomeDnsResolverClass
import com.poyka.ripdpi.diagnostics.HomeNetworkCharacterSummary
import com.poyka.ripdpi.diagnostics.HomeRoutingSanityFinding
import com.poyka.ripdpi.diagnostics.HomeRoutingSanitySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainHomeGeneratedPresentationTest {
    @Test
    fun `frozen network facts reproject in Arabic and back without changing raw evidence`() {
        val outcome =
            homeOutcome().copy(
                networkCharacter =
                    HomeNetworkCharacterSummary(
                        transport = "cellular",
                        operatorOrSsid = "Carrier 25001",
                        publicIp = "2001:4860::8888",
                        captivePortalDetected = true,
                        ipv6Reachable = false,
                        notes = listOf("Active network is a VPN", "Network reports as metered", "Custom VPN note"),
                    ),
                bufferbloat = HomeBufferbloatResult(HomeBufferbloatGrade.UNKNOWN),
            )
        val original = projectHome(outcome, "en").analysisSheet!!
        val arabic = projectHome(outcome, "ar").analysisSheet!!
        assertEquals(original, projectHome(outcome, "en").analysisSheet)
        assertEquals(outcome.networkCharacter!!.notes.take(2), original.networkCharacterNotes.take(2))
        arabic.networkCharacterNotes.take(2).forEach(::assertArabic)
        assertEquals("Custom VPN note", arabic.networkCharacterNotes.last())
        assertEquals("cellular", arabic.networkCharacterRows[0].value)
        assertTrue(arabic.networkCharacterRows[1].value.contains("25001"))
        assertArabic(arabic.networkCharacterRows[1].value)
        assertEquals("2001:4860::8888", arabic.networkCharacterRows[2].value)
        assertArabic(arabic.networkCharacterRows[3].value)
        assertArabic(arabic.networkCharacterRows[4].value)
        assertFalse(arabic.bufferbloatSummary!!.contains("UNKNOWN"))
        assertEquals("Carrier 25001", outcome.networkCharacter!!.operatorOrSsid)
        val wifi =
            outcome.copy(
                networkCharacter = HomeNetworkCharacterSummary(transport = "wifi", operatorOrSsid = "Carrier 25001"),
            )
        assertEquals("Carrier 25001", projectHome(wifi, "ar").analysisSheet!!.networkCharacterRows[1].value)
        val grade = projectHome(outcome.copy(bufferbloat = HomeBufferbloatResult(HomeBufferbloatGrade.A)), "ar")
        assertTrue(grade.analysisSheet!!.bufferbloatSummary!!.contains("A"))
    }

    @Test
    fun `DNS wrappers retain endpoint and CDN uncertainty while custom notes remain exact`() {
        val endpoint = "https://dns.example/DNS%20comparison?text=unreachable"
        val raw =
            listOf(
                "DoH endpoint $endpoint unreachable",
                "DNS comparison is incomplete",
                "System and DoH answers differ; location and CDN routing can cause this",
                "Custom DoH endpoint warning",
            )
        val outcome =
            homeOutcome().copy(
                dnsCharacterization =
                    HomeDnsCharacterization(
                        resolverClass = HomeDnsResolverClass.UNKNOWN,
                        dohEndpoint = endpoint,
                        notes = raw,
                    ),
            )
        val arabic = projectHome(outcome, "ar").analysisSheet!!.dnsCharacterizationNotes
        arabic.take(3).forEach(::assertArabic)
        assertTrue(arabic[0].contains(endpoint))
        assertTrue(arabic[2].contains("CDN"))
        assertEquals(raw.last(), arabic.last())
        assertEquals(raw, projectHome(outcome, "en").analysisSheet!!.dnsCharacterizationNotes)
        assertEquals(raw, outcome.dnsCharacterization!!.notes)
    }

    @Test
    fun `routing wrapper and known severity localize while raw method and package remain exact`() {
        val method = "transport_vpn, tun0_interface (custom)"
        val description = "VPN-detector app — verify per-app routing override (detection: $method)"
        val outcome =
            homeOutcome().copy(
                routingSanity =
                    HomeRoutingSanitySummary(
                        findings =
                            listOf(
                                HomeRoutingSanityFinding("org.example.app", "high", description),
                                HomeRoutingSanityFinding("org.example.custom", "custom", "Custom routing description"),
                            ),
                    ),
            )
        val rows = projectHome(outcome, "ar").analysisSheet!!.routingSanityFindings
        assertArabic(rows[0].value)
        assertTrue(rows[0].value.contains(method))
        assertEquals("org.example.app", rows[0].label)
        assertEquals("custom: Custom routing description", rows[1].value)
        assertEquals("high: $description", projectHome(outcome, "en").analysisSheet!!.routingSanityFindings[0].value)
    }

    @Test
    fun `scope unavailable localizes in latest card and sheet and preserves custom or partial records`() {
        val summary =
            "Network changed during analysis or its original scope could not be verified. " +
                "Repeat analysis on a stable network."
        val outcome =
            homeOutcome().copy(
                fingerprintHash = null,
                actionable = false,
                headline = "Network scope unavailable",
                summary = summary,
            )
        val english = projectHome(outcome, "en")
        val arabic = projectHome(outcome, "ar")
        assertArabic(arabic.latestAudit!!.headline)
        assertArabic(arabic.latestAudit.summary)
        assertEquals(arabic.latestAudit.headline, arabic.analysisSheet!!.headline)
        assertEquals(arabic.latestAudit.summary, arabic.analysisSheet.summary)
        assertEquals(english, projectHome(outcome, "en"))
        val custom = outcome.copy(summary = "Custom explanation")
        assertEquals("Network scope unavailable", projectHome(custom, "ar").latestAudit!!.headline)
        assertEquals("Custom explanation", projectHome(custom, "ar").analysisSheet!!.summary)
    }
}
