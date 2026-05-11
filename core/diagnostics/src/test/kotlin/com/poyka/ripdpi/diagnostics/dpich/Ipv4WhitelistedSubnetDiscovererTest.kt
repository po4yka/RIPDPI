package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class Ipv4WhitelistedSubnetDiscovererTest {
    private val temp = TemporaryFolder()

    @Test
    fun cacheFetchesConfiguredAsnsAndPersistsOnly24Prefixes() =
        runTest {
            temp.create()
            val ripe =
                RecordingRipeStatSource(
                    13238 to listOf("203.0.113.0/24", "198.51.100.0/22"),
                    28709 to listOf("192.0.2.0/24"),
                )
            val cache = SubnetsCache(temp.newFile("whitelisted_subnets_cache.json"))
            val discoverer =
                Ipv4WhitelistedSubnetDiscoverer(
                    asns =
                        listOf(
                            WhitelistAsn("Yandex", 13238),
                            WhitelistAsn("VK", 28709),
                        ),
                    ripeStat = ripe,
                    cache = cache,
                    aliveProbe = FakeSubnetAliveProbe(emptySet()),
                )

            val progress = discoverer.cacheSubnets(WhitelistedSubnetConfig(only24Prefix = true)).toList()

            assertEquals(listOf(13238, 28709), ripe.requestedAsns)
            assertEquals(listOf("203.0.113.0/24"), progress.first().cachedCidrs)
            assertEquals(listOf("192.0.2.0/24"), progress.last().cachedCidrs)
            assertEquals(
                listOf(
                    CachedWhitelistSubnet("Yandex", 13238, "203.0.113.0/24"),
                    CachedWhitelistSubnet("VK", 28709, "192.0.2.0/24"),
                ),
                cache.load(),
            )
        }

    @Test
    fun checkCachedSubnetsAppliesAliveThreshold() =
        runTest {
            temp.create()
            val cache = SubnetsCache(temp.newFile("cache.json"))
            cache.save(listOf(CachedWhitelistSubnet("Yandex", 13238, "203.0.113.0/29")))
            val discoverer =
                Ipv4WhitelistedSubnetDiscoverer(
                    asns = emptyList(),
                    ripeStat = RecordingRipeStatSource(),
                    cache = cache,
                    aliveProbe =
                        FakeSubnetAliveProbe(
                            aliveIps = setOf("203.0.113.1", "203.0.113.2"),
                        ),
                    random = Random(1),
                )

            val progress =
                discoverer
                    .checkCachedSubnets(
                        WhitelistedSubnetConfig(
                            subnetSampleSize = 6,
                            subnetAliveMin = 3,
                        ),
                    ).toList()

            assertEquals(1, progress.size)
            assertEquals(false, progress.single().result.whitelisted)
            assertEquals(2, progress.single().result.aliveCount)
            assertEquals(6, progress.single().result.aliveSampled)
        }

    @Test
    fun checkCachedSubnetsMarksWhitelistedAtThresholdAndFormatsCsv() =
        runTest {
            temp.create()
            val cache = SubnetsCache(temp.newFile("cache.json"))
            cache.save(listOf(CachedWhitelistSubnet("VK", 28709, "192.0.2.0/29")))
            val discoverer =
                Ipv4WhitelistedSubnetDiscoverer(
                    asns = emptyList(),
                    ripeStat = RecordingRipeStatSource(),
                    cache = cache,
                    aliveProbe =
                        FakeSubnetAliveProbe(
                            aliveIps = setOf("192.0.2.1", "192.0.2.2"),
                            tlsPostHandshakeFailures = setOf("192.0.2.3"),
                        ),
                    random = Random(2),
                )

            val result =
                discoverer
                    .checkCachedSubnets(
                        WhitelistedSubnetConfig(
                            subnetSampleSize = 6,
                            subnetAliveMin = 3,
                        ),
                    ).toList()
                    .single()
                    .result

            assertEquals(true, result.whitelisted)
            assertEquals(3, result.aliveCount)
            assertEquals(
                "provider,cidr,alive_count,whitelisted\nVK,192.0.2.0/29,3,true\n",
                listOf(result).toWhitelistedSubnetCsv(),
            )
        }

    private class RecordingRipeStatSource(
        private vararg val responses: Pair<Int, List<String>>,
    ) : RipeStatPrefixSource {
        val requestedAsns = mutableListOf<Int>()

        override suspend fun announcedPrefixes(asn: Int): List<String> {
            requestedAsns += asn
            return responses.toMap().getValue(asn)
        }
    }

    private class FakeSubnetAliveProbe(
        private val aliveIps: Set<String>,
        private val tlsPostHandshakeFailures: Set<String> = emptySet(),
    ) : SubnetAliveProbe {
        override suspend fun probe(
            ip: String,
            timeoutMs: Long,
        ): SubnetAliveProbeResult =
            when (ip) {
                in aliveIps -> SubnetAliveProbeResult.Alive
                in tlsPostHandshakeFailures -> SubnetAliveProbeResult.TlsPostHandshakeFailure
                else -> SubnetAliveProbeResult.Unreachable
            }
    }
}
