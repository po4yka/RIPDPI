package com.poyka.ripdpi.core.detection.consensus

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.Finding
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpConsensusBuilderTest {
    @Test
    fun channelConflictDetectedWhenSameChannelReportsTwoIps() {
        val result =
            IpConsensusBuilder.build(
                observations =
                    listOf(
                        IpObservation(IpConsensusChannel.GEO_IP, "1.1.1.1"),
                        IpObservation(IpConsensusChannel.GEO_IP, "2.2.2.2"),
                    ),
                asnResolver = fakeResolver(),
            )

        assertEquals(listOf(IpConsensusChannel.GEO_IP), result.channelConflicts.map { it.channel })
        assertEquals(EvidenceConfidence.HIGH, result.channelConflicts.single().confidence)
    }

    @Test
    fun crossChannelMismatchDetectedWhenRuAndNonRuDiffer() {
        val result =
            IpConsensusBuilder.build(
                observations =
                    listOf(
                        IpObservation(IpConsensusChannel.IP_COMPARISON_RU, "1.2.3.4"),
                        IpObservation(IpConsensusChannel.IP_COMPARISON_NON_RU, "5.6.7.8"),
                    ),
                asnResolver = fakeResolver(),
            )

        assertEquals(1, result.crossChannelMismatches.size)
        assertEquals(EvidenceConfidence.HIGH, result.crossChannelMismatches.single().confidence)
    }

    @Test
    fun noConflictWhenAllChannelsAgree() {
        val result =
            IpConsensusBuilder.build(
                observations =
                    listOf(
                        IpObservation(IpConsensusChannel.GEO_IP, "1.1.1.1"),
                        IpObservation(IpConsensusChannel.IP_COMPARISON_RU, "1.1.1.1"),
                        IpObservation(IpConsensusChannel.IP_COMPARISON_NON_RU, "1.1.1.1"),
                    ),
                asnResolver = fakeResolver(),
            )

        assertTrue(result.channelConflicts.isEmpty())
        assertTrue(result.crossChannelMismatches.isEmpty())
    }

    @Test
    fun warpIndicatorTrueWhenCloudflareAsnOnUnderlyingPath() {
        val result =
            IpConsensusBuilder.build(
                observations =
                    listOf(
                        IpObservation(IpConsensusChannel.BYPASS_DIRECT, "1.1.1.1"),
                        IpObservation(IpConsensusChannel.BYPASS_PROXY, "203.0.113.10"),
                    ),
                asnResolver =
                    fakeResolver(
                        "1.1.1.1" to IpAsnInfo("1.1.1.1", "13335", "US", "Cloudflare"),
                        "203.0.113.10" to IpAsnInfo("203.0.113.10", "64500", "NL", "VPN Exit"),
                    ),
            )

        assertTrue(result.warpIndicator)
    }

    @Test
    fun foreignIpsListExcludesRussianPrefixes() {
        val result =
            IpConsensusBuilder.build(
                observations =
                    listOf(
                        IpObservation(IpConsensusChannel.GEO_IP, "5.5.5.5"),
                        IpObservation(IpConsensusChannel.CDN_PULLING, "77.88.8.8"),
                    ),
                asnResolver =
                    fakeResolver(
                        "5.5.5.5" to IpAsnInfo("5.5.5.5", "6805", "DE", "Telefonica"),
                        "77.88.8.8" to IpAsnInfo("77.88.8.8", "13238", "RU", "Yandex"),
                    ),
            )

        assertEquals(listOf("5.5.5.5"), result.foreignIps)
        assertFalse("77.88.8.8" in result.foreignIps)
    }

    @Test
    fun resolvedBuildResolvesEveryDistinctObservedIp() =
        runTest {
            val resolvedIps = mutableListOf<String>()
            val result =
                IpConsensusBuilder.buildResolved(
                    geoIp =
                        CategoryResult(
                            name = "GeoIP",
                            detected = false,
                            findings = listOf(Finding("IP: 1.1.1.1")),
                        ),
                    bypassResult =
                        BypassResult(
                            proxyEndpoint = null,
                            directIp = "2.2.2.2",
                            proxyIp = "3.3.3.3",
                            xrayApiScanResult = null,
                            findings = emptyList(),
                            detected = false,
                        ),
                    ipComparison = null,
                    cdnPulling = null,
                    asnResolver =
                        SuspendingIpAsnResolver { ip ->
                            resolvedIps += ip
                            IpAsnInfo(ip = ip, asn = "64512", countryCode = "US", org = "Test")
                        },
                )

            assertEquals(listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"), resolvedIps.sorted())
            assertEquals(setOf("1.1.1.1", "2.2.2.2", "3.3.3.3"), result.asnByIp.keys)
        }

    private fun fakeResolver(vararg entries: Pair<String, IpAsnInfo>): IpAsnResolver {
        val byIp = entries.toMap()
        return IpAsnResolver { ip ->
            byIp[ip] ?: IpAsnInfo(ip = ip, asn = "64512", countryCode = "US", org = "Test")
        }
    }
}
