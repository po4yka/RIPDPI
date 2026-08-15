@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionPlanSnapshot(
    val planVersion: String,
    val scanKind: ScanKind,
    val profileFamily: DiagnosticProfileFamily,
    val pathMode: ScanPathMode,
    val transportKind: String,
    val stageOrder: List<String>,
    val totalSteps: Int,
    val scanDeadlineMs: Long,
    val packRefs: List<String> = emptyList(),
    val probeTaskFamilies: List<ExecutionPlanProbeTaskFamily> = emptyList(),
    val targetCounts: ExecutionPlanTargetCounts,
    val strategy: StrategyExecutionPlanSnapshot? = null,
    val stageExecutions: List<ExecutionStageSnapshot> = emptyList(),
)

@Serializable
data class ExecutionStageSnapshot(
    val stageId: String,
    val plannedSteps: Int,
    val executedSteps: Int,
    val skippedByStageBudgetSteps: Int,
    val skippedByGlobalDeadlineSteps: Int,
)

@Serializable
enum class ExecutionPlanProbeTaskFamily {
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
data class ExecutionPlanTargetCounts(
    val domainTargetCount: Int,
    val dnsTargetCount: Int,
    val tcpTargetCount: Int,
    val quicTargetCount: Int,
    val serviceTargetCount: Int,
    val circumventionTargetCount: Int,
    val throughputTargetCount: Int,
    val whitelistSniCount: Int,
    val telegramTargetCount: Int,
    val strategySelectedDomainCount: Int,
    val strategySelectedQuicCount: Int,
)

@Serializable
data class StrategyExecutionPlanSnapshot(
    val suiteId: String,
    val inventorySemantics: String,
    val probeSeed: String,
    val maxCandidates: Int? = null,
    val tcpCandidates: List<StrategyCandidatePlanSnapshot>,
    val quicCandidates: List<StrategyCandidatePlanSnapshot>,
    val shortCircuitHostfake: Boolean,
    val shortCircuitQuicBurst: Boolean,
    val familyFailureThreshold: Int,
)

@Serializable
data class StrategyCandidatePlanSnapshot(
    val id: String,
    val label: String,
    val family: String,
    val emitterTier: StrategyEmitterTier,
    val exactEmitterRequiresRoot: Boolean,
    val approximateFallbackFamily: String? = null,
    val quicLayoutFamily: String? = null,
    val eligibility: String,
    val warmup: String,
    val preserveAdaptiveFakeTtl: Boolean,
    val requiresFakeTtl: Boolean,
    val requiresTcpFastOpen: Boolean,
    val requiredCapabilities: List<String> = emptyList(),
)
