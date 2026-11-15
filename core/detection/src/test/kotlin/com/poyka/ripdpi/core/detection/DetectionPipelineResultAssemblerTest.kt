package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.consensus.IpAsnInfo
import com.poyka.ripdpi.core.detection.consensus.SuspendingIpAsnResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionPipelineResultAssemblerTest {
    @Test
    fun `network requests off leaves ASN unknown without enrichment`() =
        runTest {
            val assembler =
                DetectionPipelineResultAssembler(
                    verdictEvaluator = DefaultDetectionVerdictEvaluator(),
                    asnResolver = SuspendingIpAsnResolver { ip -> IpAsnInfo(ip, "AS15169", "US") },
                )

            val result = assembler.assemble(outputs(), DetectionRunnerConfig(networkRequestsEnabled = false))

            assertNull(requireNotNull(result.ipConsensus).asnByIp.getValue("8.8.8.8").asn)
        }

    private fun outputs(): DetectionPipelineOutputs {
        val empty = CategoryResult("empty", detected = false, findings = emptyList())
        return DetectionPipelineOutputs(
            geoIp = empty,
            directSigns = empty,
            indirectSigns = empty,
            locationSignals = empty,
            bypassResult = null,
            dnsLeak = null,
            webRtcLeak = null,
            tlsFingerprint = null,
            timingAnalysis = null,
            icmpSpoofing = null,
            ipComparison = IpComparisonResult(empty, emptyList(), setOf("8.8.8.8"), emptySet()),
            rttTriangulation = null,
            cdnPulling = null,
            nativeSigns = null,
            callTransport = null,
        )
    }
}
