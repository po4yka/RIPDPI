package com.poyka.ripdpi.core.detection

import kotlin.math.roundToInt

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
