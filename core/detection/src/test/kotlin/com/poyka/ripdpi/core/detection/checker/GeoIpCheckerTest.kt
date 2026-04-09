package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker.GeoIpSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeoIpCheckerTest {
    @Test
    fun `Russian IP not detected`() {
        val result =
            GeoIpChecker.evaluate(
                GeoIpSnapshot(
                    ip = "1.2.3.4",
                    country = "Russia",
                    countryCode = "RU",
                    isp = "ISP",
                    org = "Org",
                    asn = "AS1234",
                    isProxy = false,
                    isHosting = false,
                ),
            )
        assertFalse(result.detected)
        assertFalse(result.needsReview)
    }

    @Test
    fun `foreign non-hosting IP needs review`() {
        val result =
            GeoIpChecker.evaluate(
                GeoIpSnapshot(
                    ip = "5.6.7.8",
                    country = "Germany",
                    countryCode = "DE",
                    isp = "ISP",
                    org = "Org",
                    asn = "AS5678",
                    isProxy = false,
                    isHosting = false,
                ),
            )
        assertFalse(result.detected)
        assertTrue(result.needsReview)
    }

    @Test
    fun `hosting IP detected`() {
        val result =
            GeoIpChecker.evaluate(
                GeoIpSnapshot(
                    ip = "9.10.11.12",
                    country = "Netherlands",
                    countryCode = "NL",
                    isp = "Hosting",
                    org = "VPS",
                    asn = "AS9999",
                    isProxy = false,
                    isHosting = true,
                ),
            )
        assertTrue(result.detected)
        assertTrue(result.evidence.any { it.source == EvidenceSource.GEO_IP })
    }

    @Test
    fun `proxy IP detected`() {
        val result =
            GeoIpChecker.evaluate(
                GeoIpSnapshot(
                    ip = "13.14.15.16",
                    country = "Finland",
                    countryCode = "FI",
                    isp = "ISP",
                    org = "VPN Provider",
                    asn = "AS1111",
                    isProxy = true,
                    isHosting = false,
                ),
            )
        assertTrue(result.detected)
    }

    @Test
    fun `evaluate from JSON parses correctly`() {
        val json =
            JSONObject().apply {
                put("status", "success")
                put("query", "1.2.3.4")
                put("country", "Russia")
                put("countryCode", "RU")
                put("isp", "ISP")
                put("org", "Org")
                put("as", "AS1234")
                put("proxy", false)
                put("hosting", false)
            }
        val result = GeoIpChecker.evaluate(json)
        assertFalse(result.detected)
        assertEquals("GeoIP", result.name)
    }
}
