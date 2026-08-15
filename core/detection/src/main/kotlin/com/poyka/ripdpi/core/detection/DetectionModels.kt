package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.consensus.IpConsensusResult
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.XrayApiScanResult

enum class EvidenceConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

enum class EvidenceSource {
    GEO_IP,
    IP_COMPARISON,
    NETWORK_CAPABILITIES,
    SYSTEM_PROXY,
    INSTALLED_APP,
    VPN_SERVICE_DECLARATION,
    ACTIVE_VPN,
    LOCAL_PROXY,
    XRAY_API,
    SPLIT_TUNNEL_BYPASS,
    NETWORK_INTERFACE,
    ROUTING,
    DNS,
    DUMPSYS,
    LOCATION_SIGNALS,
    ICMP_SPOOFING,
    RTT_TRIANGULATION,
    CDN_PULLING,
    IP_CONSENSUS,
    NATIVE_SIGNS,
    CALL_TRANSPORT,
}

enum class DetectionScope {
    LOCAL_INVENTORY,
    LOCAL_OBSERVER_EXPOSURE,
    NETWORK_OBSERVATION,
}

fun EvidenceSource.detectionScope(): DetectionScope =
    when (this) {
        EvidenceSource.INSTALLED_APP,
        EvidenceSource.VPN_SERVICE_DECLARATION,
        -> DetectionScope.LOCAL_INVENTORY

        EvidenceSource.SYSTEM_PROXY,
        EvidenceSource.ACTIVE_VPN,
        EvidenceSource.LOCAL_PROXY,
        EvidenceSource.XRAY_API,
        EvidenceSource.NETWORK_INTERFACE,
        EvidenceSource.ROUTING,
        EvidenceSource.DUMPSYS,
        EvidenceSource.NATIVE_SIGNS,
        EvidenceSource.NETWORK_CAPABILITIES,
        -> DetectionScope.LOCAL_OBSERVER_EXPOSURE

        else -> DetectionScope.NETWORK_OBSERVATION
    }

enum class VpnAppKind {
    TARGETED_BYPASS,
    GENERIC_VPN,
}

data class Finding(
    val description: String,
    val detected: Boolean = false,
    val needsReview: Boolean = false,
    val source: EvidenceSource? = null,
    val confidence: EvidenceConfidence? = null,
    val family: String? = null,
    val packageName: String? = null,
)

data class EvidenceItem(
    val source: EvidenceSource,
    val detected: Boolean,
    val confidence: EvidenceConfidence,
    val description: String,
    val family: String? = null,
    val packageName: String? = null,
    val kind: VpnAppKind? = null,
    val scope: DetectionScope = source.detectionScope(),
)

data class MatchedVpnApp(
    val packageName: String,
    val appName: String,
    val family: String?,
    val kind: VpnAppKind,
    val source: EvidenceSource,
    val active: Boolean,
    val confidence: EvidenceConfidence,
    val technicalMetadata: VpnAppTechnicalMetadata? = null,
)

data class ActiveVpnApp(
    val packageName: String?,
    val serviceName: String?,
    val family: String?,
    val kind: VpnAppKind?,
    val source: EvidenceSource,
    val confidence: EvidenceConfidence,
    val technicalMetadata: VpnAppTechnicalMetadata? = null,
)

data class VpnAppTechnicalMetadata(
    val versionName: String? = null,
    val serviceNames: List<String> = emptyList(),
    val appType: String? = null,
    val coreType: String? = null,
    val corePath: String? = null,
    val goVersion: String? = null,
    val systemApp: Boolean = false,
    val matchedByNameHeuristic: Boolean = false,
)

data class CategoryResult(
    val name: String,
    val detected: Boolean,
    val findings: List<Finding>,
    val needsReview: Boolean = false,
    val evidence: List<EvidenceItem> = emptyList(),
    val matchedApps: List<MatchedVpnApp> = emptyList(),
    val activeApps: List<ActiveVpnApp> = emptyList(),
)

enum class IpComparisonEndpointGroup {
    RU,
    NON_RU,
}

enum class IpComparisonAddressFamily {
    IPV4,
    IPV6,
}

enum class IpComparisonEndpointStatus {
    OK,
    ERROR,
    SKIPPED,
}

data class IpComparisonEndpointDescriptor(
    val label: String,
    val url: String,
    val group: IpComparisonEndpointGroup,
    val addressFamily: IpComparisonAddressFamily? = null,
)

data class IpComparisonDnsRecords(
    val aRecords: List<String> = emptyList(),
    val aaaaRecords: List<String> = emptyList(),
)

data class IpComparisonEndpointResult(
    val descriptor: IpComparisonEndpointDescriptor,
    val dnsRecords: IpComparisonDnsRecords,
    val reflectedIp: String?,
    val status: IpComparisonEndpointStatus,
    val errorMessage: String? = null,
)

data class IpComparisonResult(
    val category: CategoryResult,
    val endpoints: List<IpComparisonEndpointResult>,
    val ruReflectedIps: Set<String>,
    val nonRuReflectedIps: Set<String>,
)

enum class CdnPullingAddressFamily {
    IPV4,
    IPV6,
}

enum class CdnPullingEndpointStatus {
    OK,
    ERROR,
    SKIPPED,
}

data class CdnPullingEndpointDescriptor(
    val label: String,
    val url: String,
    val targetHost: String,
    val addressFamily: CdnPullingAddressFamily,
)

data class CdnPullingEndpointResult(
    val descriptor: CdnPullingEndpointDescriptor,
    val reflectedIp: String?,
    val status: CdnPullingEndpointStatus,
    val errorMessage: String? = null,
    val tlsMitm: Boolean = false,
)

data class CdnPullingResult(
    val category: CategoryResult,
    val endpoints: List<CdnPullingEndpointResult>,
    val actionableTargets: List<String> = emptyList(),
)

data class NativeSignsResult(
    val category: CategoryResult,
    val hiddenInterfaces: List<String> = emptyList(),
    val hookMarkers: List<String> = emptyList(),
    val rwxRegions: List<String> = emptyList(),
    val rootArtifacts: List<String> = emptyList(),
    val hasError: Boolean = false,
)

enum class CallTransportPath {
    VPN,
    UNDERLYING,
}

enum class CallTransportStunScope {
    RU,
    GLOBAL,
}

data class CallTransportStunServer(
    val host: String,
    val port: Int,
    val scope: CallTransportStunScope,
)

data class CallTransportStunRequest(
    val path: CallTransportPath,
    val server: CallTransportStunServer,
)

data class CallTransportStunObservation(
    val path: CallTransportPath,
    val server: CallTransportStunServer,
    val reflexiveAddress: String,
)

data class CallTransportResult(
    val category: CategoryResult,
    val stunObservations: List<CallTransportStunObservation> = emptyList(),
    val mtProtoReachable: Boolean = false,
    val proxyEndpoint: ProxyEndpoint? = null,
    val proxyStunReflexiveAddresses: List<String> = emptyList(),
)

enum class Verdict {
    NOT_DETECTED,
    NEEDS_REVIEW,
    DETECTED,
}

data class VerdictExplanation(
    val verdict: Verdict,
    val ruleApplied: String,
    val summary: String,
    val appliedScopes: List<DetectionScope> = emptyList(),
    val uniqueSignalCount: Int = 0,
)

data class BypassResult(
    val proxyEndpoint: ProxyEndpoint?,
    val directIp: String?,
    val proxyIp: String?,
    val xrayApiScanResult: XrayApiScanResult?,
    val mtProtoReachable: Boolean = false,
    val stunReflexiveAddresses: List<String> = emptyList(),
    val findings: List<Finding>,
    val detected: Boolean,
    val needsReview: Boolean = false,
    val evidence: List<EvidenceItem> = emptyList(),
)

enum class IcmpSpoofingState {
    OK,
    NEEDS_REVIEW,
    SKIPPED,
}

data class IcmpSpoofingResult(
    val state: IcmpSpoofingState,
    val category: CategoryResult,
    val blockedTargetAddress: String? = null,
    val blockedTargetRttMs: Double? = null,
    val controlTargetAddress: String? = null,
    val controlTargetRttMs: Double? = null,
)

enum class RttTriangulationState {
    OK,
    NEEDS_REVIEW,
    SKIPPED,
}

enum class RttTargetGroup {
    RU,
    FOREIGN,
}

data class RttTargetProbeResult(
    val host: String,
    val group: RttTargetGroup,
    val resolvedIpv4: String?,
    val rttMs: Double?,
    val jitterMs: Double?,
    val replied: Boolean,
)

data class RttTriangulationResult(
    val state: RttTriangulationState,
    val category: CategoryResult,
    val targetResults: List<RttTargetProbeResult> = emptyList(),
    val ruMedianRttMs: Double? = null,
    val foreignMedianRttMs: Double? = null,
)

data class DetectionCheckResult(
    val geoIp: CategoryResult,
    val directSigns: CategoryResult,
    val indirectSigns: CategoryResult,
    val locationSignals: CategoryResult,
    val bypassResult: BypassResult,
    val dnsLeak: CategoryResult? = null,
    val webRtcLeak: CategoryResult? = null,
    val tlsFingerprint: CategoryResult? = null,
    val timingAnalysis: CategoryResult? = null,
    val icmpSpoofing: IcmpSpoofingResult? = null,
    val ipComparison: IpComparisonResult? = null,
    val rttTriangulation: RttTriangulationResult? = null,
    val cdnPulling: CdnPullingResult? = null,
    val nativeSigns: NativeSignsResult? = null,
    val verdict: Verdict,
    val methodologyVersion: String = MethodologyVersion.CURRENT,
    val ipConsensus: IpConsensusResult? = null,
    val verdictExplanation: VerdictExplanation? = null,
    val verdictNarrative: VerdictNarrative? = null,
    val callTransport: CallTransportResult? = null,
)
