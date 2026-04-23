package com.poyka.ripdpi.diagnostics.contract.engine

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.diagnostics.CircumventionTarget
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import com.poyka.ripdpi.diagnostics.DnsTarget
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.QuicTarget
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.RouteProbeConfig
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ServiceTarget
import com.poyka.ripdpi.diagnostics.StrategyProbeLiveProgress
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.StrategyProbeRequest
import com.poyka.ripdpi.diagnostics.TcpTarget
import com.poyka.ripdpi.diagnostics.TelegramTarget
import com.poyka.ripdpi.diagnostics.ThroughputTarget
import kotlinx.serialization.Serializable

const val DiagnosticsEngineSchemaVersion = 1

@Serializable
enum class EngineProbeTaskFamily {
    DNS,
    WEB,
    QUIC,
    TCP,
    SERVICE,
    CIRCUMVENTION,
    TELEGRAM,
    THROUGHPUT,
}

@Serializable
data class EngineProbeTaskWire(
    val family: EngineProbeTaskFamily,
    val targetId: String,
    val label: String,
)

@Serializable
data class EngineScanRequestWire(
    val schemaVersion: Int = DiagnosticsEngineSchemaVersion,
    val profileId: String,
    val displayName: String,
    val pathMode: ScanPathMode,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
    val family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    val regionTag: String? = null,
    val packRefs: List<String> = emptyList(),
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val probeTasks: List<EngineProbeTaskWire> = emptyList(),
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
    val routeProbe: RouteProbeConfig? = null,
    val nativeLogLevel: String? = null,
    val logContext: RipDpiLogContext? = null,
    val scanDeadlineMs: Long? = null,
)

@Serializable
data class EngineProbeResultWire(
    val probeType: String,
    val target: String,
    val outcome: String,
    val details: List<ProbeDetail> = emptyList(),
    val probeRetryCount: Int? = null,
)

@Serializable
data class EngineScanReportWire(
    val schemaVersion: Int = DiagnosticsEngineSchemaVersion,
    val sessionId: String,
    val profileId: String,
    val pathMode: ScanPathMode,
    val startedAt: Long,
    val finishedAt: Long,
    val summary: String,
    val results: List<EngineProbeResultWire> = emptyList(),
    val resolverRecommendation: ResolverRecommendation? = null,
    val directModeVerdict: DirectModeVerdict? = null,
    val strategyProbeReport: StrategyProbeReport? = null,
    val observations: List<ObservationFact> = emptyList(),
    val engineAnalysisVersion: String? = null,
    val diagnoses: List<Diagnosis> = emptyList(),
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
)

@Serializable
data class EngineProgressWire(
    val schemaVersion: Int = DiagnosticsEngineSchemaVersion,
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
