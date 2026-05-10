package com.poyka.ripdpi.core.detection.debug

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.NativeSignsResult
import com.poyka.ripdpi.core.detection.privacy.DetectionPrivacyMask

data class DetectionDebugSettings(
    val cdnPullingEnabled: Boolean = false,
    val dnsResolverMode: String = "default",
    val portRange: String = "default",
    val debugModeEnabled: Boolean = false,
)

data class NativeSignsDebugSnapshot(
    val getifaddrsRows: List<String> = emptyList(),
    val tcpSockets: List<String> = emptyList(),
    val udpSockets: List<String> = emptyList(),
    val procNetRoute: List<String> = emptyList(),
    val procNetIpv6Route: List<String> = emptyList(),
    val procNetDev: List<String> = emptyList(),
    val mapsMarkers: List<String> = emptyList(),
    val jvmNetworkInterfaces: List<String> = emptyList(),
)

data class DetectionDebugSnapshot(
    val indirectStepTimings: Map<String, Long> = emptyMap(),
    val locationDebugRows: List<String> = emptyList(),
    val nativeSigns: NativeSignsDebugSnapshot = NativeSignsDebugSnapshot(),
    val tunProbe: TunProbeDebugSnapshot = TunProbeDebugSnapshot(),
)

object DetectionDebugFormatter {
    fun format(
        result: DetectionCheckResult,
        settings: DetectionDebugSettings = DetectionDebugSettings(),
        snapshot: DetectionDebugSnapshot = DetectionDebugSnapshot(),
        privacyModeEnabled: Boolean = false,
    ): String =
        DetectionPrivacyMask.maskIpsInText(
            buildString {
                appendSettings(settings)
                appendCategories(result)
                appendIndirectPerformance(snapshot)
                appendLocationDebug(snapshot)
                appendNativeRaw(result.nativeSigns, snapshot.nativeSigns)
                append(TunProbeDebugFormatter.formatSection(snapshot.tunProbe))
            },
            enabled = privacyModeEnabled,
        )

    private fun StringBuilder.appendSettings(settings: DetectionDebugSettings) {
        appendLine("[settings]")
        appendLine("cdnPullingEnabled=${settings.cdnPullingEnabled}")
        appendLine("dnsResolverMode=${settings.dnsResolverMode}")
        appendLine("portRange=${settings.portRange}")
        appendLine("debugModeEnabled=${settings.debugModeEnabled}")
        appendLine()
    }

    private fun StringBuilder.appendCategories(result: DetectionCheckResult) {
        appendCategory("geoIp", result.geoIp)
        appendCategory("directSigns", result.directSigns)
        appendCategory("indirectSigns", result.indirectSigns)
        appendCategory("locationSignals", result.locationSignals)
        appendCategory(
            "bypass",
            result.bypassResult.let {
                CategoryResult(
                    name = "Bypass",
                    detected = it.detected,
                    needsReview = it.needsReview,
                    findings = it.findings,
                    evidence = it.evidence,
                )
            },
        )
        result.nativeSigns?.category?.let { appendCategory("nativeSigns", it) }
        result.callTransport?.category?.let { appendCategory("callTransport", it) }
        result.ipConsensus?.toCategoryResult()?.let { appendCategory("ipConsensus", it) }
    }

    private fun StringBuilder.appendCategory(
        key: String,
        category: CategoryResult,
    ) {
        appendLine("[$key]")
        appendLine("name=${category.name}")
        appendLine("detected=${category.detected}")
        appendLine("needsReview=${category.needsReview}")
        category.findings.forEachIndexed { index, finding ->
            appendLine("finding.$index=${finding.description}")
        }
        category.evidence.forEachIndexed { index, evidence ->
            appendLine("evidence.$index=${evidence.source}:${evidence.confidence}:${evidence.description}")
        }
        appendLine()
    }

    private fun StringBuilder.appendIndirectPerformance(snapshot: DetectionDebugSnapshot) {
        appendLine("[indirectSigns.performance]")
        appendLine("totalDurationMs=${snapshot.indirectStepTimings.values.sum()}")
        val dominant =
            snapshot.indirectStepTimings
                .maxByOrNull { it.value }
                ?.key
                .orEmpty()
        appendLine("dominantDelay=$dominant")
        snapshot.indirectStepTimings.forEach { (step, durationMs) ->
            appendLine("step.$step.durationMs=$durationMs")
        }
        appendLine()
    }

    private fun StringBuilder.appendLocationDebug(snapshot: DetectionDebugSnapshot) {
        appendLine("[locationSignals.debug]")
        if (snapshot.locationDebugRows.isEmpty()) {
            appendLine("unavailableReason=no debug rows captured")
        }
        snapshot.locationDebugRows.forEach { appendLine(it) }
        appendLine()
    }

    private fun StringBuilder.appendNativeRaw(
        result: NativeSignsResult?,
        snapshot: NativeSignsDebugSnapshot,
    ) {
        appendLine("[nativeSigns.raw]")
        result?.hiddenInterfaces.orEmpty().forEach { appendLine("hiddenInterface=$it") }
        result?.hookMarkers.orEmpty().forEach { appendLine("hookMarker=$it") }
        result?.rwxRegions.orEmpty().forEach { appendLine("rwxRegion=$it") }
        result?.rootArtifacts.orEmpty().forEach { appendLine("rootArtifact=$it") }
        snapshot.getifaddrsRows.forEach { appendLine("getifaddrs=$it") }
        snapshot.tcpSockets.take(MAX_SOCKET_ROWS).forEach { appendLine("tcpSocket=$it") }
        snapshot.udpSockets.take(MAX_SOCKET_ROWS).forEach { appendLine("udpSocket=$it") }
        snapshot.procNetRoute.forEach { appendLine("procNetRoute=$it") }
        snapshot.procNetIpv6Route.forEach { appendLine("procNetIpv6Route=$it") }
        snapshot.procNetDev.forEach { appendLine("procNetDev=$it") }
        snapshot.mapsMarkers.forEach { appendLine("mapsMarker=$it") }
        snapshot.jvmNetworkInterfaces.forEach { appendLine("jvmNetworkInterface=$it") }
        appendLine()
    }

    private const val MAX_SOCKET_ROWS = 60
}
