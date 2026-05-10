package com.poyka.ripdpi.core.detection

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
)

internal class DetectionPipelineResultAssembler(
    private val verdictEvaluator: DetectionVerdictEvaluator,
) {
    fun assemble(outputs: DetectionPipelineOutputs): DetectionCheckResult {
        val locationSignals = outputs.locationSignals ?: DetectionDisabledResults.locationSignals()
        val bypassResult = outputs.bypassResult ?: DetectionDisabledResults.bypass()
        val verdict =
            verdictEvaluator.evaluate(
                geoIp = outputs.geoIp,
                directSigns = outputs.directSigns,
                indirectSigns = outputs.indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                ipComparison = outputs.ipComparison,
            )

        return DetectionCheckResult(
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
            verdict = verdict,
        )
    }
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
