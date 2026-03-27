package com.poyka.ripdpi.diagnostics.domain

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.diagnostics.CircumventionTarget
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DnsTarget
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.ProbePersistencePolicy
import com.poyka.ripdpi.diagnostics.QuicTarget
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ServiceTarget
import com.poyka.ripdpi.diagnostics.StoredApproachSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeRequest
import com.poyka.ripdpi.diagnostics.TcpTarget
import com.poyka.ripdpi.diagnostics.TelegramTarget
import com.poyka.ripdpi.diagnostics.ThroughputTarget
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire

internal enum class ProbeFamily {
    DNS,
    WEB,
    QUIC,
    TCP,
    SERVICE,
    CIRCUMVENTION,
    TELEGRAM,
    THROUGHPUT,
}

internal data class ProbeTask(
    val family: ProbeFamily,
    val targetId: String,
    val label: String,
)

internal data class ExecutionPolicy(
    val manualOnly: Boolean,
    val allowBackground: Boolean,
    val requiresRawPath: Boolean,
    val probePersistencePolicy: ProbePersistencePolicy,
)

internal data class DiagnosticsIntent(
    val profileId: String,
    val displayName: String,
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val kind: ScanKind,
    val family: DiagnosticProfileFamily,
    val regionTag: String?,
    val executionPolicy: ExecutionPolicy,
    val packRefs: List<String>,
    val domainTargets: List<DomainTarget>,
    val dnsTargets: List<DnsTarget>,
    val tcpTargets: List<TcpTarget>,
    val quicTargets: List<QuicTarget>,
    val serviceTargets: List<ServiceTarget>,
    val circumventionTargets: List<CircumventionTarget>,
    val throughputTargets: List<ThroughputTarget>,
    val whitelistSni: List<String>,
    val telegramTarget: TelegramTarget?,
    val strategyProbe: StrategyProbeRequest?,
    val requestedPathMode: ScanPathMode,
)

internal data class ScanContext(
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val pathMode: ScanPathMode,
    val networkFingerprint: NetworkFingerprint?,
    val preferredDnsPath: EncryptedDnsPathCandidate?,
    val networkSnapshot: NativeNetworkSnapshot?,
    val serviceMode: String,
    val contextSnapshot: DiagnosticContextModel,
    val approachSnapshot: StoredApproachSnapshot,
)

internal data class ScanPlan(
    val intent: DiagnosticsIntent,
    val context: ScanContext,
    val proxyHost: String?,
    val proxyPort: Int?,
    val dnsTargets: List<DnsTarget>,
    val probeTasks: List<ProbeTask>,
)

internal data class RawScanReport(
    val report: EngineScanReportWire,
)

internal data class DerivedScanReport(
    val report: EngineScanReportWire,
)
