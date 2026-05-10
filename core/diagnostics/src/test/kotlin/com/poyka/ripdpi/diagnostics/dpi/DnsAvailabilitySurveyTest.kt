package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class DnsAvailabilitySurveyTest {
    @Test
    fun timeoutServerRecordedAsUnavailable() =
        runTest {
            val survey =
                survey(
                    servers = listOf(DnsServer("timeout", DnsServerType.UDP, "192.0.2.53")),
                    udpProbe = { _, _ -> throw SocketTimeoutException("timed out") },
                )

            val result = survey.run().single()

            assertEquals(0, result.availableDomains)
            assertEquals(1, result.totalDomains)
            assertNull(result.avgLatencyMs)
        }

    @Test
    fun successfulServerRecordsLatency() =
        runTest {
            val survey =
                survey(
                    servers = listOf(DnsServer("fast", DnsServerType.UDP, "8.8.8.8")),
                    udpProbe = { _, _ -> DnsProbeSample(DnsProbeStatus.OK, latencyMs = 24) },
                )

            val result = survey.run().single()

            assertEquals(1, result.availableDomains)
            assertEquals(24L, result.avgLatencyMs)
        }

    @Test
    fun dohWireFallbackToGetWhenPostReturns405() =
        runTest {
            val calls = mutableListOf<String>()
            val survey =
                survey(
                    servers = listOf(DnsServer("doh", DnsServerType.DOH_WIRE, "https://dns.example/dns-query")),
                    dohProbe =
                        object : DohWireAvailabilityProbe {
                            override suspend fun warmup(
                                server: DnsServer,
                                domain: String,
                                timeoutMs: Long,
                            ) = Unit

                            override suspend fun probe(
                                server: DnsServer,
                                domain: String,
                                timeoutMs: Long,
                            ): DnsProbeSample {
                                calls += "POST"
                                calls += "GET"
                                return DnsProbeSample(DnsProbeStatus.OK, latencyMs = 31)
                            }
                        },
                )

            val result = survey.run().single()

            assertEquals(listOf("POST", "GET"), calls)
            assertEquals(1, result.availableDomains)
            assertEquals(31L, result.avgLatencyMs)
        }

    @Test
    fun resultsSortedAvailableFirst() =
        runTest {
            val survey =
                survey(
                    servers =
                        listOf(
                            DnsServer("timeout", DnsServerType.UDP, "192.0.2.53"),
                            DnsServer("available", DnsServerType.UDP, "8.8.8.8"),
                        ),
                    udpProbe = { server, _ ->
                        if (server.name == "available") {
                            DnsProbeSample(DnsProbeStatus.OK, latencyMs = 12)
                        } else {
                            DnsProbeSample(DnsProbeStatus.TIMEOUT, latencyMs = null)
                        }
                    },
                )

            val results = survey.run()

            assertEquals("available", results.first().name)
            assertEquals("timeout", results.last().name)
        }

    @Test
    fun defaultServerListContainsTwentyOneServers() {
        assertEquals(21, DnsAvailabilitySurvey.defaultServers().size)
        assertTrue(DnsAvailabilitySurvey.defaultServers().count { it.type == DnsServerType.UDP } >= 9)
        assertTrue(DnsAvailabilitySurvey.defaultServers().count { it.type == DnsServerType.DOH_WIRE } >= 12)
    }

    private fun survey(
        servers: List<DnsServer>,
        udpProbe: UdpAvailabilityProbe = UdpAvailabilityProbe { _, _ -> DnsProbeSample(DnsProbeStatus.TIMEOUT) },
        dohProbe: DohWireAvailabilityProbe =
            object : DohWireAvailabilityProbe {
                override suspend fun warmup(
                    server: DnsServer,
                    domain: String,
                    timeoutMs: Long,
                ) = Unit

                override suspend fun probe(
                    server: DnsServer,
                    domain: String,
                    timeoutMs: Long,
                ): DnsProbeSample = DnsProbeSample(DnsProbeStatus.TIMEOUT)
            },
    ): DnsAvailabilitySurvey =
        DnsAvailabilitySurvey(
            servers = servers,
            domains = listOf("example.com"),
            udpProbe = udpProbe,
            dohProbe = dohProbe,
        )
}
