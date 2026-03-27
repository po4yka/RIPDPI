package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeNetworkSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class ScanPathMode {
    RAW_PATH,
    IN_PATH,
}

@Serializable
enum class ScanKind {
    CONNECTIVITY,
    STRATEGY_PROBE,
}

@Serializable
enum class DiagnosticProfileFamily {
    GENERAL,
    WEB_CONNECTIVITY,
    MESSAGING,
    CIRCUMVENTION,
    THROTTLING,
    DPI_FULL,
    AUTOMATIC_PROBING,
    AUTOMATIC_AUDIT,
}

@Serializable
data class DomainTarget(
    val host: String,
    val connectIp: String? = null,
    val httpsPort: Int? = null,
    val httpPort: Int? = null,
    val httpPath: String = "/",
)

@Serializable
data class DnsTarget(
    val domain: String,
    val udpServer: String? = null,
    val encryptedResolverId: String? = null,
    val encryptedProtocol: String? = null,
    val encryptedHost: String? = null,
    val encryptedPort: Int? = null,
    val encryptedTlsServerName: String? = null,
    val encryptedBootstrapIps: List<String> = emptyList(),
    val encryptedDohUrl: String? = null,
    val encryptedDnscryptProviderName: String? = null,
    val encryptedDnscryptPublicKey: String? = null,
    val dohUrl: String? = null,
    val dohBootstrapIps: List<String> = emptyList(),
    val expectedIps: List<String> = emptyList(),
)

@Serializable
data class TcpTarget(
    val id: String,
    val provider: String,
    val ip: String,
    val port: Int = 443,
    val sni: String? = null,
    val asn: String? = null,
    val hostHeader: String? = null,
    val fatHeaderRequests: Int? = null,
)

@Serializable
data class QuicTarget(
    val host: String,
    val connectIp: String? = null,
    val port: Int = 443,
)

@Serializable
data class TelegramDcEndpoint(
    val ip: String,
    val label: String,
    val port: Int = 443,
)

@Serializable
data class TelegramTarget(
    val mediaUrl: String,
    val uploadIp: String,
    val uploadPort: Int = 443,
    val dcEndpoints: List<TelegramDcEndpoint> = emptyList(),
    val stallTimeoutMs: Long = 10_000,
    val totalTimeoutMs: Long = 60_000,
    val uploadSizeBytes: Int = 10_485_760,
)

@Serializable
data class StrategyProbeRequest(
    val suiteId: String = "quick_v1",
    val baseProxyConfigJson: String? = null,
)

@Serializable
data class ServiceTarget(
    val id: String,
    val service: String,
    val bootstrapUrl: String? = null,
    val mediaUrl: String? = null,
    val tcpEndpointHost: String? = null,
    val tcpEndpointIp: String? = null,
    val tcpEndpointPort: Int = 443,
    val tlsServerName: String? = null,
    val quicHost: String? = null,
    val quicConnectIp: String? = null,
    val quicPort: Int = 443,
)

@Serializable
data class CircumventionTarget(
    val id: String,
    val tool: String,
    val bootstrapUrl: String? = null,
    val handshakeHost: String? = null,
    val handshakeIp: String? = null,
    val handshakePort: Int = 443,
    val tlsServerName: String? = null,
)

@Serializable
data class ThroughputTarget(
    val id: String,
    val label: String,
    val url: String,
    val connectIp: String? = null,
    val port: Int? = null,
    val isControl: Boolean = false,
    val windowBytes: Int = 8_388_608,
    val runs: Int = 2,
)

@Serializable
data class Diagnosis(
    val code: String,
    val summary: String,
    val severity: String = "warning",
    val target: String? = null,
    val evidence: List<String> = emptyList(),
)

@Serializable
enum class ObservationKind {
    DNS,
    DOMAIN,
    TCP,
    QUIC,
    SERVICE,
    CIRCUMVENTION,
    TELEGRAM,
    THROUGHPUT,
    STRATEGY,
}

@Serializable
enum class TransportFailureKind {
    NONE,
    TIMEOUT,
    RESET,
    CLOSE,
    ALERT,
    CERTIFICATE,
    OTHER,
}

@Serializable
enum class DnsObservationStatus {
    MATCH,
    EXPECTED_MISMATCH,
    SUBSTITUTION,
    ENCRYPTED_BLOCKED,
    UDP_BLOCKED,
    UNAVAILABLE,
}

@Serializable
enum class HttpProbeStatus {
    OK,
    BLOCKPAGE,
    UNREACHABLE,
    NOT_RUN,
}

@Serializable
enum class TlsProbeStatus {
    OK,
    HANDSHAKE_FAILED,
    VERSION_SPLIT,
    CERT_INVALID,
    NOT_RUN,
}

@Serializable
enum class TcpProbeStatus {
    OK,
    CONNECT_FAILED,

    @SerialName("BLOCKED16_KB")
    BLOCKED_16KB,
    WHITELIST_SNI_OK,
    ERROR,
}

@Serializable
enum class QuicProbeStatus {
    INITIAL_RESPONSE,
    RESPONSE,
    EMPTY,
    ERROR,
    NOT_RUN,
}

@Serializable
enum class EndpointProbeStatus {
    OK,
    FAILED,
    BLOCKED,
    NOT_RUN,
}

@Serializable
enum class TelegramVerdict {
    OK,
    SLOW,
    PARTIAL,
    BLOCKED,
    ERROR,
}

@Serializable
enum class TelegramTransferStatus {
    OK,
    SLOW,
    STALLED,
    BLOCKED,
    ERROR,
}

@Serializable
enum class ThroughputProbeStatus {
    MEASURED,
    HTTP_UNREACHABLE,
    INVALID_TARGET,
}

@Serializable
enum class StrategyProbeProtocol {
    HTTP,
    HTTPS,
    QUIC,
    CANDIDATE,
    BASELINE,
}

@Serializable
enum class StrategyProbeStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
    SKIPPED,
    NOT_APPLICABLE,
}

@Serializable
data class DnsObservationFact(
    val domain: String,
    val status: DnsObservationStatus,
    val udpAddresses: List<String> = emptyList(),
    val encryptedAddresses: List<String> = emptyList(),
)

@Serializable
data class DomainObservationFact(
    val host: String,
    val httpStatus: HttpProbeStatus = HttpProbeStatus.NOT_RUN,
    val tls13Status: TlsProbeStatus = TlsProbeStatus.NOT_RUN,
    val tls12Status: TlsProbeStatus = TlsProbeStatus.NOT_RUN,
    val tlsEchStatus: TlsProbeStatus = TlsProbeStatus.NOT_RUN,
    val tlsEchVersion: String? = null,
    val tlsEchError: String? = null,
    val transportFailure: TransportFailureKind = TransportFailureKind.NONE,
    val certificateAnomaly: Boolean = false,
)

@Serializable
data class TcpObservationFact(
    val provider: String,
    val status: TcpProbeStatus,
    val selectedSni: String? = null,
    val bytesSent: Int? = null,
    val responsesSeen: Int? = null,
)

@Serializable
data class QuicObservationFact(
    val host: String,
    val status: QuicProbeStatus,
    val transportFailure: TransportFailureKind = TransportFailureKind.NONE,
)

@Serializable
data class ServiceObservationFact(
    val service: String,
    val bootstrapStatus: HttpProbeStatus = HttpProbeStatus.NOT_RUN,
    val mediaStatus: HttpProbeStatus = HttpProbeStatus.NOT_RUN,
    val endpointStatus: EndpointProbeStatus = EndpointProbeStatus.NOT_RUN,
    val endpointFailure: TransportFailureKind = TransportFailureKind.NONE,
    val quicStatus: QuicProbeStatus = QuicProbeStatus.NOT_RUN,
    val quicFailure: TransportFailureKind = TransportFailureKind.NONE,
)

@Serializable
data class CircumventionObservationFact(
    val tool: String,
    val bootstrapStatus: HttpProbeStatus = HttpProbeStatus.NOT_RUN,
    val handshakeStatus: EndpointProbeStatus = EndpointProbeStatus.NOT_RUN,
    val handshakeFailure: TransportFailureKind = TransportFailureKind.NONE,
)

@Serializable
data class TelegramObservationFact(
    val verdict: TelegramVerdict,
    val qualityScore: Int = 0,
    val downloadStatus: TelegramTransferStatus = TelegramTransferStatus.ERROR,
    val uploadStatus: TelegramTransferStatus = TelegramTransferStatus.ERROR,
    val dcReachable: Int = 0,
    val dcTotal: Int = 0,
)

@Serializable
data class ThroughputObservationFact(
    val label: String,
    val status: ThroughputProbeStatus,
    val isControl: Boolean = false,
    val medianBps: Long = 0,
    val sampleBps: List<Long> = emptyList(),
    val windowBytes: Int = 0,
)

@Serializable
data class StrategyObservationFact(
    val candidateId: String? = null,
    val candidateLabel: String? = null,
    val candidateFamily: String? = null,
    val protocol: StrategyProbeProtocol = StrategyProbeProtocol.CANDIDATE,
    val status: StrategyProbeStatus = StrategyProbeStatus.FAILED,
    val tlsEchStatus: TlsProbeStatus = TlsProbeStatus.NOT_RUN,
    val tlsEchVersion: String? = null,
    val tlsEchError: String? = null,
    val transportFailure: TransportFailureKind = TransportFailureKind.NONE,
)

@Serializable
data class ObservationFact(
    val kind: ObservationKind,
    val target: String,
    val dns: DnsObservationFact? = null,
    val domain: DomainObservationFact? = null,
    val tcp: TcpObservationFact? = null,
    val quic: QuicObservationFact? = null,
    val service: ServiceObservationFact? = null,
    val circumvention: CircumventionObservationFact? = null,
    val telegram: TelegramObservationFact? = null,
    val throughput: ThroughputObservationFact? = null,
    val strategy: StrategyObservationFact? = null,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ScanRequest(
    val profileId: String,
    val displayName: String,
    val pathMode: ScanPathMode,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
    val family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    val regionTag: String? = null,
    val manualOnly: Boolean = false,
    val packRefs: List<String> = emptyList(),
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val domainTargets: List<DomainTarget> = emptyList(),
    val dnsTargets: List<DnsTarget> = emptyList(),
    val tcpTargets: List<TcpTarget> = emptyList(),
    val quicTargets: List<QuicTarget> = emptyList(),
    val serviceTargets: List<ServiceTarget> = emptyList(),
    val circumventionTargets: List<CircumventionTarget> = emptyList(),
    val throughputTargets: List<ThroughputTarget> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
    val telegramTarget: TelegramTarget? = null,
    val strategyProbe: StrategyProbeRequest? = null,
    val networkSnapshot: NativeNetworkSnapshot? = null,
)

@Serializable
data class ScanProgress(
    val sessionId: String,
    val phase: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val message: String,
    val isFinished: Boolean = false,
    val latestProbeTarget: String? = null,
    val latestProbeOutcome: String? = null,
    val strategyProbeProgress: StrategyProbeLiveProgress? = null,
)

@Serializable
enum class StrategyProbeProgressLane {
    TCP,
    QUIC,
}

@Serializable
data class StrategyProbeLiveProgress(
    val lane: StrategyProbeProgressLane,
    val candidateIndex: Int,
    val candidateTotal: Int,
    val candidateId: String,
    val candidateLabel: String,
)

@Serializable
data class ProbeDetail(
    val key: String,
    val value: String,
)

@Serializable
data class ProbeResult(
    val probeType: String,
    val target: String,
    val outcome: String,
    val details: List<ProbeDetail> = emptyList(),
    val probeRetryCount: Int? = null,
) {
    constructor(
        id: String,
        sessionId: String,
        probeType: String,
        target: String,
        outcome: String,
        detailJson: String,
        createdAt: Long,
    ) : this(
        probeType = probeType,
        target = target,
        outcome = outcome,
        details = decodeProbeDetailsCompat(detailJson),
        probeRetryCount = null,
    )
}

@Serializable
data class ScanReport(
    val sessionId: String,
    val profileId: String,
    val pathMode: ScanPathMode,
    val startedAt: Long,
    val finishedAt: Long,
    val summary: String,
    val results: List<ProbeResult> = emptyList(),
    val resolverRecommendation: ResolverRecommendation? = null,
    val strategyProbeReport: StrategyProbeReport? = null,
    val observations: List<ObservationFact> = emptyList(),
    val engineAnalysisVersion: String? = null,
    val diagnoses: List<Diagnosis> = emptyList(),
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
)

@Serializable
data class ResolverRecommendation(
    val triggerOutcome: String,
    val selectedResolverId: String,
    val selectedProtocol: String,
    val selectedEndpoint: String,
    val selectedBootstrapIps: List<String> = emptyList(),
    val selectedHost: String = "",
    val selectedPort: Int = 0,
    val selectedTlsServerName: String = "",
    val selectedDohUrl: String = "",
    val selectedDnscryptProviderName: String = "",
    val selectedDnscryptPublicKey: String = "",
    val rationale: String,
    val appliedTemporarily: Boolean = false,
    val persistable: Boolean = false,
)

@Serializable
enum class StrategyProbeAuditConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
data class StrategyProbeAuditCoverage(
    val tcpCandidatesPlanned: Int,
    val tcpCandidatesExecuted: Int,
    val tcpCandidatesSkipped: Int,
    val tcpCandidatesNotApplicable: Int,
    val quicCandidatesPlanned: Int,
    val quicCandidatesExecuted: Int,
    val quicCandidatesSkipped: Int,
    val quicCandidatesNotApplicable: Int,
    val tcpWinnerSucceededTargets: Int,
    val tcpWinnerTotalTargets: Int,
    val quicWinnerSucceededTargets: Int,
    val quicWinnerTotalTargets: Int,
    val matrixCoveragePercent: Int,
    val winnerCoveragePercent: Int,
)

@Serializable
data class StrategyProbeAuditConfidence(
    val level: StrategyProbeAuditConfidenceLevel,
    val score: Int,
    val rationale: String,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class StrategyProbeAuditAssessment(
    val dnsShortCircuited: Boolean = false,
    val coverage: StrategyProbeAuditCoverage,
    val confidence: StrategyProbeAuditConfidence,
)

@Serializable
data class StrategyProbeReport(
    val suiteId: String,
    val tcpCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
    val quicCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
    val recommendation: StrategyProbeRecommendation,
    val auditAssessment: StrategyProbeAuditAssessment? = null,
)

@Serializable
data class StrategyProbeCandidateSummary(
    val id: String,
    val label: String,
    val family: String,
    val outcome: String,
    val rationale: String,
    val succeededTargets: Int,
    val totalTargets: Int,
    val weightedSuccessScore: Int,
    val totalWeight: Int,
    val qualityScore: Int,
    val proxyConfigJson: String? = null,
    val notes: List<String> = emptyList(),
    val averageLatencyMs: Long? = null,
    val skipped: Boolean = false,
)

@Serializable
data class StrategyProbeRecommendation(
    val tcpCandidateId: String,
    val tcpCandidateLabel: String,
    val tcpCandidateFamily: String? = null,
    val quicCandidateId: String,
    val quicCandidateLabel: String,
    val quicCandidateFamily: String? = null,
    val dnsStrategyFamily: String? = null,
    val dnsStrategyLabel: String? = null,
    val rationale: String,
    val recommendedProxyConfigJson: String,
    val strategySignature: BypassStrategySignature? = null,
)

@Serializable
data class NativeSessionEvent(
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val subsystem: String? = null,
)

@Serializable
data class PassiveMonitorSnapshot(
    val activeMode: String?,
    val connectionState: String,
    val networkType: String,
    val publicIp: String?,
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
    val nativeEvents: List<NativeSessionEvent> = emptyList(),
    val capturedAt: Long,
)

@Serializable
data class NetworkSnapshotModel(
    val transport: String,
    val capabilities: List<String>,
    val dnsServers: List<String>,
    val privateDnsMode: String,
    val mtu: Int?,
    val localAddresses: List<String>,
    val publicIp: String?,
    val publicAsn: String?,
    val captivePortalDetected: Boolean,
    val networkValidated: Boolean,
    val wifiDetails: WifiNetworkDetails? = null,
    val cellularDetails: CellularNetworkDetails? = null,
    val capturedAt: Long,
)

@Serializable
data class WifiNetworkDetails(
    val ssid: String = "unknown",
    val bssid: String = "unknown",
    val hiddenSsid: Boolean? = null,
    val frequencyMhz: Int? = null,
    val band: String = "unknown",
    val channelWidth: String = "unknown",
    val wifiStandard: String = "unknown",
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    val txLinkSpeedMbps: Int? = null,
    val networkId: Int? = null,
    val isPasspoint: Boolean? = null,
    val isOsuAp: Boolean? = null,
    val gateway: String? = null,
    val dhcpServer: String? = null,
    val ipAddress: String? = null,
    val subnetMask: String? = null,
    val leaseDurationSeconds: Int? = null,
)

@Serializable
data class CellularNetworkDetails(
    val carrierName: String = "unknown",
    val simOperatorName: String = "unknown",
    val networkOperatorName: String = "unknown",
    val networkCountryIso: String = "unknown",
    val simCountryIso: String = "unknown",
    val operatorCode: String = "unknown",
    val simOperatorCode: String = "unknown",
    val dataNetworkType: String = "unknown",
    val voiceNetworkType: String = "unknown",
    val dataState: String = "unknown",
    val serviceState: String = "unknown",
    val isNetworkRoaming: Boolean? = null,
    val carrierId: Int? = null,
    val simCarrierId: Int? = null,
    val signalLevel: Int? = null,
    val signalDbm: Int? = null,
)

@Serializable
data class ServiceContextModel(
    val serviceStatus: String,
    val configuredMode: String,
    val activeMode: String,
    val selectedProfileId: String,
    val selectedProfileName: String,
    val configSource: String,
    val proxyEndpoint: String,
    val desyncMethod: String,
    val chainSummary: String,
    val routeGroup: String,
    val sessionUptimeMs: Long?,
    val lastNativeErrorHeadline: String,
    val restartCount: Int,
    val hostAutolearnEnabled: String,
    val learnedHostCount: Int,
    val penalizedHostCount: Int,
    val lastAutolearnHost: String,
    val lastAutolearnGroup: String,
    val lastAutolearnAction: String,
)

@Serializable
data class PermissionContextModel(
    val vpnPermissionState: String,
    val notificationPermissionState: String,
    val batteryOptimizationState: String,
    val dataSaverState: String,
)

@Serializable
data class DeviceContextModel(
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: String,
    val androidVersion: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val primaryAbi: String,
    val locale: String,
    val timezone: String,
)

@Serializable
data class EnvironmentContextModel(
    val batterySaverState: String,
    val powerSaveModeState: String,
    val networkMeteredState: String,
    val roamingState: String,
)

@Serializable
data class DiagnosticContextModel(
    val service: ServiceContextModel,
    val permissions: PermissionContextModel,
    val device: DeviceContextModel,
    val environment: EnvironmentContextModel,
)

data class DiagnosticsArchive(
    val fileName: String,
    val absolutePath: String,
    val sessionId: String?,
    val createdAt: Long,
    val scope: String,
    val schemaVersion: Int,
    val privacyMode: String,
)

data class SummaryMetric(
    val label: String,
    val value: String,
)

data class DiagnosticSessionDetail(
    val session: DiagnosticScanSession,
    val results: List<ProbeResult>,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val events: List<DiagnosticEvent>,
    val context: DiagnosticContextSnapshot?,
)

data class ShareSummary(
    val title: String,
    val body: String,
    val compactMetrics: List<SummaryMetric> = emptyList(),
)

@Serializable
data class BundledDiagnosticProfile(
    val id: String,
    val name: String,
    val version: Int,
    val request: ScanRequest,
)

@Serializable
data class BundledDiagnosticsCatalog(
    val schemaVersion: Int = 1,
    val generatedAt: String? = null,
    val profiles: List<BundledDiagnosticProfile> = emptyList(),
)

fun deriveProbeRetryCount(details: List<ProbeDetail>): Int? {
    val detailMap = details.associate { it.key to it.value }
    detailMap["probeRetryCount"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
    detailMap["retryCount"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
    val attempts =
        detailMap["attempts"]
            ?.split('|')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.size
            ?: return null
    return (attempts - 1).takeIf { it > 0 }
}

fun ProbeResult.withDerivedProbeRetryCount(): ProbeResult =
    copy(probeRetryCount = probeRetryCount ?: deriveProbeRetryCount(details))

private val modelsCompatibilityJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

private fun decodeProbeDetailsCompat(payload: String): List<ProbeDetail> =
    runCatching {
        modelsCompatibilityJson.decodeFromString(
            ListSerializer(ProbeDetail.serializer()),
            payload,
        )
    }.getOrElse { emptyList() }
