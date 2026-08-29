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
import com.poyka.ripdpi.diagnostics.DiagnosticsJurisdictionProfileAccess
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.ProbePersistencePolicy
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

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

enum class DiagnosticsDnsIntegrityState {
    Idle,
    Running,
    Complete,
    Failed,
}

enum class DiagnosticsDnsAvailabilityState {
    Idle,
    Running,
    Complete,
    Failed,
}

enum class DiagnosticsDomainReachabilityState {
    Idle,
    Running,
    Complete,
    Failed,
}

enum class DiagnosticsRknBlockDiagnosisState {
    Idle,
    Running,
    Complete,
    Failed,
}

enum class DiagnosticsCompressionProbeState {
    Idle,
    Running,
    Complete,
    Failed,
}

enum class DiagnosticsTcp16FatHeaderState {
    Idle,
    Running,
    Complete,
    Failed,
}

enum class DiagnosticsAllowlistSniState {
    Idle,
    Running,
    Complete,
    Failed,
}

@Immutable
data class DiagnosticsDnsIntegrityDomainUiModel(
    val domain: String,
    val verdict: String,
    val udpAnswer: String,
    val dohAnswer: String,
    val tone: DiagnosticsTone,
)

@Immutable
data class DiagnosticsDnsIntegrityDoqUiModel(
    val provider: String,
    val domain: String,
    val verdict: String,
    val endpoint: String,
    val resolvedIps: String,
    val detail: String,
    val tone: DiagnosticsTone,
)

@Immutable
data class DiagnosticsDohBootstrapUiModel(
    val provider: String,
    val hostname: String,
    val verdict: String,
    val detail: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsDnsIntegrityToolUiModel(
    val state: DiagnosticsDnsIntegrityState = DiagnosticsDnsIntegrityState.Idle,
    val summary: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsDnsIntegrityDomainUiModel> = persistentListOf(),
    val doqRows: ImmutableList<DiagnosticsDnsIntegrityDoqUiModel> = persistentListOf(),
    val dohBootstrapRows: ImmutableList<DiagnosticsDohBootstrapUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)

@Immutable
data class DiagnosticsDnsAvailabilityServerUiModel(
    val name: String,
    val type: String,
    val availability: String,
    val latency: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsDnsAvailabilityToolUiModel(
    val state: DiagnosticsDnsAvailabilityState = DiagnosticsDnsAvailabilityState.Idle,
    val summary: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsDnsAvailabilityServerUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)

@Immutable
data class DiagnosticsDomainReachabilityDomainUiModel(
    val domain: String,
    val verdict: String,
    val resolvedIps: String,
    val tls13: String,
    val tls12: String,
    val http: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsDomainReachabilityToolUiModel(
    val state: DiagnosticsDomainReachabilityState = DiagnosticsDomainReachabilityState.Idle,
    val summary: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsDomainReachabilityDomainUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)

@Immutable
data class DiagnosticsRknBlockTypeUiModel(
    val label: String,
    val count: Int,
    val tone: DiagnosticsTone,
)

@Immutable
data class DiagnosticsRknTargetUiModel(
    val group: String,
    val name: String,
    val verdict: String,
    val notes: String,
    val tone: DiagnosticsTone,
)

@Immutable
data class DiagnosticsRknSelfInfoUiModel(
    val maskedIp: String,
    val provider: String,
    val asn: String?,
    val org: String?,
    val location: String?,
    val source: String,
)

@Stable
data class DiagnosticsRknBlockDiagnosisToolUiModel(
    val state: DiagnosticsRknBlockDiagnosisState = DiagnosticsRknBlockDiagnosisState.Idle,
    val headline: String = "",
    val confidenceNote: String = "",
    val summary: String = "",
    val fetchSelfInfoEnabled: Boolean = false,
    val selfInfoPrivacyOverridden: Boolean = false,
    val selfInfo: DiagnosticsRknSelfInfoUiModel? = null,
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val blockTypes: ImmutableList<DiagnosticsRknBlockTypeUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsRknTargetUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)

@Immutable
data class DiagnosticsCompressionCodecUiModel(
    val codec: String,
    val verdict: String,
    val compressedBytes: String,
    val decompressedBytes: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsCompressionProbeToolUiModel(
    val state: DiagnosticsCompressionProbeState = DiagnosticsCompressionProbeState.Idle,
    val targetUrl: String? = null,
    val summary: String = "",
    val includeZstd: Boolean = false,
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsCompressionCodecUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)

@Immutable
data class DiagnosticsTcp16AsnUiModel(
    val asn: String,
    val providers: String,
    val checked: String,
    val detected: String,
    val dead: String,
    val errors: String,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsTcp16FatHeaderToolUiModel(
    val state: DiagnosticsTcp16FatHeaderState = DiagnosticsTcp16FatHeaderState.Idle,
    val summary: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsTcp16AsnUiModel> = persistentListOf(),
    val detectedResults: ImmutableList<DiagnosticsTcp16DetectedTargetUiModel> = persistentListOf(),
    val errorMessage: String? = null,
)

@Immutable
data class DiagnosticsTcp16DetectedTargetUiModel(
    val targetId: String,
    val asn: String,
    val provider: String,
    val ip: String,
)

@Immutable
data class DiagnosticsCompatibleSniUiModel(
    val label: String,
    val value: String,
)

@Immutable
data class DiagnosticsAllowlistSniAsnUiModel(
    val asn: String,
    val provider: String,
    val ip: String,
    val triedCount: String,
    val compatibleSnis: ImmutableList<DiagnosticsCompatibleSniUiModel>,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsAllowlistSniToolUiModel(
    val state: DiagnosticsAllowlistSniState = DiagnosticsAllowlistSniState.Idle,
    val summary: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val rows: ImmutableList<DiagnosticsAllowlistSniAsnUiModel> = persistentListOf(),
    val errorMessage: String? = null,
    val enabled: Boolean = false,
)

@Stable
data class DiagnosticsDpiToolsUiModel(
    val dnsIntegrity: DiagnosticsDnsIntegrityToolUiModel = DiagnosticsDnsIntegrityToolUiModel(),
    val dnsAvailability: DiagnosticsDnsAvailabilityToolUiModel = DiagnosticsDnsAvailabilityToolUiModel(),
    val domainReachability: DiagnosticsDomainReachabilityToolUiModel = DiagnosticsDomainReachabilityToolUiModel(),
    val rknBlockDiagnosis: DiagnosticsRknBlockDiagnosisToolUiModel = DiagnosticsRknBlockDiagnosisToolUiModel(),
    val compressionProbe: DiagnosticsCompressionProbeToolUiModel = DiagnosticsCompressionProbeToolUiModel(),
    val tcp16FatHeader: DiagnosticsTcp16FatHeaderToolUiModel = DiagnosticsTcp16FatHeaderToolUiModel(),
    val allowlistSni: DiagnosticsAllowlistSniToolUiModel = DiagnosticsAllowlistSniToolUiModel(),
    val byohCompatibility: DiagnosticsByohCompatibilityToolUiModel = DiagnosticsByohCompatibilityToolUiModel(),
    val dpiSuite: DiagnosticsDpiSuiteToolUiModel = DiagnosticsDpiSuiteToolUiModel(),
)

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
    val fields: ImmutableList<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsNetworkSnapshotUiModel(
    val title: String,
    val subtitle: String,
    val fieldGroups: ImmutableList<DiagnosticsFieldGroupUiModel>,
) {
    val fields: ImmutableList<DiagnosticsFieldUiModel> get() = fieldGroups.flatMap { it.fields }.toImmutableList()
}

@Stable
data class DiagnosticsContextGroupUiModel(
    val title: String,
    val fields: ImmutableList<DiagnosticsFieldUiModel>,
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
    val packRefs: ImmutableList<String> = persistentListOf(),
    val policyAccess: DiagnosticsJurisdictionProfileAccess = DiagnosticsJurisdictionProfileAccess.ALLOWED,
    val requiresExplicitConsent: Boolean = false,
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

enum class DiagnosticsRemediationActionKindUiModel {
    OPEN_ADVANCED_SETTINGS,
    OPEN_VPN_PERMISSION,
    OPEN_DNS_SETTINGS,
    OPEN_DIAGNOSTICS,
    OPEN_HISTORY,
    OPEN_MODE_EDITOR,
    OPEN_OWNED_STACK_BROWSER,
}

@Immutable
data class DiagnosticsRemediationActionUiModel(
    val label: String,
    val kind: DiagnosticsRemediationActionKindUiModel,
    val targetUrl: String? = null,
)

@Immutable
data class DiagnosticsRemediationStepUiModel(
    val text: String,
)

@Immutable
data class DiagnosticsRemediationLadderUiModel(
    val title: String,
    val summary: String,
    val steps: ImmutableList<DiagnosticsRemediationStepUiModel>,
    val primaryAction: DiagnosticsRemediationActionUiModel,
    val tone: DiagnosticsTone = DiagnosticsTone.Warning,
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
    RESOLUTION_FAILED,
}

enum class DpiFailureClass {
    TCP_RESET,
    SILENT_DROP,
    TLS_ALERT,
    HTTP_BLOCKPAGE,
    QUIC_BREAKAGE,
    TLS_HANDSHAKE_FAILURE,
    CONNECTION_FREEZE,
    REDIRECT,
    OTHER,
}

@Immutable
data class ScanNetworkContextUiModel(
    val transport: String,
    val signalLabel: String?,
    val resolverLabel: String?,
    val validated: Boolean,
)

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
    /** Wall-clock start of the run; the card re-derives elapsed and ETA from this on its own clock. */
    val scanStartedAtMs: Long,
    val phaseSteps: ImmutableList<PhaseStepUiModel>,
    val currentProbeLabel: String,
    val strategyProbeProgress: DiagnosticsStrategyProbeLiveProgressUiModel? = null,
    val dnsBaselineStatus: DnsBaselineStatus? = null,
    val dpiFailureClass: DpiFailureClass? = null,
    val networkContext: ScanNetworkContextUiModel? = null,
    val candidateTimeline: ImmutableList<StrategyCandidateTimelineEntryUiModel> = persistentListOf(),
    val completedProbes: ImmutableList<CompletedProbeUiModel> = persistentListOf(),
)

@Stable
data class DiagnosticsProbeResultUiModel(
    val id: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val probeRetryCount: Int? = null,
    val tone: DiagnosticsTone,
    val details: ImmutableList<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsProbeGroupUiModel(
    val title: String,
    val items: ImmutableList<DiagnosticsProbeResultUiModel>,
)

@Immutable
data class DiagnosticsDiagnosisUiModel(
    val code: String,
    val summary: String,
    val severity: String,
    val target: String? = null,
    val tone: DiagnosticsTone,
    val evidence: ImmutableList<String> = persistentListOf(),
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
    val values: ImmutableList<Float>,
    val tone: DiagnosticsTone = DiagnosticsTone.Info,
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

@Immutable
data class SensitiveProfileConsentDialogState(
    val profileId: String?,
    val profileName: String,
    val pathMode: ScanPathMode,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
)

@Stable
data class DiagnosticsSessionDetailUiModel(
    val session: DiagnosticsSessionRowUiModel,
    val diagnoses: ImmutableList<DiagnosticsDiagnosisUiModel> = persistentListOf(),
    val reportMetadata: ImmutableList<DiagnosticsFieldUiModel> = persistentListOf(),
    val capabilityEvidence: ImmutableList<DiagnosticsCapabilityEvidenceUiModel> = persistentListOf(),
    val probeGroups: ImmutableList<DiagnosticsProbeGroupUiModel>,
    val snapshots: ImmutableList<DiagnosticsNetworkSnapshotUiModel>,
    val events: ImmutableList<DiagnosticsEventUiModel>,
    val contextGroups: ImmutableList<DiagnosticsContextGroupUiModel>,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val hasSensitiveDetails: Boolean,
    val sensitiveDetailsVisible: Boolean,
)

@Stable
data class DiagnosticsCapabilityEvidenceUiModel(
    val authority: String,
    val summary: String,
    val fields: ImmutableList<DiagnosticsFieldUiModel>,
)

@Stable
data class DiagnosticsStrategyProbeCandidateUiModel(
    val id: String,
    val label: String,
    val outcome: String,
    val rationale: String,
    val metrics: ImmutableList<DiagnosticsMetricUiModel>,
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
    val notes: ImmutableList<String>,
    val metrics: ImmutableList<DiagnosticsMetricUiModel>,
    val signature: ImmutableList<DiagnosticsFieldUiModel>,
    val resultGroups: ImmutableList<DiagnosticsProbeGroupUiModel>,
)

@Stable
data class DiagnosticsStrategyProbeWinningCandidateUiModel(
    val id: String,
    val label: String,
    val familyLabel: String,
    val outcome: String,
    val rationale: String,
    val metrics: ImmutableList<DiagnosticsMetricUiModel>,
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
    val candidates: ImmutableList<DiagnosticsStrategyProbeCandidateUiModel>,
)

@Stable
data class DiagnosticsStrategyProbeRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: ImmutableList<DiagnosticsFieldUiModel>,
    val signature: ImmutableList<DiagnosticsFieldUiModel>,
)

@Immutable
data class DiagnosticsStrategyProbeReportPresentationUiModel(
    val statusLabel: String,
    val statusTone: DiagnosticsTone,
    val matrixTitle: String,
    val manualApplyBadge: String,
    val supportsWinningPath: Boolean,
    val isIncomplete: Boolean,
    val showFullMatrixInitially: Boolean,
    val auditConfidenceLabel: String? = null,
    val auditConfidenceTone: DiagnosticsTone? = null,
    val auditAssessmentMetrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
)

@Stable
data class DiagnosticsStrategyProbeReportUiModel(
    val suiteId: String,
    val suiteLabel: String,
    val summaryMetrics: ImmutableList<DiagnosticsMetricUiModel>,
    val completionKind: StrategyProbeCompletionKind,
    val auditAssessment: StrategyProbeAuditAssessment? = null,
    val recommendation: DiagnosticsStrategyProbeRecommendationUiModel? = null,
    val winningPath: DiagnosticsStrategyProbeWinningPathUiModel? = null,
    val families: ImmutableList<DiagnosticsStrategyProbeFamilyUiModel>,
    val candidateDetails: ImmutableMap<String, DiagnosticsStrategyProbeCandidateDetailUiModel> = persistentMapOf(),
    val presentation: DiagnosticsStrategyProbeReportPresentationUiModel? = null,
)

@Stable
data class DiagnosticsResolverRecommendationUiModel(
    val headline: String,
    val rationale: String,
    val fields: ImmutableList<DiagnosticsFieldUiModel>,
    val appliedTemporarily: Boolean,
    val persistable: Boolean,
)

@Immutable
data class DiagnosticsScanWorkflowBadgeUiModel(
    val text: String,
    val tone: DiagnosticsTone,
)

@Immutable
data class DiagnosticsScanWorkflowPresentationUiModel(
    val title: String,
    val body: String,
    val tone: DiagnosticsTone,
    val badges: ImmutableList<DiagnosticsScanWorkflowBadgeUiModel> = persistentListOf(),
    val rawActionLabel: String,
    val inPathActionLabel: String,
)

@Stable
data class DiagnosticsOverviewUiModel(
    val health: DiagnosticsHealth = DiagnosticsHealth.Idle,
    val headline: String = "",
    val body: String = "",
    val activeProfile: DiagnosticsProfileOptionUiModel? = null,
    val recentAutomaticProbe: DiagnosticsAutomaticProbeCalloutUiModel? = null,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val contextSummary: DiagnosticsContextGroupUiModel? = null,
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val warnings: ImmutableList<DiagnosticsEventUiModel> = persistentListOf(),
    val rememberedNetworks: ImmutableList<DiagnosticsRememberedNetworkUiModel> = persistentListOf(),
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
    val profiles: ImmutableList<DiagnosticsProfileOptionUiModel> = persistentListOf(),
    val selectedProfileId: String? = null,
    val selectedProfile: DiagnosticsProfileOptionUiModel? = null,
    val activePathMode: com.poyka.ripdpi.diagnostics.ScanPathMode = com.poyka.ripdpi.diagnostics.ScanPathMode.RAW_PATH,
    val activeProgress: DiagnosticsProgressUiModel? = null,
    val latestSession: DiagnosticsSessionRowUiModel? = null,
    val diagnoses: ImmutableList<DiagnosticsDiagnosisUiModel> = persistentListOf(),
    val latestResults: ImmutableList<DiagnosticsProbeResultUiModel> = persistentListOf(),
    val selectedProfileScopeLabel: String? = null,
    val runRawEnabled: Boolean = true,
    val runInPathEnabled: Boolean = true,
    val runRawHint: String? = null,
    val runInPathHint: String? = null,
    val policyNoticeMessage: String? = null,
    val workflowRestriction: DiagnosticsWorkflowRestrictionUiModel? = null,
    val remediationLadder: DiagnosticsRemediationLadderUiModel? = null,
    val workflowPresentation: DiagnosticsScanWorkflowPresentationUiModel? = null,
    val resolverRecommendation: DiagnosticsResolverRecommendationUiModel? = null,
    val strategyProbeReport: DiagnosticsStrategyProbeReportUiModel? = null,
    val hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    val sensitiveProfileConsentDialog: SensitiveProfileConsentDialogState? = null,
    val queuedManualScanRequest: QueuedManualScanRequest? = null,
    val isBusy: Boolean = false,
)

@Stable
data class DiagnosticsLiveUiModel(
    val health: DiagnosticsHealth = DiagnosticsHealth.Idle,
    val statusLabel: String = "",
    val statusTone: DiagnosticsTone = DiagnosticsTone.Neutral,
    val freshnessLabel: String = "",
    /**
     * Wall-clock timestamp (epoch ms) of the snapshot behind [freshnessLabel], or
     * null when no live telemetry exists yet. The live panel uses it to compute a
     * staleness badge against a ticking clock — see `LiveHeroCard`. Distinct from
     * the formatted [freshnessLabel] because staleness needs the raw age.
     */
    val currentTelemetryTimestampMs: Long? = null,
    val headline: String = "",
    val body: String = "",
    val networkLabel: String? = null,
    val modeLabel: String? = null,
    val signalLabel: String = "",
    val eventSummaryLabel: String = "",
    val highlights: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
    val trends: ImmutableList<DiagnosticsSparklineUiModel> = persistentListOf(),
    val snapshot: DiagnosticsNetworkSnapshotUiModel? = null,
    val contextGroups: ImmutableList<DiagnosticsContextGroupUiModel> = persistentListOf(),
    val passiveEvents: ImmutableList<DiagnosticsEventUiModel> = persistentListOf(),
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
    val sessions: ImmutableList<DiagnosticsSessionRowUiModel> = persistentListOf(),
    val pathModes: ImmutableList<String> = persistentListOf(),
    val statuses: ImmutableList<String> = persistentListOf(),
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
    val metrics: ImmutableList<DiagnosticsMetricUiModel>,
    val tone: DiagnosticsTone,
)

@Stable
data class DiagnosticsApproachDetailUiModel(
    val approach: DiagnosticsApproachRowUiModel,
    val signature: ImmutableList<DiagnosticsFieldUiModel>,
    val breakdown: ImmutableList<DiagnosticsMetricUiModel>,
    val runtimeSummary: ImmutableList<DiagnosticsMetricUiModel>,
    val recentSessions: ImmutableList<DiagnosticsSessionRowUiModel>,
    val recentUsageNotes: ImmutableList<String>,
    val failureNotes: ImmutableList<String>,
)

@Stable
data class DiagnosticsApproachesUiModel(
    val selectedMode: DiagnosticsApproachMode = DiagnosticsApproachMode.Profiles,
    val rows: ImmutableList<DiagnosticsApproachRowUiModel> = persistentListOf(),
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
    val events: ImmutableList<DiagnosticsEventUiModel> = persistentListOf(),
    val availableSources: ImmutableList<String> = persistentListOf(),
    val availableSeverities: ImmutableList<String> = persistentListOf(),
    val focusedEventId: String? = null,
)

@Stable
data class DiagnosticsShareUiModel(
    val targetSessionId: String? = null,
    val previewTitle: String = "",
    val previewBody: String = "",
    val metrics: ImmutableList<DiagnosticsMetricUiModel> = persistentListOf(),
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
        RequestLocalNetwork,
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
        val actionLabel: String? = null,
        val action: SnackbarAction? = null,
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
    val dpiFailureClass: DpiFailureClass? = null,
    val pendingAutoOpenAuditSessionId: String? = null,
    val hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    val sensitiveProfileConsentDialog: SensitiveProfileConsentDialogState? = null,
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
    val serviceStatus: com.poyka.ripdpi.data.AppStatus,
)

internal data class UiControlState(
    val selection: SelectionState,
    val filter: FilterState,
    val sessionDetail: SessionDetailState,
    val scanLifecycle: ScanLifecycleState,
)
