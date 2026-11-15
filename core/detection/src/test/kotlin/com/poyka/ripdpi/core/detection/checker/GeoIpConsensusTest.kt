package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker.GeoIpSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeoIpConsensusTest {
    @Test
    fun `one or two foreign country opinions cannot drive detection`() {
        for (count in 1..2) {
            val result = GeoIpChecker.evaluateConsensus((0 until count).map { snapshot(it, "DE") })
            assertFalse(result.needsReview)
            assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
            assertTrue(result.findings.any { it.description == "Country: N/A ()" })
            assertNotEquals(Verdict.DETECTED, verdictOnRussianNetwork(result))
        }
    }

    @Test
    fun `missing country capability does not supply a country opinion`() {
        val result = GeoIpChecker.evaluateConsensus(listOf(snapshot(0, "DE"), snapshot(1, "DE"), snapshot(2, "")))
        assertFalse(result.needsReview)
        assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
        assertTrue(result.findings.any { it.description == "GeoIP country opinions: 2/3" })
        assertNotEquals(Verdict.DETECTED, verdictOnRussianNetwork(result))
    }

    @Test
    fun `country tie stays unknown regardless of response index or order`() {
        val snapshots = listOf(snapshot(0, "RU"), snapshot(1, "RU"), snapshot(2, "DE"), snapshot(3, "DE"))
        for (opinions in listOf(snapshots, snapshots.reversed())) {
            val result = GeoIpChecker.evaluateConsensus(opinions)
            assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
            assertTrue(result.findings.any { it.description == "Country: N/A ()" })
            assertTrue(result.findings.any { it.description.startsWith("Conflicting providers: ") })
            assertNotEquals(Verdict.DETECTED, verdictOnRussianNetwork(result))
        }
    }

    @Test
    fun `a country plurality without strict majority stays unknown`() {
        val result = GeoIpChecker.evaluateConsensus(listOf(snapshot(0, "RU"), snapshot(1, "DE"), snapshot(2, "US")))
        assertTrue(result.findings.any { it.description == "Country: N/A ()" })
        assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
        assertNotEquals(Verdict.DETECTED, verdictOnRussianNetwork(result))
    }

    @Test
    fun `three usable opinions preserve an agreed country signal and its dissent`() {
        val result = GeoIpChecker.evaluateConsensus(listOf(snapshot(0, "DE"), snapshot(1, "DE"), snapshot(2, "RU")))
        assertTrue(result.needsReview)
        assertEquals(CheckExecutionStatus.COMPLETED, result.executionStatus)
        assertTrue(result.findings.any { it.description == "Country: Germany (DE)" })
        assertTrue(result.findings.any { it.description == "Conflicting providers: provider2" })
        assertEquals(Verdict.DETECTED, verdictOnRussianNetwork(result))
    }

    @Test
    fun `ASN disagreement preserves unknown and does not borrow organization data`() {
        val result =
            GeoIpChecker.evaluateConsensus(
                listOf(
                    snapshot(0, "RU", "AS13335"),
                    snapshot(1, "RU", "AS13335"),
                    snapshot(2, "RU", "AS15169"),
                    snapshot(3, "RU", "AS15169"),
                ),
            )
        assertTrue(result.findings.any { it.description == "ASN: N/A" })
        assertTrue(result.findings.any { it.description == "Org: N/A" })
        assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
    }

    @Test
    fun `ASN consensus compares normalized autonomous system numbers`() {
        val result =
            GeoIpChecker.evaluateConsensus(
                listOf(
                    snapshot(0, "RU", "AS13335 Cloudflare"),
                    snapshot(1, "RU", "13335"),
                    snapshot(2, "RU", "AS15169"),
                ),
            )
        assertTrue(result.findings.any { it.description == "ASN: AS13335" })
        assertTrue(result.findings.any { it.description == "Conflicting providers: provider2" })
    }

    @Test
    fun `unknown country codes cannot create foreign country evidence`() {
        val result = GeoIpChecker.evaluateConsensus(List(3) { snapshot(it, "ZZ") })
        assertFalse(result.needsReview)
        assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
        assertTrue(result.findings.any { it.description == "GeoIP country opinions: 0/3" })
        assertNotEquals(Verdict.DETECTED, verdictOnRussianNetwork(result))
    }

    private fun snapshot(
        index: Int,
        countryCode: String,
        asn: String = "AS1234",
    ) = GeoIpSnapshot(
        ip = "8.8.8.8",
        country = if (countryCode == "DE") "Germany" else "Russia",
        countryCode = countryCode,
        isp = "ISP",
        org = "Org",
        asn = asn,
        isProxy = false,
        isHosting = false,
        providerName = "provider$index",
        fetchedAtIndex = index,
    )

    private fun verdictOnRussianNetwork(geoIp: CategoryResult): Verdict {
        val empty = CategoryResult("empty", false, emptyList())
        val bypass =
            BypassResult(
                proxyEndpoint = null,
                directIp = null,
                proxyIp = null,
                xrayApiScanResult = null,
                findings = emptyList(),
                detected = false,
            )
        return VerdictEngine.evaluate(
            geoIp,
            empty,
            empty,
            empty.copy(findings = listOf(Finding("network_mcc_ru:true"))),
            bypass,
        )
    }
}
