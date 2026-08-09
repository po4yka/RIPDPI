package com.poyka.ripdpi.diagnostics.contract.engine

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.diagnostics.CircumventionTarget
import com.poyka.ripdpi.diagnostics.ConfirmGoodDpiEvidence
import com.poyka.ripdpi.diagnostics.ConfirmGoodDpiVerdict
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import com.poyka.ripdpi.diagnostics.DnsTarget
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.ExecutionPlanSnapshot
import com.poyka.ripdpi.diagnostics.LogHealthSummary
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
import com.poyka.ripdpi.diagnostics.StrategyRecommendation
import com.poyka.ripdpi.diagnostics.TcpTarget
import com.poyka.ripdpi.diagnostics.TelegramTarget
import com.poyka.ripdpi.diagnostics.ThroughputTarget
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

// v6: adds the planned execution inventory to completed and partial reports. Must stay in sync
// with the Rust DIAGNOSTICS_ENGINE_SCHEMA_VERSION constant.
const val DiagnosticsEngineSchemaVersion = 6

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
    DOH_JSON_SURVEY,
}

@Serializable
data class EngineProbeTaskWire(
    val family: EngineProbeTaskFamily,
    val targetId: String,
    val label: String,
)

@Serializable
data class EngineScanRequestWire(
    @Required
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
    val confirmGoodDpiEvidence: ConfirmGoodDpiEvidence? = null,
    val networkSnapshot: NativeNetworkSnapshot? = null,
    val routeProbe: RouteProbeConfig? = null,
    val nativeLogLevel: String? = null,
    val logContext: RipDpiLogContext? = null,
    val scanDeadlineMs: Long? = null,
    val diagnosticTlsKeylogPath: String? = null,
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
enum class ScanCompletionKind {
    NORMAL,
    PARTIAL_RESULTS,
    TERMINATED,
}

@Serializable
enum class ScanTerminationReason {
    NETWORK_UNAVAILABLE,
    USER_CANCELLED,
    DEADLINE_EXCEEDED,
    ENGINE_ERROR,
    WORKER_PANICKED,
}

@Serializable
data class EngineScanReportWire(
    @Required
    val schemaVersion: Int = DiagnosticsEngineSchemaVersion,
    val sessionId: String,
    val profileId: String,
    val pathMode: ScanPathMode,
    val startedAt: Long,
    val finishedAt: Long,
    val summary: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val completionKind: ScanCompletionKind = ScanCompletionKind.NORMAL,
    val terminationReason: ScanTerminationReason? = null,
    val results: List<EngineProbeResultWire> = emptyList(),
    val resolverRecommendation: ResolverRecommendation? = null,
    val strategyRecommendation: StrategyRecommendation? = null,
    val directModeVerdict: DirectModeVerdict? = null,
    val strategyProbeReport: StrategyProbeReport? = null,
    val confirmGoodDpiVerdict: ConfirmGoodDpiVerdict? = null,
    val observations: List<ObservationFact> = emptyList(),
    val engineAnalysisVersion: String? = null,
    val diagnoses: List<Diagnosis> = emptyList(),
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
    val logHealthSummary: LogHealthSummary? = null,
    val executionPlan: ExecutionPlanSnapshot? = null,
)

@Serializable
data class EngineProgressWire(
    @Required
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
