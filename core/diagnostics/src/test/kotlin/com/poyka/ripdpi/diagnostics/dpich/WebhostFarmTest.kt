package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WebhostFarmTest {
    @Test
    fun discoverReturnsOnlyHostsWithSuccessfulTcpAndTlsProbe() =
        runTest {
            val farm =
                WebhostFarm(
                    probe =
                        FakeWebhostProbe(
                            mapOf(
                                "192.0.2.1" to
                                    WebhostProbeResult(
                                        tcpOk = true,
                                        tlsOk = true,
                                        tcpTimeMs = 10,
                                        tlsTimeMs = 20,
                                    ),
                                "192.0.2.2" to
                                    WebhostProbeResult(
                                        tcpOk = true,
                                        tlsOk = false,
                                        tcpTimeMs = 11,
                                        tlsTimeMs = 0,
                                    ),
                            ),
                        ),
                    random = Random(1),
                )

            val hosts = farm.discover(setOf(IpRange("192.0.2.0/30")), count = 4, workers = 1)

            assertEquals(listOf("192.0.2.1"), hosts.map { host -> host.ip })
            assertEquals(443, hosts.single().port)
            assertEquals(IpRange("192.0.2.0/30"), hosts.single().sourceSubnet)
            assertEquals(10, hosts.single().tcpTimeMs)
            assertEquals(20, hosts.single().tlsTimeMs)
        }

    @Test
    fun discoverStopsAtRequestedCount() =
        runTest {
            val probe =
                FakeWebhostProbe(
                    (1..6).associate { host ->
                        "198.51.100.$host" to
                            WebhostProbeResult(
                                tcpOk = true,
                                tlsOk = true,
                                tcpTimeMs = 1,
                                tlsTimeMs = 1,
                            )
                    },
                )
            val farm = WebhostFarm(probe = probe, random = Random(2))

            val hosts = farm.discover(setOf(IpRange("198.51.100.0/29")), count = 2, workers = 1)

            assertEquals(2, hosts.size)
            assertEquals(2, probe.probedIps.size)
        }

    @Test
    fun discoverHonorsMaxCandidateCap() =
        runTest {
            val probe =
                FakeWebhostProbe(
                    default =
                        WebhostProbeResult(
                            tcpOk = false,
                            tlsOk = false,
                            tcpTimeMs = 0,
                            tlsTimeMs = 0,
                        ),
                )
            val farm =
                WebhostFarm(
                    probe = probe,
                    random = Random(3),
                    maxCandidates = 5,
                )

            farm.discover(setOf(IpRange("203.0.113.0/24")), count = 10, workers = 1)

            assertEquals(5, probe.probedIps.size)
            assertEquals(5, probe.probedIps.toSet().size)
        }

    @Test
    fun discoverAttachesReverseMetadata() =
        runTest {
            val farm =
                WebhostFarm(
                    probe =
                        FakeWebhostProbe(
                            mapOf(
                                "192.0.2.1" to
                                    WebhostProbeResult(
                                        tcpOk = true,
                                        tlsOk = true,
                                        tcpTimeMs = 5,
                                        tlsTimeMs = 6,
                                    ),
                            ),
                        ),
                    metadata = FakeMetadata,
                    random = Random(1),
                )

            val host = farm.discover(setOf(IpRange("192.0.2.0/30")), count = 1, workers = 1).single()

            assertEquals(64500, host.asn)
            assertEquals("example-net", host.org)
        }

    @Test
    fun discoverRejectsInvalidRanges() =
        runTest {
            val farm = WebhostFarm(probe = FakeWebhostProbe(emptyMap()))

            val error =
                runCatching {
                    farm.discover(setOf(IpRange("not-a-cidr")), count = 1)
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
        }

    private class FakeWebhostProbe(
        private val responses: Map<String, WebhostProbeResult> = emptyMap(),
        private val default: WebhostProbeResult =
            WebhostProbeResult(
                tcpOk = false,
                tlsOk = false,
                tcpTimeMs = 0,
                tlsTimeMs = 0,
            ),
    ) : WebhostProbe {
        val probedIps = mutableListOf<String>()

        override suspend fun probe(
            ip: String,
            port: Int,
            sni: String?,
            tcpConnectTimeoutMs: Long,
            tlsHandshakeTimeoutMs: Long,
        ): WebhostProbeResult {
            probedIps += ip
            return responses[ip] ?: default
        }
    }

    private object FakeMetadata : SubnetMetadataLookup {
        override fun subnetsForCountry(countryCode: String): Set<IpRange> = emptySet()

        override fun subnetsForOrgTerm(term: String): Set<IpRange> = emptySet()

        override fun orgTermsForAsn(asn: Int): Set<String> = emptySet()

        override fun orgTermsForIp(ip: String): Set<String> =
            if (ip == "192.0.2.1") setOf("example-net") else emptySet()

        override fun subnetsForAsn(asn: Int): Set<IpRange> = emptySet()

        override fun asnForIp(ip: String): Int? = if (ip == "192.0.2.1") 64500 else null

        override fun subnetForIp(ip: String): IpRange? = null
    }
}
