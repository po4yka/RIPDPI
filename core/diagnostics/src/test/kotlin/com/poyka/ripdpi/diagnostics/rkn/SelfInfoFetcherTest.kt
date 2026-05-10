package com.poyka.ripdpi.diagnostics.rkn

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class SelfInfoFetcherTest {
    @Test
    fun disabledByDefaultReturnsNullNoNetworkCall() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val fetcher = fetcher(server)

                val result = fetcher.fetch(enabled = false, privacyModeEnabled = false)

                assertNull(result)
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun enabledCallsIpInfoIo() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "ip":"1.2.3.4",
                              "org":"AS12389 Rostelecom",
                              "city":"Moscow",
                              "region":"Moscow",
                              "country":"RU"
                            }
                            """.trimIndent(),
                        ).build(),
                )
                server.start()

                val result = fetcher(server).fetch(enabled = true, privacyModeEnabled = false)

                assertEquals(
                    SelfInfoResult(
                        ip = "1.2.3.4",
                        asn = "AS12389",
                        org = "Rostelecom",
                        city = "Moscow",
                        region = "Moscow",
                        country = "RU",
                        source = "ipinfo.io",
                    ),
                    result,
                )
            }
        }

    @Test
    fun ipInfoFailureFallsBackToIfconfig() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(503).build())
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "ip":"5.6.7.8",
                              "asn":"AS4242",
                              "asn_org":"Fallback ISP",
                              "city":"Tbilisi",
                              "region_name":"Tbilisi",
                              "country_iso":"GE"
                            }
                            """.trimIndent(),
                        ).build(),
                )
                server.start()

                val result = fetcher(server).fetch(enabled = true, privacyModeEnabled = false)

                assertEquals("5.6.7.8", result?.ip)
                assertEquals("AS4242", result?.asn)
                assertEquals("Fallback ISP", result?.org)
                assertEquals("ifconfig.co", result?.source)
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun bothEndpointsFailReturnsNull() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(503).build())
                server.enqueue(MockResponse.Builder().code(503).build())
                server.start()

                assertNull(fetcher(server).fetch(enabled = true, privacyModeEnabled = false))
            }
        }

    @Test
    fun asnExtractedFromOrgWhenPrefixMatches() {
        val parsed = SelfInfoFetcher.parseOrg("AS12389 Rostelecom")

        assertEquals("AS12389", parsed.asn)
        assertEquals("Rostelecom", parsed.org)
    }

    @Test
    fun asnNullWhenFormatDoesNotMatch() {
        val parsed = SelfInfoFetcher.parseOrg("Some Provider Inc")

        assertNull(parsed.asn)
        assertEquals("Some Provider Inc", parsed.org)
    }

    @Test
    fun privacyModeOverridesOptIn() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val result = fetcher(server).fetch(enabled = true, privacyModeEnabled = true)

                assertNull(result)
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun timeoutReturnsNullNoException() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .headersDelay(1, TimeUnit.SECONDS)
                        .body("{}")
                        .build(),
                )
                server.enqueue(MockResponse.Builder().code(503).build())
                server.start()

                val result =
                    fetcher(
                        server = server,
                        timeoutMs = 100,
                    ).fetch(enabled = true, privacyModeEnabled = false)

                assertNull(result)
            }
        }

    private fun fetcher(
        server: MockWebServer,
        timeoutMs: Long = 5_000,
    ): SelfInfoFetcher =
        SelfInfoFetcher(
            clientBuilder = { customize ->
                OkHttpClient
                    .Builder()
                    .apply(customize)
                    .build()
            },
            config =
                SelfInfoConfig(
                    primaryUrl = server.url("/ipinfo").toString(),
                    fallbackUrl = server.url("/ifconfig").toString(),
                    timeoutMs = timeoutMs,
                ),
        )
}
