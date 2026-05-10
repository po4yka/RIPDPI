package com.poyka.ripdpi.core.detection.consensus

import com.poyka.ripdpi.core.detection.EvidenceConfidence
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

    private fun fakeResolver(vararg entries: Pair<String, IpAsnInfo>): IpAsnResolver {
        val byIp = entries.toMap()
        return IpAsnResolver { ip ->
            byIp[ip] ?: IpAsnInfo(ip = ip, asn = "64512", countryCode = "US", org = "Test")
        }
    }
}
