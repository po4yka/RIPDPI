package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeNetworkSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val connectIps: List<String> = emptyList(),
    val httpsPort: Int? = null,
    val httpPort: Int? = null,
    val httpPath: String = "/",
    val isControl: Boolean = false,
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
    val connectIps: List<String> = emptyList(),
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
    val targetSelection: StrategyProbeTargetSelection? = null,
    val maxCandidates: Int? = null,
)

@Serializable
data class StrategyProbeTargetSelection(
    val cohortId: String,
    val cohortLabel: String,
    val domainHosts: List<String> = emptyList(),
    val quicHosts: List<String> = emptyList(),
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
    val connectIps: List<String> = emptyList(),
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
    val recommendation: String? = null,
    val controlValidated: Boolean? = null,
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
    ANSWER_DIVERGENCE,
    SUBSTITUTION,
    NXDOMAIN,
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
    val udpLatencyMs: Long? = null,
    val encryptedLatencyMs: Long? = null,
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
    val tlsEchResolutionDetail: String? = null,
    val transportFailure: TransportFailureKind = TransportFailureKind.NONE,
    val tlsError: String? = null,
    val certificateAnomaly: Boolean = false,
    val isControl: Boolean = false,
    val h3Advertised: Boolean = false,
    val altSvc: String? = null,
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
    val tlsEchResolutionDetail: String? = null,
    val transportFailure: TransportFailureKind = TransportFailureKind.NONE,
    val tlsError: String? = null,
    val h3Advertised: Boolean = false,
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
    val succeededTargets: Int = 0,
    val totalTargets: Int = 0,
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
    @Suppress("UnusedPrivateProperty")
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
data class StrategyRecommendation(
    val triggerOutcomes: List<String>,
    val recommendedFamily: String,
    val blockingPattern: String,
    val rationale: String,
    val evidence: List<String> = emptyList(),
    val actionable: Boolean = true,
)

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
    val strategyRecommendation: StrategyRecommendation? = null,
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
    val tcpWinnerCoveragePercent: Int = 0,
    val quicWinnerCoveragePercent: Int = 0,
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
enum class StrategyProbeCompletionKind {
    NORMAL,
    DNS_SHORT_CIRCUITED,
    DNS_TAMPERING_WITH_FALLBACK,
    PARTIAL_RESULTS,
}

@Serializable
enum class StrategyEmitterTier {
    NON_ROOT_PRODUCTION,
    ROOTED_PRODUCTION,
    LAB_DIAGNOSTICS_ONLY,
}

@Serializable
data class StrategyProbeReport(
    val suiteId: String,
    val methodologyVersion: String = "strategy_learning_v3",
    val tcpCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
    val quicCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
    val recommendation: StrategyProbeRecommendation,
    val completionKind: StrategyProbeCompletionKind = StrategyProbeCompletionKind.NORMAL,
    val auditAssessment: StrategyProbeAuditAssessment? = null,
    val targetSelection: StrategyProbeTargetSelection? = null,
    val pilotBucketLabels: List<String> = emptyList(),
)

@Serializable
data class StrategyProbeCandidateSummary(
    val id: String,
    val label: String,
    val family: String,
    val emitterTier: StrategyEmitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
    val exactEmitterRequiresRoot: Boolean = false,
    val emitterDowngraded: Boolean = false,
    val quicLayoutFamily: String? = null,
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
    val quicCandidateLayoutFamily: String? = null,
    val dnsStrategyFamily: String? = null,
    val dnsStrategyLabel: String? = null,
    val rationale: String,
    val recommendedProxyConfigJson: String,
    val strategySignature: BypassStrategySignature? = null,
)
