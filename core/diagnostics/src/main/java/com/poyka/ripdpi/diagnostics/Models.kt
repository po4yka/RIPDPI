package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class ScanPathMode {
    RAW_PATH,
    IN_PATH,
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
    val dohUrl: String? = null,
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
data class ScanRequest(
    val profileId: String,
    val displayName: String,
    val pathMode: ScanPathMode,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val domainTargets: List<DomainTarget> = emptyList(),
    val dnsTargets: List<DnsTarget> = emptyList(),
    val tcpTargets: List<TcpTarget> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
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
    val capturedAt: Long,
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
    val routeGroup: String,
    val sessionUptimeMs: Long?,
    val lastNativeErrorHeadline: String,
    val restartCount: Int,
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
