@file:Suppress("ReturnCount", "LongMethod")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CdnPullingResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.IcmpSpoofingResult
import com.poyka.ripdpi.core.detection.IpComparisonResult
import com.poyka.ripdpi.core.detection.NativeSignsResult
import com.poyka.ripdpi.core.detection.RttTriangulationResult
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VerdictExplanation
import com.poyka.ripdpi.core.detection.VerdictNarrative
import com.poyka.ripdpi.core.detection.VerdictNarrativeBuilder
import com.poyka.ripdpi.core.detection.VpnAppKind
import com.poyka.ripdpi.core.detection.consensus.IpConsensusResult

object VerdictEngine {
    fun narrative(result: DetectionCheckResult): VerdictNarrative = VerdictNarrativeBuilder.build(result)

    fun evaluate(
        geoIp: CategoryResult,
        directSigns: CategoryResult,
        indirectSigns: CategoryResult,
        locationSignals: CategoryResult,
        bypassResult: BypassResult,
        icmpSpoofing: IcmpSpoofingResult? = null,
        ipComparison: IpComparisonResult? = null,
        rttTriangulation: RttTriangulationResult? = null,
        cdnPulling: CdnPullingResult? = null,
        ipConsensus: IpConsensusResult? = null,
        nativeSigns: NativeSignsResult? = null,
    ): Verdict =
        evaluateDetailed(
            geoIp = geoIp,
            directSigns = directSigns,
            indirectSigns = indirectSigns,
            locationSignals = locationSignals,
            bypassResult = bypassResult,
            icmpSpoofing = icmpSpoofing,
            ipComparison = ipComparison,
            rttTriangulation = rttTriangulation,
            cdnPulling = cdnPulling,
            ipConsensus = ipConsensus,
            nativeSigns = nativeSigns,
        ).verdict

    @Suppress("CyclomaticComplexMethod")
    fun evaluateDetailed(
        geoIp: CategoryResult,
        directSigns: CategoryResult,
        indirectSigns: CategoryResult,
        locationSignals: CategoryResult,
        bypassResult: BypassResult,
        icmpSpoofing: IcmpSpoofingResult? = null,
        ipComparison: IpComparisonResult? = null,
        rttTriangulation: RttTriangulationResult? = null,
        cdnPulling: CdnPullingResult? = null,
        ipConsensus: IpConsensusResult? = null,
        nativeSigns: NativeSignsResult? = null,
    ): VerdictExplanation {
        val homeRoutedRoaming = locationSignals.isHomeRoutedRoaming()
        val evidence =
            buildList {
                addAll(geoIp.evidence)
                addAll(directSigns.evidence)
                addAll(indirectSigns.evidence)
                addAll(locationSignals.evidence)
                addAll(bypassResult.evidence)
                ipComparison?.category?.evidence?.let(::addAll)
                if (!homeRoutedRoaming) {
                    cdnPulling?.category?.evidence?.let(::addAll)
                    icmpSpoofing?.category?.evidence?.let(::addAll)
                }
                rttTriangulation?.category?.evidence?.let(::addAll)
                nativeSigns?.category?.evidence?.let(::addAll)
            }
        val hardBypassEvidence =
            evidence.filter { it.isDetected(EvidenceSource.SPLIT_TUNNEL_BYPASS, EvidenceSource.XRAY_API) }

        if (hardBypassEvidence.isNotEmpty()) {
            return explanation(
                Verdict.DETECTED,
                "R1",
                "Hard bypass evidence detected",
                EvidenceSummary.from(hardBypassEvidence),
            )
        }

        if (ipConsensus?.crossChannelMismatches?.isNotEmpty() == true || ipConsensus?.warpIndicator == true) {
            return explanation(
                Verdict.DETECTED,
                "R3",
                "IP consensus found cross-channel divergence",
                EvidenceSummary.from(ipConsensusDecisionEvidence()),
            )
        }
        if (ipConsensus?.channelConflicts?.isNotEmpty() == true) {
            return explanation(
                Verdict.NEEDS_REVIEW,
                "R3",
                "IP consensus found a single-channel conflict",
                EvidenceSummary.from(ipConsensusDecisionEvidence()),
            )
        }

        val networkMccIsRu =
            locationSignals.findings.any {
                it.description.contains("network_mcc_ru:true")
            }
        val hasGeoSignal =
            geoIp.needsReview ||
                evidence.any {
                    it.source == EvidenceSource.GEO_IP && it.detected
                }
        if (networkMccIsRu && hasGeoSignal) {
            return explanation(
                Verdict.DETECTED,
                "R4",
                "Russian network context conflicts with GeoIP signal",
                EvidenceSummary.from(
                    (geoIp.evidence + locationSignals.evidence).ifEmpty { networkContextDecisionEvidence() },
                ),
            )
        }

        val hasStrongTransport =
            evidence.any {
                it.source == EvidenceSource.NETWORK_CAPABILITIES && it.confidence == EvidenceConfidence.HIGH
            }
        val hasDirectHit =
            directSigns.detected ||
                directSigns.needsReview ||
                bypassResult.detected ||
                evidence.any {
                    it.isDetected(
                        EvidenceSource.LOCAL_PROXY,
                        EvidenceSource.SYSTEM_PROXY,
                        EvidenceSource.NETWORK_CAPABILITIES,
                    )
                }
        val hasTargetedActive =
            evidence.any {
                it.source == EvidenceSource.ACTIVE_VPN && it.kind == VpnAppKind.TARGETED_BYPASS
            }
        val hasGenericActive =
            evidence.any {
                it.source == EvidenceSource.ACTIVE_VPN && it.kind == VpnAppKind.GENERIC_VPN
            }
        val hasIndirectHit =
            indirectSigns.detected ||
                indirectSigns.needsReview ||
                hasTargetedActive
        val geoAxisEvidence = geoIp.evidence
        val directAxisEvidence = directSigns.evidence + bypassResult.evidence
        val indirectAxisEvidence = indirectSigns.evidence + evidence.filter { it.source == EvidenceSource.ACTIVE_VPN }

        return when {
            hasGeoSignal && hasDirectHit && hasIndirectHit -> {
                explanation(
                    Verdict.DETECTED,
                    "R5",
                    "Geo, direct, and indirect detection axes are all set",
                    EvidenceSummary.from(geoAxisEvidence + directAxisEvidence + indirectAxisEvidence),
                )
            }

            hasDirectHit && hasIndirectHit -> {
                explanation(
                    Verdict.DETECTED,
                    "R5",
                    "Direct and indirect detection axes are both set",
                    EvidenceSummary.from(directAxisEvidence + indirectAxisEvidence),
                )
            }

            hasGeoSignal && (hasStrongTransport || hasDirectHit || hasIndirectHit) -> {
                explanation(
                    Verdict.DETECTED,
                    "R5",
                    "Geo axis and one additional detection axis are set",
                    EvidenceSummary.from(
                        geoAxisEvidence +
                            when {
                                hasStrongTransport -> {
                                    evidence.filter {
                                        it.source == EvidenceSource.NETWORK_CAPABILITIES
                                    }
                                }

                                hasDirectHit -> {
                                    directAxisEvidence
                                }

                                else -> {
                                    indirectAxisEvidence
                                }
                            },
                    ),
                )
            }

            hasNeedsReviewFallback(
                hasDirectHit = hasDirectHit,
                hasIndirectHit = hasIndirectHit,
                hasGenericActive = hasGenericActive,
                ipComparison = ipComparison,
                cdnPulling = cdnPulling,
                icmpSpoofing = icmpSpoofing,
                rttTriangulation = rttTriangulation,
                nativeSigns = nativeSigns,
                bypassResult = bypassResult,
                homeRoutedRoaming = homeRoutedRoaming,
            ) -> {
                explanation(
                    Verdict.NEEDS_REVIEW,
                    "R6",
                    "Review-only diagnostic signal present",
                    EvidenceSummary.from(evidence),
                )
            }

            else -> {
                explanation(Verdict.NOT_DETECTED, "R0", "No detection rule matched", EvidenceSummary.from(emptyList()))
            }
        }
    }

    private fun hasNeedsReviewFallback(
        hasDirectHit: Boolean,
        hasIndirectHit: Boolean,
        hasGenericActive: Boolean,
        ipComparison: IpComparisonResult?,
        cdnPulling: CdnPullingResult?,
        icmpSpoofing: IcmpSpoofingResult?,
        rttTriangulation: RttTriangulationResult?,
        nativeSigns: NativeSignsResult?,
        bypassResult: BypassResult,
        homeRoutedRoaming: Boolean,
    ): Boolean =
        hasDirectHit ||
            hasIndirectHit ||
            hasGenericActive ||
            ipComparison?.category?.needsReview == true ||
            (!homeRoutedRoaming && cdnPulling?.category.hasReviewSignal()) ||
            (!homeRoutedRoaming && icmpSpoofing?.category.hasReviewSignal()) ||
            rttTriangulation?.category.hasReviewSignal() ||
            nativeSigns?.category.hasReviewSignal() ||
            bypassResult.mtProtoReachable ||
            bypassResult.stunReflexiveAddresses.isNotEmpty()

    private fun CategoryResult?.hasReviewSignal(): Boolean =
        this?.detected == true || this?.needsReview == true || this?.evidence?.any(EvidenceItem::detected) == true

    private fun CategoryResult.isHomeRoutedRoaming(): Boolean {
        if (findings.any { it.description == "home_routed_roaming:true" }) return true
        val hasRussianNetwork = findings.any { it.description == "network_mcc_ru:true" }
        val hasRoaming = findings.any { it.description == "Roaming: yes" }
        val simMcc =
            findings.firstNotNullOfOrNull { finding ->
                SIM_MCC_REGEX.find(finding.description)?.groupValues?.get(1)
            }
        return hasRussianNetwork && hasRoaming && simMcc != null && simMcc != RUSSIA_MCC
    }

    private fun EvidenceItem.isDetected(vararg sources: EvidenceSource): Boolean = detected && source in sources

    private fun explanation(
        verdict: Verdict,
        ruleApplied: String,
        summary: String,
        evidenceSummary: EvidenceSummary,
    ): VerdictExplanation =
        VerdictExplanation(
            verdict = verdict,
            ruleApplied = ruleApplied,
            summary = summary,
            appliedScopes = evidenceSummary.appliedScopes,
            uniqueSignalCount = evidenceSummary.uniqueSignalCount,
        )

    private data class EvidenceSummary(
        val appliedScopes: List<DetectionScope>,
        val uniqueSignalCount: Int,
    ) {
        companion object {
            fun from(evidence: List<EvidenceItem>): EvidenceSummary {
                val detectedEvidence = evidence.filter(EvidenceItem::detected)
                return EvidenceSummary(
                    appliedScopes = detectedEvidence.map(EvidenceItem::scope).distinct(),
                    uniqueSignalCount = detectedEvidence.map { it.logicalSignalKey() }.distinct().size,
                )
            }
        }
    }

    private fun EvidenceItem.logicalSignalKey(): String =
        when (scope) {
            DetectionScope.LOCAL_INVENTORY -> {
                listOfNotNull(packageName, family).firstOrNull()?.let { "inventory:$it" }
                    ?: "inventory:${description.lowercase()}"
            }

            DetectionScope.LOCAL_OBSERVER_EXPOSURE -> {
                if (source == EvidenceSource.NETWORK_CAPABILITIES) {
                    "observer:active_vpn_capabilities"
                } else {
                    listOfNotNull(packageName, family).firstOrNull()?.let { "observer:$it" }
                        ?: "observer:${source.name}:${description.lowercase()}"
                }
            }

            DetectionScope.NETWORK_OBSERVATION -> {
                "network:${source.name}:${family.orEmpty()}:${description.lowercase()}"
            }
        }

    private fun ipConsensusDecisionEvidence(): List<EvidenceItem> =
        listOf(
            EvidenceItem(
                source = EvidenceSource.IP_CONSENSUS,
                detected = true,
                confidence = EvidenceConfidence.HIGH,
                description = "IP consensus decision signal",
            ),
        )

    private fun networkContextDecisionEvidence(): List<EvidenceItem> =
        listOf(
            EvidenceItem(
                source = EvidenceSource.GEO_IP,
                detected = true,
                confidence = EvidenceConfidence.MEDIUM,
                description = "Network context and GeoIP decision signal",
            ),
        )

    private const val RUSSIA_MCC = "250"
    private val SIM_MCC_REGEX = Regex("""SIM MCC:\s*(\d+)""")
}
