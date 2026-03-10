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
)

@Serializable
data class DnsTarget(
    val domain: String,
)

@Serializable
data class TcpTarget(
    val id: String,
    val provider: String,
    val ip: String,
    val port: Int = 443,
    val sni: String? = null,
    val asn: String? = null,
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

data class ExportBundle(
    val fileName: String,
    val absolutePath: String,
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
