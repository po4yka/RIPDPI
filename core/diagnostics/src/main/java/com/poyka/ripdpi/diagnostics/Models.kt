package com.poyka.ripdpi.diagnostics

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
data class StrategyProbeRequest(
    val suiteId: String = "quick_v1",
    val baseProxyConfigJson: String? = null,
)

@Serializable
data class ScanRequest(
    val profileId: String,
    val displayName: String,
    val pathMode: ScanPathMode,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val domainTargets: List<DomainTarget> = emptyList(),
    val dnsTargets: List<DnsTarget> = emptyList(),
    val tcpTargets: List<TcpTarget> = emptyList(),
    val quicTargets: List<QuicTarget> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
    val strategyProbe: StrategyProbeRequest? = null,
)

@Serializable
data class ScanProgress(
    val sessionId: String,
    val phase: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val message: String,
    val isFinished: Boolean = false,
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
    val strategyProbeReport: StrategyProbeReport? = null,
)

@Serializable
data class StrategyProbeReport(
    val suiteId: String,
    val tcpCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
    val quicCandidates: List<StrategyProbeCandidateSummary> = emptyList(),
    val recommendation: StrategyProbeRecommendation,
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
    val averageLatencyMs: Long? = null,
    val skipped: Boolean = false,
)

@Serializable
data class StrategyProbeRecommendation(
    val tcpCandidateId: String,
    val tcpCandidateLabel: String,
    val quicCandidateId: String,
    val quicCandidateLabel: String,
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
    val session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
    val results: List<com.poyka.ripdpi.data.diagnostics.ProbeResultEntity>,
    val snapshots: List<com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity>,
    val events: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
    val context: com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity?,
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
