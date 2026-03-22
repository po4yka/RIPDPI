package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
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

@Immutable
data class DiagnosticsExecutionPolicyUiModel(
    val manualOnly: Boolean = false,
    val allowBackground: Boolean = false,
    val requiresRawPath: Boolean = false,
)

@Immutable
data class DiagnosticsMetricUiModel(
    val label: String,
    val value: String,
    val tone: DiagnosticsTone = DiagnosticsTone.Neutral,
)

@Immutable
data class DiagnosticsFieldUiModel(
    val label: String,
    val value: String,
)

@Stable
data class DiagnosticsNetworkSnapshotUiModel(
    val title: String,
    val subtitle: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsContextGroupUiModel(
    val title: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

@Immutable
data class DiagnosticsProfileOptionUiModel(
    val id: String,
    val name: String,
    val source: String,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
    val strategyProbeSuiteId: String? = null,
    val family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    val regionTag: String? = null,
    val executionPolicy: DiagnosticsExecutionPolicyUiModel = DiagnosticsExecutionPolicyUiModel(),
    val manualOnly: Boolean = false,
    val packRefs: List<String> = emptyList(),
)

enum class PhaseState { Completed, Active, Pending }

@Immutable
data class PhaseStepUiModel(
    val label: String,
    val state: PhaseState,
    val tone: DiagnosticsTone,
)

@Immutable
data class CompletedProbeUiModel(
    val target: String,
    val outcome: String,
    val tone: DiagnosticsTone,
)

@Stable
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

@Stable
data class DiagnosticsProbeResultUiModel(
    val id: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val probeRetryCount: Int? = null,
    val tone: DiagnosticsTone,
    val details: List<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsProbeGroupUiModel(
    val title: String,
    val items: List<DiagnosticsProbeResultUiModel>,
)

@Immutable
data class DiagnosticsDiagnosisUiModel(
    val code: String,
    val summary: String,
    val severity: String,
    val target: String? = null,
    val tone: DiagnosticsTone,
    val evidence: List<String> = emptyList(),
)

@Immutable
data class DiagnosticsEventUiModel(
    val id: String,
    val source: String,
    val severity: String,
    val message: String,
    val createdAtLabel: String,
    val tone: DiagnosticsTone,
)

@Immutable
data class DiagnosticsSparklineUiModel(
    val label: String,
    val values: List<Float>,
    val tone: DiagnosticsTone = DiagnosticsTone.Info,
)

@Stable
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

@Stable
data class DiagnosticsSessionDetailUiModel(
    val session: DiagnosticsSessionRowUiModel,
    val diagnoses: List<DiagnosticsDiagnosisUiModel> = emptyList(),
    val reportMetadata: List<DiagnosticsFieldUiModel> = emptyList(),
    val probeGroups: List<DiagnosticsProbeGroupUiModel>,
    val snapshots: List<DiagnosticsNetworkSnapshotUiModel>,
    val events: List<DiagnosticsEventUiModel>,
    val contextGroups: List<DiagnosticsContextGroupUiModel>,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val hasSensitiveDetails: Boolean,
    val sensitiveDetailsVisible: Boolean,
)

@Stable
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

@Stable
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

@Stable
data class DiagnosticsStrategyProbeFamilyUiModel(
    val title: String,
    val candidates: List<DiagnosticsStrategyProbeCandidateUiModel>,
)

@Stable
data class DiagnosticsStrategyProbeRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: List<DiagnosticsFieldUiModel>,
    val signature: List<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsStrategyProbeReportUiModel(
    val suiteId: String,
    val suiteLabel: String,
    val summaryMetrics: List<DiagnosticsMetricUiModel>,
    val recommendation: DiagnosticsStrategyProbeRecommendationUiModel,
    val families: List<DiagnosticsStrategyProbeFamilyUiModel>,
    val candidateDetails: Map<String, DiagnosticsStrategyProbeCandidateDetailUiModel> = emptyMap(),
)

@Stable
data class DiagnosticsResolverRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: List<DiagnosticsFieldUiModel>,
    val appliedTemporarily: Boolean,
    val persistable: Boolean,
)

@Stable
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

@Immutable
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

@Stable
data class DiagnosticsScanUiModel(
    val profiles: List<DiagnosticsProfileOptionUiModel> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedProfile: DiagnosticsProfileOptionUiModel? = null,
    val activePathMode: com.poyka.ripdpi.diagnostics.ScanPathMode = com.poyka.ripdpi.diagnostics.ScanPathMode.RAW_PATH,
    val activeProgress: DiagnosticsProgressUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val diagnoses: List<DiagnosticsDiagnosisUiModel> = emptyList(),
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

@Stable
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

@Immutable
data class DiagnosticsSessionFiltersUiModel(
    val pathMode: String? = null,
    val status: String? = null,
    val query: String = "",
)

@Stable
data class DiagnosticsSessionsUiModel(
    val filters: DiagnosticsSessionFiltersUiModel = DiagnosticsSessionFiltersUiModel(),
    val sessions: List<DiagnosticsSessionRowUiModel> = emptyList(),
    val pathModes: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val focusedSessionId: String? = null,
)

@Stable
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

@Stable
data class DiagnosticsApproachDetailUiModel(
    val approach: DiagnosticsApproachRowUiModel,
    val signature: List<DiagnosticsFieldUiModel>,
    val breakdown: List<DiagnosticsMetricUiModel>,
    val runtimeSummary: List<DiagnosticsMetricUiModel>,
    val recentSessions: List<DiagnosticsSessionRowUiModel>,
    val recentUsageNotes: List<String>,
    val failureNotes: List<String>,
)

@Stable
data class DiagnosticsApproachesUiModel(
    val selectedMode: DiagnosticsApproachMode = DiagnosticsApproachMode.Profiles,
    val rows: List<DiagnosticsApproachRowUiModel> = emptyList(),
    val focusedApproachId: String? = null,
)

@Immutable
data class DiagnosticsEventFiltersUiModel(
    val source: String? = null,
    val severity: String? = null,
    val search: String = "",
    val autoScroll: Boolean = true,
)

@Stable
data class DiagnosticsEventsUiModel(
    val filters: DiagnosticsEventFiltersUiModel = DiagnosticsEventFiltersUiModel(),
    val events: List<DiagnosticsEventUiModel> = emptyList(),
    val availableSources: List<String> = emptyList(),
    val availableSeverities: List<String> = emptyList(),
    val focusedEventId: String? = null,
)

@Stable
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

@Stable
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

internal data class SelectionState(
    val selectedSectionRequest: DiagnosticsSection = DiagnosticsSection.Overview,
    val selectedProfileId: String? = null,
    val selectedApproachMode: DiagnosticsApproachMode = DiagnosticsApproachMode.Profiles,
    val selectedApproachDetail: DiagnosticsApproachDetailUiModel? = null,
    val selectedProbe: DiagnosticsProbeResultUiModel? = null,
    val selectedEventId: String? = null,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel? = null,
)

internal data class FilterState(
    val sessionPathModeFilter: String? = null,
    val sessionStatusFilter: String? = null,
    val sessionSearch: String = "",
    val eventSourceFilter: String? = null,
    val eventSeverityFilter: String? = null,
    val eventSearch: String = "",
    val eventAutoScroll: Boolean = true,
)

internal data class SessionDetailState(
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel? = null,
    val sensitiveSessionDetailsVisible: Boolean = false,
)

internal data class ScanLifecycleState(
    val scanStartedAt: Long? = null,
    val accumulatedProbes: List<CompletedProbeUiModel> = emptyList(),
    val pendingAutoOpenAuditSessionId: String? = null,
    val archiveActionState: ArchiveActionState = ArchiveActionState(),
)

// -- Intermediate snapshot data classes for layered combine architecture --

internal data class LiveDataSnapshot(
    val telemetry: List<DiagnosticTelemetrySample>,
    val nativeEvents: List<DiagnosticEvent>,
    val progress: com.poyka.ripdpi.diagnostics.ScanProgress?,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val contexts: List<DiagnosticContextSnapshot>,
) {
    companion object {
        val EMPTY =
            LiveDataSnapshot(
                telemetry = emptyList(),
                nativeEvents = emptyList(),
                progress = null,
                snapshots = emptyList(),
                contexts = emptyList(),
            )
    }
}

internal data class ScanDataSnapshot(
    val profiles: List<DiagnosticProfile>,
    val sessions: List<DiagnosticScanSession>,
    val approachStats: List<com.poyka.ripdpi.diagnostics.BypassApproachSummary>,
    val exports: List<DiagnosticExportRecord>,
) {
    companion object {
        val EMPTY =
            ScanDataSnapshot(
                profiles = emptyList(),
                sessions = emptyList(),
                approachStats = emptyList(),
                exports = emptyList(),
            )
    }
}

internal data class ConfigSnapshot(
    val settings: com.poyka.ripdpi.proto.AppSettings,
    val rememberedPolicies: List<DiagnosticsRememberedPolicy>,
    val activeConnectionPolicy: DiagnosticActiveConnectionPolicy?,
)

internal data class UiControlState(
    val selection: SelectionState,
    val filter: FilterState,
    val sessionDetail: SessionDetailState,
    val scanLifecycle: ScanLifecycleState,
)
