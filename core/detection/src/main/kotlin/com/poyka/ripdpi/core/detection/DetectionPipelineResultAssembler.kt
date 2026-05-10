package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.checker.GeoIpChecker
import com.poyka.ripdpi.core.detection.checker.VerdictEngine
import com.poyka.ripdpi.core.detection.consensus.IpConsensusBuilder
import com.poyka.ripdpi.core.detection.consensus.SuspendingIpAsnResolver

internal data class DetectionPipelineOutputs(
    val geoIp: CategoryResult,
    val directSigns: CategoryResult,
    val indirectSigns: CategoryResult,
    val locationSignals: CategoryResult?,
    val bypassResult: BypassResult?,
    val dnsLeak: CategoryResult?,
    val webRtcLeak: CategoryResult?,
    val tlsFingerprint: CategoryResult?,
    val timingAnalysis: CategoryResult?,
    val icmpSpoofing: IcmpSpoofingResult?,
    val ipComparison: IpComparisonResult?,
    val rttTriangulation: RttTriangulationResult?,
    val cdnPulling: CdnPullingResult?,
    val nativeSigns: NativeSignsResult?,
    val callTransport: CallTransportResult?,
)

internal class DetectionPipelineResultAssembler(
    private val verdictEvaluator: DetectionVerdictEvaluator,
    private val asnResolver: SuspendingIpAsnResolver = DEFAULT_ASN_RESOLVER,
) {
    suspend fun assemble(outputs: DetectionPipelineOutputs): DetectionCheckResult {
        val locationSignals = outputs.locationSignals ?: DetectionDisabledResults.locationSignals()
        val bypassResult = outputs.bypassResult ?: DetectionDisabledResults.bypass()
        val ipConsensus =
            IpConsensusBuilder.buildResolved(
                geoIp = outputs.geoIp,
                bypassResult = bypassResult,
                ipComparison = outputs.ipComparison,
                cdnPulling = outputs.cdnPulling,
                callTransport = outputs.callTransport,
                asnResolver = asnResolver,
            )
        val verdictExplanation =
            verdictEvaluator.explain(
                geoIp = outputs.geoIp,
                directSigns = outputs.directSigns,
                indirectSigns = outputs.indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                icmpSpoofing = outputs.icmpSpoofing,
                ipComparison = outputs.ipComparison,
                rttTriangulation = outputs.rttTriangulation,
                cdnPulling = outputs.cdnPulling,
                ipConsensus = ipConsensus,
                nativeSigns = outputs.nativeSigns,
            )

        val result =
            DetectionCheckResult(
                geoIp = outputs.geoIp,
                directSigns = outputs.directSigns,
                indirectSigns = outputs.indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                dnsLeak = outputs.dnsLeak,
                webRtcLeak = outputs.webRtcLeak,
                tlsFingerprint = outputs.tlsFingerprint,
                timingAnalysis = outputs.timingAnalysis,
                icmpSpoofing = outputs.icmpSpoofing,
                ipComparison = outputs.ipComparison,
                rttTriangulation = outputs.rttTriangulation,
                cdnPulling = outputs.cdnPulling,
                nativeSigns = outputs.nativeSigns,
                callTransport = outputs.callTransport,
                verdict = verdictExplanation.verdict,
                ipConsensus = ipConsensus,
                verdictExplanation = verdictExplanation,
            )
        return result.copy(verdictNarrative = VerdictEngine.narrative(result))
    }
}

private val DEFAULT_ASN_RESOLVER =
    SuspendingIpAsnResolver { ip ->
        GeoIpChecker.resolveAsnInfo(ip)
    }

internal object DetectionDisabledResults {
    fun locationSignals(): CategoryResult =
        CategoryResult(
            name = "Location signals",
            detected = false,
            findings = listOf(Finding("Location check disabled")),
        )

    fun bypass(): BypassResult =
        BypassResult(
            proxyEndpoint = null,
            directIp = null,
            proxyIp = null,
            xrayApiScanResult = null,
            findings = listOf(Finding("Bypass check disabled")),
            detected = false,
        )
}
