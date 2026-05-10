package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CdnPullingAddressFamily
import com.poyka.ripdpi.core.detection.CdnPullingEndpointDescriptor
import com.poyka.ripdpi.core.detection.CdnPullingEndpointResult
import com.poyka.ripdpi.core.detection.CdnPullingEndpointStatus
import com.poyka.ripdpi.core.detection.CdnPullingResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.IcmpSpoofingResult
import com.poyka.ripdpi.core.detection.IcmpSpoofingState
import com.poyka.ripdpi.core.detection.IpComparisonAddressFamily
import com.poyka.ripdpi.core.detection.IpComparisonDnsRecords
import com.poyka.ripdpi.core.detection.IpComparisonEndpointDescriptor
import com.poyka.ripdpi.core.detection.IpComparisonEndpointGroup
import com.poyka.ripdpi.core.detection.IpComparisonEndpointResult
import com.poyka.ripdpi.core.detection.IpComparisonEndpointStatus
import com.poyka.ripdpi.core.detection.IpComparisonResult
import com.poyka.ripdpi.core.detection.NativeSignsResult
import com.poyka.ripdpi.core.detection.RttTargetGroup
import com.poyka.ripdpi.core.detection.RttTargetProbeResult
import com.poyka.ripdpi.core.detection.RttTriangulationResult
import com.poyka.ripdpi.core.detection.RttTriangulationState
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VerdictExplanation
import com.poyka.ripdpi.core.detection.VerdictNarrativeBuilder
import com.poyka.ripdpi.core.detection.consensus.IpConsensusBuilder
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.core.detection.probe.XrayApiEndpoint
import com.poyka.ripdpi.core.detection.probe.XrayApiScanResult
import com.poyka.ripdpi.core.detection.probe.XrayOutboundSummary

internal const val FixturePublicIp = "5.6.7.8"
internal const val FixtureUuid = "11111111-2222-3333-4444-555555555555"
internal const val FixturePublicKey = "public-key-secret"

internal fun detectionExportFixture(): DetectionCheckResult {
    val geoIp =
        category(
            name = "GeoIP",
            detected = true,
            findings =
                listOf(
                    Finding("Observed public IP: $FixturePublicIp", detected = true, source = EvidenceSource.GEO_IP),
                ),
            evidence = listOf(evidence(EvidenceSource.GEO_IP, "GeoIP provider returned $FixturePublicIp")),
        )
    val bypass =
        BypassResult(
            proxyEndpoint = ProxyEndpoint("127.0.0.1", 10808, ProxyType.SOCKS5),
            directIp = FixturePublicIp,
            proxyIp = "198.51.100.40",
            xrayApiScanResult =
                XrayApiScanResult(
                    endpoint = XrayApiEndpoint("127.0.0.1", 10085),
                    outbounds =
                        listOf(
                            XrayOutboundSummary(
                                tag = "proxy",
                                protocolName = "vless",
                                address = "203.0.113.20",
                                port = 443,
                                uuid = FixtureUuid,
                                sni = "example.test",
                                publicKey = FixturePublicKey,
                                senderSettingsType = "tcp",
                                proxySettingsType = "reality",
                            ),
                        ),
                ),
            mtProtoReachable = true,
            stunReflexiveAddresses = listOf("198.51.100.41"),
            findings =
                listOf(
                    Finding("Xray API endpoint reachable", detected = true, source = EvidenceSource.XRAY_API),
                ),
            detected = true,
            evidence = listOf(evidence(EvidenceSource.XRAY_API, "Xray API endpoint reachable")),
        )
    val result =
        DetectionCheckResult(
            geoIp = geoIp,
            directSigns = category("DirectSigns"),
            indirectSigns = category("IndirectSigns"),
            locationSignals =
                category(
                    name = "LocationSignals",
                    findings =
                        listOf(
                            Finding("network_mcc_ru:true"),
                            Finding("Roaming: yes"),
                            Finding("SIM MCC: 262"),
                        ),
                ),
            bypassResult = bypass,
            icmpSpoofing =
                IcmpSpoofingResult(
                    state = IcmpSpoofingState.NEEDS_REVIEW,
                    category = category("IcmpSpoofing", needsReview = true),
                ),
            ipComparison =
                IpComparisonResult(
                    category = category("IpComparison", detected = true),
                    endpoints =
                        listOf(
                            IpComparisonEndpointResult(
                                descriptor =
                                    IpComparisonEndpointDescriptor(
                                        label = "ru",
                                        url = "https://ru.example",
                                        group = IpComparisonEndpointGroup.RU,
                                        addressFamily = IpComparisonAddressFamily.IPV4,
                                    ),
                                dnsRecords = IpComparisonDnsRecords(aRecords = listOf("203.0.113.10")),
                                reflectedIp = FixturePublicIp,
                                status = IpComparisonEndpointStatus.OK,
                            ),
                        ),
                    ruReflectedIps = setOf(FixturePublicIp),
                    nonRuReflectedIps = setOf("198.51.100.40"),
                ),
            rttTriangulation =
                RttTriangulationResult(
                    state = RttTriangulationState.NEEDS_REVIEW,
                    category = category("RttTriangulation", needsReview = true),
                    targetResults =
                        listOf(
                            RttTargetProbeResult(
                                host = "ru.example",
                                group = RttTargetGroup.RU,
                                resolvedIpv4 = FixturePublicIp,
                                rttMs = 45.0,
                                jitterMs = 4.0,
                                replied = true,
                            ),
                        ),
                ),
            cdnPulling =
                CdnPullingResult(
                    category = category("CdnPulling", needsReview = true),
                    endpoints =
                        listOf(
                            CdnPullingEndpointResult(
                                descriptor =
                                    CdnPullingEndpointDescriptor(
                                        label = "cdn",
                                        url = "https://cdn.example",
                                        targetHost = "target.example",
                                        addressFamily = CdnPullingAddressFamily.IPV4,
                                    ),
                                reflectedIp = "198.51.100.42",
                                status = CdnPullingEndpointStatus.OK,
                                tlsMitm = true,
                            ),
                        ),
                ),
            nativeSigns = NativeSignsResult(category = category("NativeSigns", needsReview = true)),
            verdict = Verdict.DETECTED,
            ipConsensus =
                IpConsensusBuilder.build(
                    geoIp = geoIp,
                    bypassResult = bypass,
                    ipComparison = null,
                    cdnPulling = null,
                ),
            verdictExplanation = VerdictExplanation(Verdict.DETECTED, "R1", "Hard bypass evidence detected"),
        )
    return result.copy(verdictNarrative = VerdictNarrativeBuilder.build(result))
}

private fun category(
    name: String,
    detected: Boolean = false,
    needsReview: Boolean = false,
    findings: List<Finding> = emptyList(),
    evidence: List<EvidenceItem> = emptyList(),
): CategoryResult =
    CategoryResult(
        name = name,
        detected = detected,
        needsReview = needsReview,
        findings = findings,
        evidence = evidence,
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
