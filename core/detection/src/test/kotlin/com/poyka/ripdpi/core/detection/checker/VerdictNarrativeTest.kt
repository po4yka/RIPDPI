package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.ExposureStatus
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VerdictExplanation
import com.poyka.ripdpi.core.detection.consensus.IpConsensusChannel
import com.poyka.ripdpi.core.detection.consensus.IpConsensusResult
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictNarrativeTest {
    @Test
    fun `remote endpoint discovered when xray API present`() {
        val narrative =
            VerdictEngine.narrative(
                resultWithBypassEvidence(EvidenceSource.XRAY_API),
            )

        assertEquals(ExposureStatus.REMOTE_ENDPOINT_DISCOVERED, narrative.exposureStatus)
    }

    @Test
    fun `public ip only when geo signal but no endpoint`() {
        val narrative =
            VerdictEngine.narrative(
                cleanResult().copy(
                    geoIp = category("GeoIP", detected = true),
                    verdict = Verdict.DETECTED,
                ),
            )

        assertEquals(ExposureStatus.PUBLIC_IP_ONLY, narrative.exposureStatus)
    }

    @Test
    fun `local proxy or api only when local proxy is present`() {
        val narrative =
            VerdictEngine.narrative(
                cleanResult().copy(
                    bypassResult =
                        cleanBypass().copy(
                            proxyEndpoint = ProxyEndpoint("127.0.0.1", 10808, ProxyType.SOCKS5),
                            evidence =
                                listOf(
                                    evidence(EvidenceSource.LOCAL_PROXY, "SOCKS proxy"),
                                ),
                        ),
                    verdict = Verdict.NEEDS_REVIEW,
                ),
            )

        assertEquals(ExposureStatus.LOCAL_PROXY_OR_API_ONLY, narrative.exposureStatus)
    }

    @Test
    fun `technical signal only when indirect app signal is present`() {
        val narrative =
            VerdictEngine.narrative(
                cleanResult().copy(
                    indirectSigns =
                        category("Indirect").copy(
                            evidence = listOf(evidence(EvidenceSource.ACTIVE_VPN, "VPN service active")),
                        ),
                    verdict = Verdict.NEEDS_REVIEW,
                ),
            )

        assertEquals(ExposureStatus.TECHNICAL_SIGNAL_ONLY, narrative.exposureStatus)
    }

    @Test
    fun `insufficient data when no positive signals`() {
        val narrative = VerdictEngine.narrative(cleanResult())

        assertEquals(ExposureStatus.INSUFFICIENT_DATA, narrative.exposureStatus)
    }

    @Test
    fun `reason rows contain xray api reason when xray found`() {
        val narrative = VerdictEngine.narrative(resultWithBypassEvidence(EvidenceSource.XRAY_API))

        assertTrue(narrative.reasonRows.any { it.label == "Xray API" })
    }

    @Test
    fun `reason rows capped at five`() {
        val narrative =
            VerdictEngine.narrative(
                cleanResult().copy(
                    geoIp = category("GeoIP", detected = true),
                    directSigns =
                        category("Direct").copy(
                            evidence =
                                listOf(
                                    evidence(EvidenceSource.NETWORK_CAPABILITIES, "VPN transport"),
                                    evidence(EvidenceSource.SYSTEM_PROXY, "System proxy"),
                                ),
                        ),
                    indirectSigns =
                        category("Indirect").copy(
                            evidence =
                                listOf(
                                    evidence(EvidenceSource.ACTIVE_VPN, "Active VPN"),
                                    evidence(EvidenceSource.INSTALLED_APP, "Installed bypass app"),
                                ),
                        ),
                    locationSignals =
                        category("Location").copy(
                            evidence = listOf(evidence(EvidenceSource.LOCATION_SIGNALS, "MCC conflict")),
                        ),
                    bypassResult =
                        cleanBypass().copy(
                            mtProtoReachable = true,
                            stunReflexiveAddresses = listOf("198.51.100.20"),
                            evidence =
                                listOf(
                                    evidence(EvidenceSource.XRAY_API, "Xray API"),
                                    evidence(EvidenceSource.SPLIT_TUNNEL_BYPASS, "Split tunnel"),
                                    evidence(EvidenceSource.LOCAL_PROXY, "Local proxy"),
                                ),
                        ),
                    verdict = Verdict.DETECTED,
                ),
            )

        assertTrue(narrative.reasonRows.size <= 5)
    }

    @Test
    fun `home routed roaming note non null when flag set`() {
        val narrative =
            VerdictEngine.narrative(
                cleanResult().copy(
                    locationSignals =
                        category("Location").copy(
                            findings =
                                listOf(
                                    Finding("network_mcc_ru:true"),
                                    Finding("Roaming: yes"),
                                    Finding("SIM MCC: 262"),
                                ),
                        ),
                ),
            )

        assertNotNull(narrative.homeRoutedRoamingNote)
    }

    @Test
    fun `discovered rows capped at ten`() {
        val narrative =
            VerdictEngine.narrative(
                cleanResult().copy(
                    bypassResult =
                        cleanBypass().copy(
                            proxyEndpoint = ProxyEndpoint("127.0.0.1", 10808, ProxyType.SOCKS5),
                            directIp = "203.0.113.1",
                            proxyIp = "198.51.100.2",
                        ),
                    ipConsensus =
                        IpConsensusResult(
                            observedIps =
                                mapOf(
                                    IpConsensusChannel.GEO_IP to listOf("203.0.113.3"),
                                    IpConsensusChannel.IP_COMPARISON_RU to listOf("203.0.113.4", "203.0.113.5"),
                                    IpConsensusChannel.IP_COMPARISON_NON_RU to listOf("198.51.100.6"),
                                    IpConsensusChannel.BYPASS_DIRECT to listOf("203.0.113.7"),
                                    IpConsensusChannel.BYPASS_PROXY to listOf("198.51.100.8"),
                                    IpConsensusChannel.BYPASS_STUN to listOf("198.51.100.9", "198.51.100.10"),
                                ),
                            channelConflicts = emptyList(),
                            crossChannelMismatches = emptyList(),
                            foreignIps = listOf("198.51.100.6", "198.51.100.8"),
                            warpIndicator = true,
                        ),
                ),
            )

        assertTrue(narrative.discoveredRows.size <= 10)
    }

    private fun resultWithBypassEvidence(source: EvidenceSource): DetectionCheckResult =
        cleanResult().copy(
            bypassResult =
                cleanBypass().copy(
                    evidence = listOf(evidence(source, "Xray API found")),
                ),
            verdict = Verdict.DETECTED,
            verdictExplanation = VerdictExplanation(Verdict.DETECTED, "R1", "Hard bypass evidence detected"),
        )

    private fun cleanResult(): DetectionCheckResult =
        DetectionCheckResult(
            geoIp = category("GeoIP"),
            directSigns = category("Direct"),
            indirectSigns = category("Indirect"),
            locationSignals = category("Location"),
            bypassResult = cleanBypass(),
            verdict = Verdict.NOT_DETECTED,
            verdictExplanation = VerdictExplanation(Verdict.NOT_DETECTED, "R0", "No detection rule matched"),
        )

    private fun cleanBypass(): BypassResult =
        BypassResult(
            proxyEndpoint = null,
            directIp = null,
            proxyIp = null,
            xrayApiScanResult = null,
            findings = emptyList(),
            detected = false,
        )

    private fun category(
        name: String,
        detected: Boolean = false,
    ): CategoryResult =
        CategoryResult(
            name = name,
            detected = detected,
            needsReview = false,
            findings = emptyList(),
        )

    private fun evidence(
        source: EvidenceSource,
        description: String,
    ): EvidenceItem =
        EvidenceItem(
            source = source,
            detected = true,
            confidence = EvidenceConfidence.HIGH,
            description = description,
        )
}
