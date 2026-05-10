package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker.GeoIpProvider
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker.GeoIpProviderClient
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker.GeoIpSnapshot
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
            JSONObject(
                """
                {
                  "status": "success",
                  "query": "1.2.3.4",
                  "country": "Russia",
                  "countryCode": "RU",
                  "isp": "ISP",
                  "org": "Org",
                  "as": "AS1234",
                  "proxy": false,
                  "hosting": false
                }
                """.trimIndent(),
            )
        val result = GeoIpChecker.evaluate(json)
        assertFalse(result.detected)
        assertEquals("GeoIP", result.name)
    }

    @Test
    fun `evaluate from current HTTPS JSON parses nested connection and security fields`() {
        val json =
            JSONObject(
                """
                {
                  "success": true,
                  "ip": "8.8.8.8",
                  "country": "United States",
                  "country_code": "US",
                  "connection": {
                    "asn": 15169,
                    "isp": "Google LLC",
                    "org": "Google LLC"
                  },
                  "security": {
                    "vpn": true,
                    "hosting": true
                  }
                }
                """.trimIndent(),
            )

        val result = GeoIpChecker.evaluate(json)

        assertTrue(result.detected)
        assertTrue(result.findings.any { it.description.contains("Google LLC") })
    }

    @Test
    fun `evaluate from provider JSON shapes parses nested risk and privacy fields`() {
        val providerResponses =
            listOf(
                """
                {
                  "ip": "5.6.7.8",
                  "location": { "country": "Germany", "country_code": "DE" },
                  "isp": { "asn": "1234", "isp": "Example ISP", "org": "Example Org" },
                  "risk": { "is_proxy": true, "is_datacenter": true }
                }
                """.trimIndent(),
                """
                {
                  "ip": "5.6.7.8",
                  "geo": { "country": "Germany", "country_code": "DE" },
                  "network": { "asn": 1234, "isp": "Example ISP", "org": "Example Org" },
                  "privacy": { "vpn": true, "hosting": true }
                }
                """.trimIndent(),
                """
                {
                  "ip": "5.6.7.8",
                  "location": { "country": "Germany", "country_code": "DE" },
                  "network": { "asn": "1234", "org": "Example Org", "radar": "proxy" },
                  "security": { "usage_type": "DCH", "threat_lists": ["vpn"] }
                }
                """.trimIndent(),
            )

        providerResponses.forEach { body ->
            val result = GeoIpChecker.evaluate(JSONObject(body))

            assertTrue(result.detected)
            assertTrue(result.findings.any { it.description == "Country: Germany (DE)" })
            assertTrue(result.findings.any { it.description == "IP belongs to hosting provider: yes" })
            assertTrue(result.findings.any { it.description == "IP in known proxy/VPN database: yes" })
        }
    }

    @Test
    fun `majority proxy flag true when three of five providers agree`() {
        val result =
            GeoIpChecker.evaluateConsensus(
                listOf(
                    snapshot("ipapi.is", isProxy = true, isHosting = false),
                    snapshot("iplocate.io", isProxy = true, isHosting = false),
                    snapshot("ipquery.io", isProxy = true, isHosting = false),
                    snapshot("iplookup.it", isProxy = false, isHosting = false),
                    snapshot("ipbot.com", isProxy = false, isHosting = false),
                ),
            )

        assertTrue(result.detected)
        assertTrue(result.findings.any { it.description == "Provider count: 5" })
        assertTrue(result.findings.any { it.description == "Conflicting providers: iplookup.it, ipbot.com" })
        assertTrue(result.findings.any { it.description == "IP in known proxy/VPN database: yes" })
    }

    @Test
    fun `majority proxy flag false when only two of five providers agree`() {
        val result =
            GeoIpChecker.evaluateConsensus(
                listOf(
                    snapshot("ipapi.is", isProxy = true, isHosting = false),
                    snapshot("iplocate.io", isProxy = true, isHosting = false),
                    snapshot("ipquery.io", isProxy = false, isHosting = false),
                    snapshot("iplookup.it", isProxy = false, isHosting = false),
                    snapshot("ipbot.com", isProxy = false, isHosting = false),
                ),
            )

        assertFalse(result.detected)
        assertTrue(result.findings.any { it.description == "IP in known proxy/VPN database: no" })
    }

    @Test
    fun `majority hosting flag true when three of five providers agree`() {
        val result =
            GeoIpChecker.evaluateConsensus(
                listOf(
                    snapshot("ipapi.is", isProxy = false, isHosting = true),
                    snapshot("iplocate.io", isProxy = false, isHosting = true),
                    snapshot("ipquery.io", isProxy = false, isHosting = true),
                    snapshot("iplookup.it", isProxy = false, isHosting = false),
                    snapshot("ipbot.com", isProxy = false, isHosting = false),
                ),
            )

        assertTrue(result.detected)
        assertTrue(result.findings.any { it.description == "IP belongs to hosting provider: yes" })
    }

    @Test
    fun `provider count equals successful responses`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers =
                AppCoroutineDispatchers(
                    default = dispatcher,
                    io = dispatcher,
                    main = dispatcher,
                )
            val client =
                FakeGeoIpProviderClient(
                    failures = setOf("iplocate.io", "iplookup.it"),
                )

            val result = GeoIpChecker.check(dispatchers = dispatchers, client = client)

            assertTrue(result.findings.any { it.description == "Provider count: 3" })
            assertEquals(1, client.seedCalls)
            assertEquals(5, client.providerCalls.count { it.second == "5.6.7.8" })
        }

    @Test
    fun `country tie uses most recent successful response`() {
        val result =
            GeoIpChecker.evaluateConsensus(
                listOf(
                    snapshot("ipapi.is", countryCode = "RU", fetchedAtIndex = 0),
                    snapshot("iplocate.io", countryCode = "RU", fetchedAtIndex = 1),
                    snapshot("ipquery.io", countryCode = "DE", fetchedAtIndex = 2),
                    snapshot("iplookup.it", countryCode = "DE", fetchedAtIndex = 3),
                ),
            )

        assertTrue(result.findings.any { it.description == "Country: Germany (DE)" })
    }

    private class FakeGeoIpProviderClient(
        private val failures: Set<String> = emptySet(),
    ) : GeoIpProviderClient {
        var seedCalls = 0
        val providerCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun fetchSeedIp(): String {
            seedCalls += 1
            return "5.6.7.8"
        }

        override suspend fun fetchProvider(
            provider: GeoIpProvider,
            ip: String,
        ): GeoIpSnapshot {
            providerCalls += provider.name to ip
            if (provider.name in failures) {
                error("failed ${provider.name}")
            }
            return GeoIpCheckerTest.snapshot(provider.name, ip = ip)
        }
    }

    private companion object {
        fun snapshot(
            provider: String,
            ip: String = "5.6.7.8",
            country: String = if (provider == "ipquery.io" || provider == "iplookup.it") "Germany" else "Russia",
            countryCode: String = if (provider == "ipquery.io" || provider == "iplookup.it") "DE" else "RU",
            asn: String = "AS1234",
            isProxy: Boolean = false,
            isHosting: Boolean = false,
            fetchedAtIndex: Int = 0,
        ): GeoIpSnapshot =
            GeoIpSnapshot(
                ip = ip,
                country = country,
                countryCode = countryCode,
                isp = "ISP $provider",
                org = "Org $provider",
                asn = asn,
                isProxy = isProxy,
                isHosting = isHosting,
                providerName = provider,
                fetchedAtIndex = fetchedAtIndex,
            )
    }
}
