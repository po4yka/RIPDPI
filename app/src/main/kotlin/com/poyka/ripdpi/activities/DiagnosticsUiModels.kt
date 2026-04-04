package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.ProbePersistencePolicy
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal const val StrategyProbeSuiteQuickV1 = "quick_v1"
internal const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"

internal val DiagnosticsProfileOptionUiModel.isStrategyProbe: Boolean
    get() = kind == ScanKind.STRATEGY_PROBE

internal val DiagnosticsProfileOptionUiModel.isFullAudit: Boolean
    get() = strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1

enum class DiagnosticsSection {
    Dashboard,
    Scan,
    Tools,
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
    val probePersistencePolicy: ProbePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
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

@Immutable
data class DiagnosticsFieldGroupUiModel(
    val header: String,
    val fields: List<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsNetworkSnapshotUiModel(
    val title: String,
    val subtitle: String,
    val fieldGroups: List<DiagnosticsFieldGroupUiModel>,
) {
    val fields: List<DiagnosticsFieldUiModel> get() = fieldGroups.flatMap { it.fields }
}

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

enum class DiagnosticsStrategyProbeProgressLaneUiModel {
    TCP,
    QUIC,
}

enum class DiagnosticsWorkflowRestrictionReasonUiModel {
    COMMAND_LINE_MODE_ACTIVE,
    VPN_PERMISSION_DISABLED,
}

enum class DiagnosticsWorkflowRestrictionActionKindUiModel {
    OPEN_ADVANCED_SETTINGS,
    OPEN_VPN_PERMISSION,
}

@Immutable
data class DiagnosticsWorkflowRestrictionUiModel(
    val reason: DiagnosticsWorkflowRestrictionReasonUiModel,
    val title: String,
    val body: String,
    val actionLabel: String,
    val actionKind: DiagnosticsWorkflowRestrictionActionKindUiModel,
)

@Immutable
data class DiagnosticsStrategyProbeLiveProgressUiModel(
    val lane: DiagnosticsStrategyProbeProgressLaneUiModel,
    val candidateIndex: Int,
    val candidateTotal: Int,
    val candidateId: String,
    val candidateLabel: String,
    val succeededTargets: Int = 0,
    val totalTargets: Int = 0,
)

enum class DnsBaselineStatus {
    CLEAN,
    TAMPERED,
}

@Immutable
data class StrategyCandidateTimelineEntryUiModel(
    val candidateId: String,
    val candidateLabel: String,
    val lane: DiagnosticsStrategyProbeProgressLaneUiModel,
    val outcome: String,
    val tone: DiagnosticsTone,
    val succeededTargets: Int = 0,
    val totalTargets: Int = 0,
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
    val strategyProbeProgress: DiagnosticsStrategyProbeLiveProgressUiModel? = null,
    val dnsBaselineStatus: DnsBaselineStatus? = null,
    val candidateTimeline: List<StrategyCandidateTimelineEntryUiModel> = emptyList(),
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
    val recommendation: String? = null,
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
    val launchOrigin: DiagnosticsScanLaunchOrigin = DiagnosticsScanLaunchOrigin.UNKNOWN,
    val triggerClassification: String? = null,
)

@Immutable
data class DiagnosticsAutomaticProbeCalloutUiModel(
    val title: String,
    val summary: String,
    val detail: String,
    val actionLabel: String,
)

@Immutable
data class HiddenProbeConflictDialogState(
    val requestId: String,
    val profileName: String,
    val pathMode: ScanPathMode,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
)

@Immutable
data class QueuedManualScanRequest(
    val requestId: String,
    val profileName: String,
    val pathMode: ScanPathMode,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
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
data class DiagnosticsStrategyProbeWinningCandidateUiModel(
    val id: String,
    val label: String,
    val familyLabel: String,
    val outcome: String,
    val rationale: String,
    val metrics: List<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
    val hiddenCandidateCount: Int,
)

@Stable
data class DiagnosticsStrategyProbeWinningPathUiModel(
    val tcpWinner: DiagnosticsStrategyProbeWinningCandidateUiModel,
    val quicWinner: DiagnosticsStrategyProbeWinningCandidateUiModel,
    val dnsLaneLabel: String? = null,
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
    val completionKind: StrategyProbeCompletionKind,
    val auditAssessment: StrategyProbeAuditAssessment? = null,
    val recommendation: DiagnosticsStrategyProbeRecommendationUiModel,
    val winningPath: DiagnosticsStrategyProbeWinningPathUiModel? = null,
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
    val recentAutomaticProbe: DiagnosticsAutomaticProbeCalloutUiModel? = null,
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
    val workflowRestriction: DiagnosticsWorkflowRestrictionUiModel? = null,
    val resolverRecommendation: DiagnosticsResolverRecommendationUiModel? = null,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    val queuedManualScanRequest: QueuedManualScanRequest? = null,
    val isBusy: Boolean = false,
)

@Stable
data class DiagnosticsLiveUiModel(
    val health: DiagnosticsHealth = DiagnosticsHealth.Idle,
    val statusLabel: String = "Idle",
    val statusTone: DiagnosticsTone = DiagnosticsTone.Neutral,
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

@Immutable
data class DiagnosticsPerformanceUiModel(
    val buildSequence: Long,
    val totalDurationMillis: Double,
    val eventMappingDurationMillis: Double,
    val resolveDurationMillis: Double,
    val overviewDurationMillis: Double,
    val scanDurationMillis: Double,
    val liveDurationMillis: Double,
    val sessionsDurationMillis: Double,
    val approachesDurationMillis: Double,
    val eventsDurationMillis: Double,
    val shareDurationMillis: Double,
    val telemetryCount: Int,
    val nativeEventCount: Int,
    val sessionCount: Int,
)

@Stable
data class DiagnosticsUiState(
    val selectedSection: DiagnosticsSection = DiagnosticsSection.Dashboard,
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
    val performance: DiagnosticsPerformanceUiModel? = null,
)

sealed interface DiagnosticsEffect {
    enum class SnackbarAction {
        OpenDnsSettings,
    }

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

    data class ScanQueued(
        val message: String,
    ) : DiagnosticsEffect

    data class ScanCompleted(
        val summary: String,
        val tone: DiagnosticsTone,
        val actionLabel: String? = null,
        val action: SnackbarAction? = null,
    ) : DiagnosticsEffect

    data class ScanStartFailed(
        val message: String,
    ) : DiagnosticsEffect
}

internal data class ArchiveActionState(
    val message: String? = null,
    val tone: DiagnosticsTone = DiagnosticsTone.Neutral,
    val isBusy: Boolean = false,
    val latestArchiveFileName: String? = null,
)

internal data class SelectionState(
    val selectedSectionRequest: DiagnosticsSection = DiagnosticsSection.Dashboard,
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
    val activeScanPathMode: ScanPathMode? = null,
    val activeScanKind: ScanKind? = null,
    val accumulatedProbes: ImmutableList<CompletedProbeUiModel> = persistentListOf(),
    val accumulatedStrategyCandidates: ImmutableList<StrategyCandidateTimelineEntryUiModel> = persistentListOf(),
    val dnsBaselineStatus: DnsBaselineStatus? = null,
    val pendingAutoOpenAuditSessionId: String? = null,
    val hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    val queuedManualScanRequest: QueuedManualScanRequest? = null,
    val archiveActionState: ArchiveActionState = ArchiveActionState(),
)

// -- Intermediate snapshot data classes for layered combine architecture --

internal data class LiveDataSnapshot(
    val activeConnectionSession: DiagnosticConnectionSession?,
    val currentTelemetry: DiagnosticTelemetrySample?,
    val telemetry: List<DiagnosticTelemetrySample>,
    val nativeEvents: List<DiagnosticEvent>,
    val progress: com.poyka.ripdpi.diagnostics.ScanProgress?,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val contexts: List<DiagnosticContextSnapshot>,
    val liveTelemetry: List<DiagnosticTelemetrySample>,
    val liveNativeEvents: List<DiagnosticEvent>,
    val liveSnapshots: List<DiagnosticNetworkSnapshot>,
    val liveContexts: List<DiagnosticContextSnapshot>,
) {
    companion object {
        val EMPTY =
            LiveDataSnapshot(
                activeConnectionSession = null,
                currentTelemetry = null,
                telemetry = emptyList(),
                nativeEvents = emptyList(),
                progress = null,
                snapshots = emptyList(),
                contexts = emptyList(),
                liveTelemetry = emptyList(),
                liveNativeEvents = emptyList(),
                liveSnapshots = emptyList(),
                liveContexts = emptyList(),
            )
    }
}

internal data class LiveRuntimeSnapshot(
    val activeConnectionSession: DiagnosticConnectionSession?,
    val liveSnapshots: List<DiagnosticNetworkSnapshot>,
    val liveContexts: List<DiagnosticContextSnapshot>,
    val liveTelemetry: List<DiagnosticTelemetrySample>,
    val liveNativeEvents: List<DiagnosticEvent>,
) {
    companion object {
        val EMPTY =
            LiveRuntimeSnapshot(
                activeConnectionSession = null,
                liveSnapshots = emptyList(),
                liveContexts = emptyList(),
                liveTelemetry = emptyList(),
                liveNativeEvents = emptyList(),
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
