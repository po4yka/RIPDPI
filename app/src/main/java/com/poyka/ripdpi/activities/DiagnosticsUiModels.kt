package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.ScanKind

internal const val StrategyProbeSuiteQuickV1 = "quick_v1"
internal const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"

internal val DiagnosticsProfileOptionUiModel.isStrategyProbe: Boolean
    get() = kind == ScanKind.STRATEGY_PROBE

internal val DiagnosticsProfileOptionUiModel.isFullAudit: Boolean
    get() = strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1

enum class DiagnosticsSection {
    Overview,
    Scan,
    Live,
    Approaches,
    Share,
}

enum class DiagnosticsApproachMode {
    Profiles,
    Strategies,
}

enum class DiagnosticsHealth {
    Healthy,
    Attention,
    Degraded,
    Idle,
}

enum class DiagnosticsTone {
    Neutral,
    Positive,
    Warning,
    Negative,
    Info,
}

data class DiagnosticsMetricUiModel(
    val label: String,
    val value: String,
    val tone: DiagnosticsTone = DiagnosticsTone.Neutral,
)

data class DiagnosticsFieldUiModel(
    val label: String,
    val value: String,
)

data class DiagnosticsNetworkSnapshotUiModel(
    val title: String,
    val subtitle: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsContextGroupUiModel(
    val title: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsProfileOptionUiModel(
    val id: String,
    val name: String,
    val source: String,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
    val strategyProbeSuiteId: String? = null,
)

enum class PhaseState { Completed, Active, Pending }

data class PhaseStepUiModel(
    val label: String,
    val state: PhaseState,
    val tone: DiagnosticsTone,
)

data class CompletedProbeUiModel(
    val target: String,
    val outcome: String,
    val tone: DiagnosticsTone,
)

data class DiagnosticsProgressUiModel(
    val phase: String,
    val summary: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val fraction: Float,
    val scanKind: com.poyka.ripdpi.diagnostics.ScanKind,
    val isFullAudit: Boolean,
    val elapsedLabel: String,
    val etaLabel: String?,
    val phaseSteps: List<PhaseStepUiModel>,
    val currentProbeLabel: String,
    val completedProbes: List<CompletedProbeUiModel> = emptyList(),
)

data class DiagnosticsProbeResultUiModel(
    val id: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val probeRetryCount: Int? = null,
    val tone: DiagnosticsTone,
    val details: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsProbeGroupUiModel(
    val title: String,
    val items: List<DiagnosticsProbeResultUiModel>,
)

data class DiagnosticsEventUiModel(
    val id: String,
    val source: String,
    val severity: String,
    val message: String,
    val createdAtLabel: String,
    val tone: DiagnosticsTone,
)

data class DiagnosticsSparklineUiModel(
    val label: String,
    val values: List<Float>,
    val tone: DiagnosticsTone = DiagnosticsTone.Info,
)

data class DiagnosticsSessionRowUiModel(
    val id: String,
    val profileId: String,
    val title: String,
    val subtitle: String,
    val pathMode: String,
    val serviceMode: String,
    val status: String,
    val startedAtLabel: String,
    val summary: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

data class DiagnosticsSessionDetailUiModel(
    val session: DiagnosticsSessionRowUiModel,
    val probeGroups: List<DiagnosticsProbeGroupUiModel>,
    val snapshots: List<DiagnosticsNetworkSnapshotUiModel>,
    val events: List<DiagnosticsEventUiModel>,
    val contextGroups: List<DiagnosticsContextGroupUiModel>,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val hasSensitiveDetails: Boolean,
    val sensitiveDetailsVisible: Boolean,
)

data class DiagnosticsStrategyProbeCandidateUiModel(
    val id: String,
    val label: String,
    val outcome: String,
    val rationale: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
    val skipped: Boolean,
    val recommended: Boolean,
)

data class DiagnosticsStrategyProbeCandidateDetailUiModel(
    val id: String,
    val label: String,
    val familyLabel: String,
    val suiteLabel: String,
    val outcome: String,
    val rationale: String,
    val tone: DiagnosticsTone,
    val recommended: Boolean,
    val notes: List<String>,
    val metrics: List<DiagnosticsMetricUiModel>,
    val signature: List<DiagnosticsFieldUiModel>,
    val resultGroups: List<DiagnosticsProbeGroupUiModel>,
)

data class DiagnosticsStrategyProbeFamilyUiModel(
    val title: String,
    val candidates: List<DiagnosticsStrategyProbeCandidateUiModel>,
)

data class DiagnosticsStrategyProbeRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: List<DiagnosticsFieldUiModel>,
    val signature: List<DiagnosticsFieldUiModel>,
)

data class DiagnosticsStrategyProbeReportUiModel(
    val suiteId: String,
    val suiteLabel: String,
    val summaryMetrics: List<DiagnosticsMetricUiModel>,
    val recommendation: DiagnosticsStrategyProbeRecommendationUiModel,
    val families: List<DiagnosticsStrategyProbeFamilyUiModel>,
    val candidateDetails: Map<String, DiagnosticsStrategyProbeCandidateDetailUiModel> = emptyMap(),
)

data class DiagnosticsResolverRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: List<DiagnosticsFieldUiModel>,
    val appliedTemporarily: Boolean,
    val persistable: Boolean,
)

data class DiagnosticsOverviewUiModel(
    val health: DiagnosticsHealth = DiagnosticsHealth.Idle,
    val headline: String = "Idle",
    val body: String = "No diagnostics activity yet.",
    val activeProfile: DiagnosticsProfileOptionUiModel? = null,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val contextSummary: DiagnosticsContextGroupUiModel? = null,
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val warnings: List<DiagnosticsEventUiModel> = emptyList(),
    val rememberedNetworks: List<DiagnosticsRememberedNetworkUiModel> = emptyList(),
)

data class DiagnosticsRememberedNetworkUiModel(
    val id: Long,
    val title: String,
    val subtitle: String,
    val status: String,
    val statusTone: DiagnosticsTone,
    val source: String,
    val strategyLabel: String,
    val lastValidatedLabel: String? = null,
    val lastAppliedLabel: String? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val isCurrentMatch: Boolean = false,
)

data class DiagnosticsScanUiModel(
    val profiles: List<DiagnosticsProfileOptionUiModel> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedProfile: DiagnosticsProfileOptionUiModel? = null,
    val activePathMode: com.poyka.ripdpi.diagnostics.ScanPathMode = com.poyka.ripdpi.diagnostics.ScanPathMode.RAW_PATH,
    val activeProgress: DiagnosticsProgressUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val latestResults: List<DiagnosticsProbeResultUiModel> = emptyList(),
    val selectedProfileScopeLabel: String? = null,
    val runRawEnabled: Boolean = true,
    val runInPathEnabled: Boolean = true,
    val runRawHint: String? = null,
    val runInPathHint: String? = null,
    val resolverRecommendation: DiagnosticsResolverRecommendationUiModel? = null,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val isBusy: Boolean = false,
)

data class DiagnosticsLiveUiModel(
    val statusLabel: String = "Idle",
    val freshnessLabel: String = "No live telemetry",
    val headline: String = "Live monitor standing by",
    val body: String = "Continuous monitor is waiting for an active RIPDPI session.",
    val networkLabel: String? = null,
    val modeLabel: String? = null,
    val signalLabel: String = "No transfer observed yet",
    val eventSummaryLabel: String = "Runtime feed is quiet",
    val highlights: List<DiagnosticsMetricUiModel> = emptyList(),
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val trends: List<DiagnosticsSparklineUiModel> = emptyList(),
    val snapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val contextGroups: List<DiagnosticsContextGroupUiModel> = emptyList(),
    val passiveEvents: List<DiagnosticsEventUiModel> = emptyList(),
)

data class DiagnosticsSessionFiltersUiModel(
    val pathMode: String? = null,
    val status: String? = null,
    val query: String = "",
)

data class DiagnosticsSessionsUiModel(
    val filters: DiagnosticsSessionFiltersUiModel = DiagnosticsSessionFiltersUiModel(),
    val sessions: List<DiagnosticsSessionRowUiModel> = emptyList(),
    val pathModes: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val focusedSessionId: String? = null,
)

data class DiagnosticsApproachRowUiModel(
    val id: String,
    val kind: DiagnosticsApproachMode,
    val title: String,
    val subtitle: String,
    val verificationState: String,
    val lastValidatedResult: String,
    val dominantFailurePattern: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

data class DiagnosticsApproachDetailUiModel(
    val approach: DiagnosticsApproachRowUiModel,
    val signature: List<DiagnosticsFieldUiModel>,
    val breakdown: List<DiagnosticsMetricUiModel>,
    val runtimeSummary: List<DiagnosticsMetricUiModel>,
    val recentSessions: List<DiagnosticsSessionRowUiModel>,
    val recentUsageNotes: List<String>,
    val failureNotes: List<String>,
)

data class DiagnosticsApproachesUiModel(
    val selectedMode: DiagnosticsApproachMode = DiagnosticsApproachMode.Profiles,
    val rows: List<DiagnosticsApproachRowUiModel> = emptyList(),
    val focusedApproachId: String? = null,
)

data class DiagnosticsEventFiltersUiModel(
    val source: String? = null,
    val severity: String? = null,
    val search: String = "",
    val autoScroll: Boolean = true,
)

data class DiagnosticsEventsUiModel(
    val filters: DiagnosticsEventFiltersUiModel = DiagnosticsEventFiltersUiModel(),
    val events: List<DiagnosticsEventUiModel> = emptyList(),
    val availableSources: List<String> = emptyList(),
    val availableSeverities: List<String> = emptyList(),
    val focusedEventId: String? = null,
)

data class DiagnosticsShareUiModel(
    val targetSessionId: String? = null,
    val previewTitle: String = "RIPDPI diagnostics",
    val previewBody: String = "Select a session or use the latest diagnostics state.",
    val metrics: List<DiagnosticsMetricUiModel> = emptyList(),
    val latestArchiveFileName: String? = null,
    val archiveStateMessage: String? = null,
    val archiveStateTone: DiagnosticsTone = DiagnosticsTone.Neutral,
    val isArchiveBusy: Boolean = false,
)

data class DiagnosticsUiState(
    val selectedSection: DiagnosticsSection = DiagnosticsSection.Overview,
    val overview: DiagnosticsOverviewUiModel = DiagnosticsOverviewUiModel(),
    val scan: DiagnosticsScanUiModel = DiagnosticsScanUiModel(),
    val live: DiagnosticsLiveUiModel = DiagnosticsLiveUiModel(),
    val sessions: DiagnosticsSessionsUiModel = DiagnosticsSessionsUiModel(),
    val approaches: DiagnosticsApproachesUiModel = DiagnosticsApproachesUiModel(),
    val events: DiagnosticsEventsUiModel = DiagnosticsEventsUiModel(),
    val share: DiagnosticsShareUiModel = DiagnosticsShareUiModel(),
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel? = null,
    val selectedApproachDetail: DiagnosticsApproachDetailUiModel? = null,
    val selectedEvent: DiagnosticsEventUiModel? = null,
    val selectedProbe: DiagnosticsProbeResultUiModel? = null,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel? = null,
)

sealed interface DiagnosticsEffect {
    data class ShareSummaryRequested(
        val title: String,
        val body: String,
    ) : DiagnosticsEffect

    data class ShareArchiveRequested(
        val absolutePath: String,
        val fileName: String,
    ) : DiagnosticsEffect

    data class SaveArchiveRequested(
        val absolutePath: String,
        val fileName: String,
    ) : DiagnosticsEffect

    data class ScanStarted(
        val scanTypeLabel: String,
    ) : DiagnosticsEffect

    data class ScanCompleted(
        val summary: String,
        val tone: DiagnosticsTone,
    ) : DiagnosticsEffect
}

internal data class ArchiveActionState(
    val message: String? = null,
    val tone: DiagnosticsTone = DiagnosticsTone.Neutral,
    val isBusy: Boolean = false,
    val latestArchiveFileName: String? = null,
)
