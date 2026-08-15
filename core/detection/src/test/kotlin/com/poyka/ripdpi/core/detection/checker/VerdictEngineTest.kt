package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.IcmpSpoofingResult
import com.poyka.ripdpi.core.detection.IcmpSpoofingState
import com.poyka.ripdpi.core.detection.IpComparisonResult
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VpnAppKind
import com.poyka.ripdpi.core.detection.consensus.ChannelConflict
import com.poyka.ripdpi.core.detection.consensus.CrossChannelMismatch
import com.poyka.ripdpi.core.detection.consensus.IpConsensusChannel
import com.poyka.ripdpi.core.detection.consensus.IpConsensusResult
import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictEngineTest {
    private fun emptyCategory(name: String) =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )

    private fun emptyBypass() =
        BypassResult(
            proxyEndpoint = null,
            directIp = null,
            proxyIp = null,
            xrayApiScanResult = null,
            findings = emptyList(),
            detected = false,
        )

    @Test
    fun `no evidence returns NOT_DETECTED`() {
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.NOT_DETECTED, verdict)
    }

    @Test
    fun `split tunnel bypass returns DETECTED`() {
        val bypass =
            emptyBypass().copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "IPs differ",
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = bypass,
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `xray API detected returns DETECTED`() {
        val bypass =
            emptyBypass().copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.XRAY_API,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "Xray found",
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = bypass,
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `R1 split tunnel bypass short circuits consensus mismatch`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "IPs differ",
                                ),
                            ),
                    ),
                ipConsensus = crossChannelMismatchConsensus(),
            )

        assertEquals(Verdict.DETECTED, explanation.verdict)
        assertEquals("R1", explanation.ruleApplied)
    }

    @Test
    fun `R1 provenance excludes irrelevant network evidence`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.XRAY_API,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "Local Xray API reachable",
                                ),
                            ),
                    ),
                ipComparison =
                    IpComparisonResult(
                        category =
                            emptyCategory("IpComparison").copy(
                                evidence =
                                    listOf(
                                        EvidenceItem(
                                            source = EvidenceSource.IP_COMPARISON,
                                            detected = true,
                                            confidence = EvidenceConfidence.HIGH,
                                            description = "Regional paths report different public IPs",
                                        ),
                                    ),
                            ),
                        endpoints = emptyList(),
                        ruReflectedIps = emptySet(),
                        nonRuReflectedIps = emptySet(),
                    ),
            )

        assertEquals(
            listOf("R1", listOf(DetectionScope.LOCAL_OBSERVER_EXPOSURE), 1, false),
            listOf(
                explanation.ruleApplied,
                explanation.appliedScopes,
                explanation.uniqueSignalCount,
                DetectionScope.NETWORK_OBSERVATION in explanation.appliedScopes,
            ),
        )
    }

    @Test
    fun `R3 cross channel mismatch produces DETECTED`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
                ipConsensus = crossChannelMismatchConsensus(),
            )

        assertEquals(Verdict.DETECTED, explanation.verdict)
        assertEquals("R3", explanation.ruleApplied)
    }

    @Test
    fun `R3 consensus mismatch reports network provenance without ip comparison`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
                ipConsensus = crossChannelMismatchConsensus(),
                ipComparison = null,
            )

        assertEquals(
            Triple("R3", true, true),
            Triple(
                explanation.ruleApplied,
                DetectionScope.NETWORK_OBSERVATION in explanation.appliedScopes,
                explanation.uniqueSignalCount >= 1,
            ),
        )
    }

    @Test
    fun `R3 single channel conflict produces NEEDS_REVIEW`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
                ipConsensus =
                    IpConsensusResult(
                        observedIps = mapOf(IpConsensusChannel.GEO_IP to listOf("1.1.1.1", "2.2.2.2")),
                        channelConflicts =
                            listOf(
                                ChannelConflict(
                                    channel = IpConsensusChannel.GEO_IP,
                                    ips = listOf("1.1.1.1", "2.2.2.2"),
                                    confidence = EvidenceConfidence.HIGH,
                                ),
                            ),
                        crossChannelMismatches = emptyList(),
                        foreignIps = emptyList(),
                        warpIndicator = false,
                    ),
            )

        assertEquals(Verdict.NEEDS_REVIEW, explanation.verdict)
        assertEquals("R3", explanation.ruleApplied)
    }

    @Test
    fun `generic VPN active returns NEEDS_REVIEW`() {
        val indirect =
            emptyCategory("Indirect").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.ACTIVE_VPN,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "WireGuard",
                            kind = VpnAppKind.GENERIC_VPN,
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = indirect,
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.NEEDS_REVIEW, verdict)
    }

    @Test
    fun `targeted installed app and vpn service declaration alone need review`() {
        val direct =
            emptyCategory("Direct").copy(
                needsReview = true,
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.INSTALLED_APP,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app installed",
                            family = "xray",
                            packageName = "com.example.targeted",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                        EvidenceItem(
                            source = EvidenceSource.VPN_SERVICE_DECLARATION,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app declares VpnService",
                            family = "xray",
                            packageName = "com.example.targeted",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                    ),
            )

        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )

        assertEquals(Verdict.NEEDS_REVIEW, verdict)
    }

    @Test
    fun `inventory evidence reports one scoped logical signal`() {
        val direct =
            emptyCategory("Direct").copy(
                needsReview = true,
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.INSTALLED_APP,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app installed",
                            family = "xray",
                            packageName = "com.example.targeted",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                        EvidenceItem(
                            source = EvidenceSource.VPN_SERVICE_DECLARATION,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app declares VpnService",
                            family = "xray",
                            packageName = "com.example.targeted",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                    ),
            )

        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )

        assertEquals(
            Triple(Verdict.NEEDS_REVIEW, listOf(DetectionScope.LOCAL_INVENTORY), 1),
            Triple(explanation.verdict, explanation.appliedScopes, explanation.uniqueSignalCount),
        )
    }

    @Test
    fun `active vpn capability artifacts are one local observer signal`() {
        val direct =
            emptyCategory("Direct").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.NETWORK_CAPABILITIES,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "Active network reports TRANSPORT_VPN",
                            family = "wireguard",
                            packageName = "com.example.vpn",
                        ),
                        EvidenceItem(
                            source = EvidenceSource.NETWORK_CAPABILITIES,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "NetworkCapabilities string contains IS_VPN",
                            family = "wireguard",
                            packageName = "com.example.vpn",
                        ),
                        EvidenceItem(
                            source = EvidenceSource.NETWORK_CAPABILITIES,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "NetworkCapabilities string contains VpnTransportInfo",
                            family = "wireguard",
                            packageName = "com.example.vpn",
                        ),
                    ),
            )

        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )

        assertEquals(
            Triple(listOf(DetectionScope.LOCAL_OBSERVER_EXPOSURE), 1, false),
            Triple(
                explanation.appliedScopes,
                explanation.uniqueSignalCount,
                DetectionScope.NETWORK_OBSERVATION in explanation.appliedScopes,
            ),
        )
    }

    @Test
    fun `Russian MCC with foreign GeoIP returns DETECTED`() {
        val location =
            emptyCategory("Location").copy(
                findings = listOf(Finding("network_mcc_ru:true")),
            )
        val geoIp = emptyCategory("GeoIP").copy(needsReview = true)
        val verdict =
            VerdictEngine.evaluate(
                geoIp = geoIp,
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = location,
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `R4 flags report network provenance without evidence items`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP").copy(needsReview = true),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals =
                    emptyCategory("Location").copy(
                        findings = listOf(Finding("network_mcc_ru:true")),
                    ),
                bypassResult = emptyBypass(),
            )

        assertEquals(
            Triple("R4", true, true),
            Triple(
                explanation.ruleApplied,
                DetectionScope.NETWORK_OBSERVATION in explanation.appliedScopes,
                explanation.uniqueSignalCount >= 1,
            ),
        )
    }

    @Test
    fun `R5 all three detection axes produce DETECTED`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP").copy(needsReview = true),
                directSigns =
                    emptyCategory("Direct").copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.SYSTEM_PROXY,
                                    detected = true,
                                    confidence = EvidenceConfidence.MEDIUM,
                                    description = "HTTP proxy configured",
                                ),
                            ),
                    ),
                indirectSigns =
                    emptyCategory("Indirect").copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.ACTIVE_VPN,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "Targeted app active",
                                    kind = VpnAppKind.TARGETED_BYPASS,
                                ),
                            ),
                    ),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )

        assertEquals(Verdict.DETECTED, explanation.verdict)
        assertEquals("R5", explanation.ruleApplied)
    }

    @Test
    fun `R6 icmp alone produces NEEDS_REVIEW`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
                icmpSpoofing =
                    IcmpSpoofingResult(
                        state = IcmpSpoofingState.NEEDS_REVIEW,
                        category = emptyCategory("ICMP").copy(needsReview = true),
                    ),
            )

        assertEquals(Verdict.NEEDS_REVIEW, explanation.verdict)
        assertEquals("R6", explanation.ruleApplied)
    }

    @Test
    fun `roaming relaxation ignores CDN before rule evaluation`() {
        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals =
                    emptyCategory("Location").copy(
                        findings =
                            listOf(
                                Finding("network_mcc_ru:true"),
                                Finding("Roaming: yes"),
                                Finding("SIM MCC: 262"),
                            ),
                    ),
                bypassResult = emptyBypass(),
                cdnPulling =
                    com.poyka.ripdpi.core.detection.CdnPullingResult(
                        category =
                            emptyCategory("CDN").copy(
                                detected = true,
                                evidence =
                                    listOf(
                                        EvidenceItem(
                                            source = EvidenceSource.CDN_PULLING,
                                            detected = true,
                                            confidence = EvidenceConfidence.HIGH,
                                            description = "CDN mismatch",
                                        ),
                                    ),
                            ),
                        endpoints = emptyList(),
                    ),
            )

        assertEquals(Verdict.NOT_DETECTED, explanation.verdict)
        assertEquals("R0", explanation.ruleApplied)
    }

    @Test
    fun `targeted active with local proxy returns DETECTED`() {
        val indirect =
            emptyCategory("Indirect").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.ACTIVE_VPN,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "v2rayNG active",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                    ),
            )
        val bypass =
            emptyBypass().copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.LOCAL_PROXY,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "SOCKS5 on 10808",
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = indirect,
                locationSignals = emptyCategory("Location"),
                bypassResult = bypass,
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `score at 4 returns NEEDS_REVIEW`() {
        val direct =
            emptyCategory("Direct").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.SYSTEM_PROXY,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "HTTP proxy configured",
                        ),
                        EvidenceItem(
                            source = EvidenceSource.INSTALLED_APP,
                            detected = true,
                            confidence = EvidenceConfidence.LOW,
                            description = "Generic VPN installed",
                            kind = VpnAppKind.GENERIC_VPN,
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.NEEDS_REVIEW, verdict)
    }

    private fun crossChannelMismatchConsensus() =
        IpConsensusResult(
            observedIps =
                mapOf(
                    IpConsensusChannel.IP_COMPARISON_RU to listOf("1.2.3.4"),
                    IpConsensusChannel.IP_COMPARISON_NON_RU to listOf("5.6.7.8"),
                ),
            channelConflicts = emptyList(),
            crossChannelMismatches =
                listOf(
                    CrossChannelMismatch(
                        leftChannel = IpConsensusChannel.IP_COMPARISON_RU,
                        leftIps = listOf("1.2.3.4"),
                        rightChannel = IpConsensusChannel.IP_COMPARISON_NON_RU,
                        rightIps = listOf("5.6.7.8"),
                        confidence = EvidenceConfidence.HIGH,
                    ),
                ),
            foreignIps = emptyList(),
            warpIndicator = false,
        )
}
