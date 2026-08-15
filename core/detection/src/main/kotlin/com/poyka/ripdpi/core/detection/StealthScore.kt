@file:Suppress("MagicNumber", "ReturnCount")

package com.poyka.ripdpi.core.detection

object StealthScore {
    private const val MAX_SCORE = 100
    private const val SPLIT_BYPASS_PENALTY = 40
    private const val XRAY_API_PENALTY = 35
    private const val TRANSPORT_VPN_PENALTY = 20
    private const val LOCAL_PROXY_PENALTY = 15
    private const val ACTIVE_VPN_TARGETED_PENALTY = 20
    private const val ACTIVE_VPN_GENERIC_PENALTY = 10
    private const val INSTALLED_APP_TARGETED_PENALTY = 8
    private const val INSTALLED_APP_GENERIC_PENALTY = 3
    private const val GEO_IP_HOSTING_PENALTY = 15
    private const val GEO_IP_PROXY_PENALTY = 15
    private const val GEO_IP_FOREIGN_PENALTY = 5
    private const val DNS_LOOPBACK_PENALTY = 12
    private const val DNS_PRIVATE_TUNNEL_PENALTY = 8
    private const val DNS_PUBLIC_RESOLVER_PENALTY = 3
    private const val VPN_INTERFACE_PENALTY = 10
    private const val MTU_ANOMALY_PENALTY = 5
    private const val ROUTING_ANOMALY_PENALTY = 8
    private const val NOT_VPN_MISSING_PENALTY = 8
    private const val LOCATION_MCC_MISMATCH_PENALTY = 10
    private const val TIMING_ANOMALY_PENALTY = 10

    fun compute(result: DetectionCheckResult): Int {
        var penalty = 0

        penalty += computeBypassPenalty(result)
        penalty += computeDirectPenalty(result)
        penalty += computeIndirectPenalty(result)
        penalty += computeGeoIpPenalty(result)
        penalty += computeLocationPenalty(result)
        penalty += computeTimingPenalty(result)

        return (MAX_SCORE - penalty).coerceIn(0, MAX_SCORE)
    }

    fun label(score: Int): String =
        when {
            score >= 90 -> "Excellent"
            score >= 70 -> "Good"
            score >= 50 -> "Fair"
            score >= 30 -> "Poor"
            else -> "Critical"
        }

    fun normalizedProgress(score: Int): Float = (score.toFloat() / MAX_SCORE).coerceIn(0f, 1f)

    private fun computeBypassPenalty(result: DetectionCheckResult): Int {
        var penalty = 0
        val evidence = result.bypassResult.evidence
        if (evidence.any { it.source == EvidenceSource.SPLIT_TUNNEL_BYPASS && it.detected }) {
            penalty += SPLIT_BYPASS_PENALTY
        }
        if (evidence.any { it.source == EvidenceSource.XRAY_API && it.detected }) {
            penalty += XRAY_API_PENALTY
        }
        if (evidence.any { it.source == EvidenceSource.LOCAL_PROXY && it.detected }) {
            penalty += LOCAL_PROXY_PENALTY
        }
        return penalty
    }

    private fun computeDirectPenalty(result: DetectionCheckResult): Int {
        var penalty = 0
        val evidence = result.directSigns.evidence
        if (evidence.any {
                it.source == EvidenceSource.NETWORK_CAPABILITIES &&
                    it.confidence == EvidenceConfidence.HIGH
            }
        ) {
            penalty += TRANSPORT_VPN_PENALTY
        }
        for (app in result.directSigns.matchedApps) {
            penalty +=
                when (app.kind) {
                    VpnAppKind.TARGETED_BYPASS -> INSTALLED_APP_TARGETED_PENALTY
                    VpnAppKind.GENERIC_VPN -> INSTALLED_APP_GENERIC_PENALTY
                }
        }
        val activeEvidence = evidence.filter { it.source == EvidenceSource.ACTIVE_VPN }
        if (activeEvidence.any { it.kind == VpnAppKind.TARGETED_BYPASS }) {
            penalty += ACTIVE_VPN_TARGETED_PENALTY
        } else if (activeEvidence.any { it.kind == VpnAppKind.GENERIC_VPN }) {
            penalty += ACTIVE_VPN_GENERIC_PENALTY
        }
        return penalty
    }

    private fun computeIndirectPenalty(result: DetectionCheckResult): Int {
        var penalty = 0
        val evidence = result.indirectSigns.evidence
        if (evidence.any {
                it.source == EvidenceSource.NETWORK_CAPABILITIES &&
                    it.description.contains("NOT_VPN")
            }
        ) {
            penalty += NOT_VPN_MISSING_PENALTY
        }
        if (evidence.any { it.source == EvidenceSource.NETWORK_INTERFACE }) {
            penalty += VPN_INTERFACE_PENALTY
        }
        if (evidence.any {
                it.source == EvidenceSource.NETWORK_INTERFACE &&
                    it.description.contains("MTU")
            }
        ) {
            penalty += MTU_ANOMALY_PENALTY
        }
        if (evidence.any { it.source == EvidenceSource.ROUTING }) {
            penalty += ROUTING_ANOMALY_PENALTY
        }
        val dnsEvidence = evidence.filter { it.source == EvidenceSource.DNS }
        if (dnsEvidence.any { it.confidence == EvidenceConfidence.HIGH }) {
            penalty += DNS_LOOPBACK_PENALTY
        } else if (dnsEvidence.any { it.confidence == EvidenceConfidence.MEDIUM }) {
            penalty += DNS_PRIVATE_TUNNEL_PENALTY
        } else if (dnsEvidence.any { it.confidence == EvidenceConfidence.LOW }) {
            penalty += DNS_PUBLIC_RESOLVER_PENALTY
        }
        return penalty
    }

    private fun computeGeoIpPenalty(result: DetectionCheckResult): Int {
        var penalty = 0
        val evidence = result.geoIp.evidence
        if (evidence.any { it.description.contains("hosting") }) {
            penalty += GEO_IP_HOSTING_PENALTY
        }
        if (evidence.any { it.description.contains("proxy") || it.description.contains("VPN") }) {
            penalty += GEO_IP_PROXY_PENALTY
        }
        if (result.geoIp.needsReview && penalty == 0) {
            penalty += GEO_IP_FOREIGN_PENALTY
        }
        return penalty
    }

    private fun computeLocationPenalty(result: DetectionCheckResult): Int {
        if (result.locationSignals.evidence.any { it.source == EvidenceSource.LOCATION_SIGNALS }) {
            return LOCATION_MCC_MISMATCH_PENALTY
        }
        return 0
    }

    private fun computeTimingPenalty(result: DetectionCheckResult): Int {
        val timing = result.timingAnalysis ?: return 0
        if (timing.detected || timing.evidence.isNotEmpty()) {
            return TIMING_ANOMALY_PENALTY
        }
        return 0
    }
}

data class ExposureVerdict(
    val headline: String,
    val reason: String,
    val adjustmentHint: String,
    val dominantSignal: String,
)

object ExposureVerdictBuilder {
    fun build(
        result: DetectionCheckResult,
        stealthScore: Int,
    ): ExposureVerdict {
        val signal = result.dominantExposureSignal()
        if (result.verdict == Verdict.NOT_DETECTED) {
            return ExposureVerdict(
                headline = "You look like ordinary HTTPS traffic",
                reason = cleanReason(stealthScore),
                adjustmentHint = cleanHint(stealthScore),
                dominantSignal = "ordinary HTTPS baseline",
            )
        }
        val dominant = signal ?: ExposureSignal.generic(result.verdict)
        return ExposureVerdict(
            headline = "Your traffic is distinguishable by ${dominant.label}",
            reason = dominant.reason,
            adjustmentHint = dominant.hint,
            dominantSignal = dominant.label,
        )
    }

    private fun cleanReason(stealthScore: Int): String =
        if (stealthScore >= CLEAN_STEALTH_THRESHOLD) {
            "Detection probes did not expose bypass, VPN, IP, DNS, TLS, timing, or native observability signals."
        } else {
            "Detection probes were clean; masking stealth is ${StealthScore.label(stealthScore).lowercase()}."
        }

    private fun cleanHint(stealthScore: Int): String =
        if (stealthScore >= CLEAN_STEALTH_THRESHOLD) {
            "Keep the current profile and rerun after changing networks or profiles."
        } else {
            "Keep probes clean and improve the masking profile before relying on stricter networks."
        }

    private fun DetectionCheckResult.dominantExposureSignal(): ExposureSignal? =
        buildList {
            addEvidenceSignals(bypassResult.evidence)
            addEvidenceSignals(directSigns.evidence)
            addEvidenceSignals(indirectSigns.evidence)
            addEvidenceSignals(locationSignals.evidence)
            dnsLeak?.evidence?.let { addEvidenceSignals(it) }
            webRtcLeak?.evidence?.let { addEvidenceSignals(it) }
            tlsFingerprint?.evidence?.let { addEvidenceSignals(it) }
            timingAnalysis?.evidence?.let { addEvidenceSignals(it) }
            icmpSpoofing?.category?.evidence?.let { addEvidenceSignals(it) }
            ipComparison?.category?.evidence?.let { addEvidenceSignals(it) }
            rttTriangulation?.category?.evidence?.let { addEvidenceSignals(it) }
            cdnPulling?.category?.evidence?.let { addEvidenceSignals(it) }
            nativeSigns?.category?.evidence?.let { addEvidenceSignals(it) }
            callTransport?.category?.evidence?.let { addEvidenceSignals(it) }
            ipConsensus?.toCategoryResult()?.evidence?.let { addEvidenceSignals(it) }
            addFallbackCategorySignals(this@dominantExposureSignal)
        }.minByOrNull(ExposureSignal::priority)

    private fun MutableList<ExposureSignal>.addEvidenceSignals(evidence: List<EvidenceItem>) {
        evidence.filter { it.detected }.forEach { item ->
            add(item.source.toExposureSignal())
        }
    }

    private fun MutableList<ExposureSignal>.addFallbackCategorySignals(result: DetectionCheckResult) {
        if (result.bypassResult.detected || result.bypassResult.needsReview) {
            add(ExposureSignal.bypassProbe)
        }
        addCategorySignal(result.geoIp, ExposureSignal.geoIp)
        addCategorySignal(result.directSigns, ExposureSignal.directSigns)
        addCategorySignal(result.indirectSigns, ExposureSignal.indirectSigns)
        addCategorySignal(result.locationSignals, ExposureSignal.locationSignals)
        addCategorySignal(result.dnsLeak, ExposureSignal.dnsLeak)
        addCategorySignal(result.webRtcLeak, ExposureSignal.webRtc)
        addCategorySignal(result.tlsFingerprint, ExposureSignal.tlsFingerprint)
        addCategorySignal(result.timingAnalysis, ExposureSignal.timing)
        addCategorySignal(result.icmpSpoofing?.category, ExposureSignal.icmp)
        addCategorySignal(result.ipComparison?.category, ExposureSignal.ipComparison)
        addCategorySignal(result.rttTriangulation?.category, ExposureSignal.rtt)
        addCategorySignal(result.cdnPulling?.category, ExposureSignal.cdn)
        addCategorySignal(result.nativeSigns?.category, ExposureSignal.nativeSigns)
        addCategorySignal(result.callTransport?.category, ExposureSignal.callTransport)
        addCategorySignal(result.ipConsensus?.toCategoryResult(), ExposureSignal.ipConsensus)
    }

    private fun MutableList<ExposureSignal>.addCategorySignal(
        category: CategoryResult?,
        signal: ExposureSignal,
    ) {
        if (category?.detected == true || category?.needsReview == true) {
            add(signal)
        }
    }

    private fun EvidenceSource.toExposureSignal(): ExposureSignal =
        when (this) {
            EvidenceSource.SPLIT_TUNNEL_BYPASS -> ExposureSignal.splitTunnel
            EvidenceSource.XRAY_API -> ExposureSignal.xrayApi
            EvidenceSource.LOCAL_PROXY, EvidenceSource.SYSTEM_PROXY -> ExposureSignal.localProxy
            EvidenceSource.NETWORK_CAPABILITIES, EvidenceSource.ACTIVE_VPN -> ExposureSignal.vpnBinding
            EvidenceSource.INSTALLED_APP, EvidenceSource.VPN_SERVICE_DECLARATION -> ExposureSignal.appArtifact
            EvidenceSource.IP_COMPARISON -> ExposureSignal.ipComparison
            EvidenceSource.IP_CONSENSUS -> ExposureSignal.ipConsensus
            EvidenceSource.GEO_IP, EvidenceSource.LOCATION_SIGNALS -> ExposureSignal.geoIp
            EvidenceSource.DNS -> ExposureSignal.dnsLeak
            EvidenceSource.NETWORK_INTERFACE, EvidenceSource.ROUTING -> ExposureSignal.networkObservability
            EvidenceSource.NATIVE_SIGNS -> ExposureSignal.nativeSigns
            EvidenceSource.CALL_TRANSPORT -> ExposureSignal.callTransport
            EvidenceSource.ICMP_SPOOFING -> ExposureSignal.icmp
            EvidenceSource.RTT_TRIANGULATION -> ExposureSignal.rtt
            EvidenceSource.CDN_PULLING -> ExposureSignal.cdn
            EvidenceSource.DUMPSYS -> ExposureSignal.nativeSigns
        }

    private const val CLEAN_STEALTH_THRESHOLD = 90
}

private data class ExposureSignal(
    val label: String,
    val reason: String,
    val hint: String,
    val priority: Int,
) {
    companion object {
        val splitTunnel =
            ExposureSignal(
                label = "split tunnel bypass",
                reason = "Direct and proxied paths do not present the same network identity.",
                hint = "Route apps and domains through one path, then rerun the detection check.",
                priority = 0,
            )

        val xrayApi =
            ExposureSignal(
                label = "Xray API access",
                reason = "A local API or outbound configuration was reachable from the device.",
                hint = "Restrict local API access and hide exported outbound metadata.",
                priority = 1,
            )

        val ipConsensus =
            ExposureSignal(
                label = "IP consensus mismatch",
                reason = "Observed public IPs diverge across detection channels.",
                hint = "Align DNS, proxy, VPN, and app routing so every checker sees the same exit.",
                priority = 2,
            )

        val ipComparison =
            ExposureSignal(
                label = "IP comparison mismatch",
                reason = "Regional IP comparison probes saw inconsistent public identities.",
                hint = "Use one exit path for both local and remote comparison probes.",
                priority = 3,
            )

        val geoIp =
            ExposureSignal(
                label = "GeoIP or location conflict",
                reason = "GeoIP, SIM, or network-country signals do not agree.",
                hint = "Choose an exit region that matches the network context or disable mismatched routing.",
                priority = 4,
            )

        val vpnBinding =
            ExposureSignal(
                label = "Android VPN binding",
                reason = "Android network capabilities expose VPN routing to local observers.",
                hint = "Review VPN mode, per-app routing, and active VPN ownership before retesting.",
                priority = 5,
            )

        val localProxy =
            ExposureSignal(
                label = "local proxy",
                reason = "A local proxy endpoint is visible to detection probes.",
                hint = "Bind proxy listeners narrowly and avoid exposing local ports to checked apps.",
                priority = 6,
            )

        val appArtifact =
            ExposureSignal(
                label = "installed or active app artifact",
                reason = "Installed package or active service metadata matches VPN or bypass tooling.",
                hint = "Hide app metadata where possible and disable unrelated active VPN services.",
                priority = 7,
            )

        val dnsLeak =
            ExposureSignal(
                label = "DNS observability",
                reason = "DNS resolver behavior differs from the expected encrypted or routed path.",
                hint = "Align direct, bootstrap, and encrypted DNS settings with the selected exit path.",
                priority = 8,
            )

        val tlsFingerprint =
            ExposureSignal(
                label = "TLS fingerprint",
                reason = "TLS handshake traits differ from the expected browser-like profile.",
                hint = "Switch to a browser-like TLS profile and retest after reconnecting.",
                priority = 9,
            )

        val networkObservability =
            ExposureSignal(
                label = "network observability",
                reason = "Network interface or routing metadata exposes a tunnel-shaped path.",
                hint = "Review MTU, interface, and routing settings for tunnel-specific markers.",
                priority = 10,
            )

        val timing =
            ExposureSignal(
                label = "timing anomaly",
                reason = "Latency or timing patterns differ from ordinary direct HTTPS traffic.",
                hint = "Try a closer exit, reduce chained hops, or select a lower-latency profile.",
                priority = 11,
            )

        val nativeSigns =
            ExposureSignal(
                label = "native runtime signal",
                reason = "Native checks found hook, root, or hidden-interface artifacts.",
                hint = "Remove unrelated instrumentation and retest on the target device profile.",
                priority = 12,
            )

        val callTransport =
            ExposureSignal(
                label = "call transport leak",
                reason = "Call or STUN transport probes reveal a different path than expected.",
                hint = "Route realtime transports through the same protected path as HTTPS traffic.",
                priority = 13,
            )

        val icmp =
            ExposureSignal(
                label = "ICMP observability",
                reason = "ICMP probes need review for path or spoofing behavior.",
                hint = "Verify raw-path behavior and disable strategies that expose ICMP differences.",
                priority = 14,
            )

        val rtt =
            ExposureSignal(
                label = "RTT triangulation",
                reason = "Round-trip timing suggests a route inconsistent with the claimed location.",
                hint = "Use an exit closer to the apparent region or avoid high-latency chains.",
                priority = 15,
            )

        val cdn =
            ExposureSignal(
                label = "CDN pull signal",
                reason = "CDN pull probes observed address or TLS traits that need review.",
                hint = "Check CDN-facing DNS and TLS routing before rerunning the probe.",
                priority = 16,
            )

        val webRtc =
            ExposureSignal(
                label = "WebRTC leak",
                reason = "WebRTC behavior exposes an address or path outside the expected route.",
                hint = "Enable WebRTC protection or route WebRTC through the protected path.",
                priority = 17,
            )

        val directSigns =
            ExposureSignal(
                label = "direct device signal",
                reason = "Local direct checks found proxy or VPN artifacts.",
                hint = "Review active VPN ownership, proxy ports, and app-visible network state.",
                priority = 18,
            )

        val indirectSigns =
            ExposureSignal(
                label = "indirect device signal",
                reason = "Installed-app or service signals make the setup recognizable.",
                hint = "Disable unrelated VPN apps and reduce app-visible bypass metadata.",
                priority = 19,
            )

        val locationSignals =
            ExposureSignal(
                label = "location signal",
                reason = "SIM, roaming, or network-country metadata needs review.",
                hint = "Retest on the target network and align the exit region with local context.",
                priority = 20,
            )

        val bypassProbe =
            ExposureSignal(
                label = "bypass probe",
                reason = "Bypass-specific probes found a visible local or remote path.",
                hint = "Review split routing and proxy exposure, then rerun the bypass check.",
                priority = 21,
            )

        fun generic(verdict: Verdict) =
            ExposureSignal(
                label = if (verdict == Verdict.NEEDS_REVIEW) "review signal" else "detection probe",
                reason = "The detection pipeline found an exposure signal but no more specific cause was available.",
                hint = "Open the detailed categories below and adjust the first detected or review item.",
                priority = 99,
            )
    }
}
