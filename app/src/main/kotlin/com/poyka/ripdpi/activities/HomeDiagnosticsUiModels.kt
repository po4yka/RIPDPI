package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class HomeDiagnosticsActionUiState(
    val label: String = "",
    val supportingText: String = "",
    /**
     * What a screen reader should hear while the action runs.
     *
     * [supportingText] carries a per-stage progress detail that ticks continuously, which
     * a polite live region would announce over and over. This holds the coarse form --
     * stage counter plus stage name -- so it changes only when the stage does. Blank when
     * the action is not running a staged operation, in which case [supportingText] stands
     * on its own.
     */
    val stageAnnouncement: String = "",
    val enabled: Boolean = false,
    val busy: Boolean = false,
)

@Immutable
data class HomeDiagnosticsLatestAuditUiState(
    val headline: String,
    val summary: String,
    val recommendationSummary: String? = null,
    val ownedStackLaunchUrl: String? = null,
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val totalStageCount: Int = 0,
    val stale: Boolean = false,
    val actionable: Boolean = false,
    val directModeResult: DirectModeVerdictResult? = null,
    val directModeReasonCode: DirectModeReasonCode? = null,
    val directTransportClass: DirectTransportClass? = null,
    val transportRemediationEvidence: TransportRemediationEvidence = TransportRemediationEvidence(),
)

enum class AnalysisStageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

@Immutable
data class AnalysisStageUiState(
    val status: AnalysisStageStatus,
    val progress: Float = 0f,
)

@Immutable
data class AnalysisProgressUiState(
    val stages: ImmutableList<AnalysisStageUiState>,
    val activeStageIndex: Int?,
)

enum class HomeDiagnosticsRunUiStatus {
    IDLE,
    STARTING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

@Immutable
data class HomeDiagnosticsStageUiState(
    val label: String,
    val headline: String,
    val summary: String,
    val failed: Boolean = false,
    val skipped: Boolean = false,
    val recommendationContributor: Boolean = false,
)

@Immutable
data class HomeAnalysisLabeledRow(
    val label: String,
    val value: String,
)

@Immutable
data class HomeDiagnosticsAnalysisSheetUiState(
    val runId: String,
    val headline: String,
    val summary: String,
    val confidenceSummary: String? = null,
    val coverageSummary: String? = null,
    val recommendationSummary: String? = null,
    val appliedSettings: ImmutableList<DiagnosticsAppliedSetting> = persistentListOf(),
    val capabilityEvidence: ImmutableList<DiagnosticsCapabilityEvidenceUiModel> = persistentListOf(),
    val stageSummaries: ImmutableList<HomeDiagnosticsStageUiState> = persistentListOf(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val shareBusy: Boolean = false,
    val detectionVerdict: String? = null,
    val detectionFindings: ImmutableList<String> = persistentListOf(),
    val installedVpnDetectorCount: Int? = null,
    val installedVpnDetectorTopApps: ImmutableList<String> = persistentListOf(),
    val pcapRecordingRequested: Boolean = false,
    val remediationLadder: DiagnosticsRemediationLadderUiModel? = null,
    val actionableHeadline: String? = null,
    val actionableNextSteps: ImmutableList<String> = persistentListOf(),
    val networkCharacterRows: ImmutableList<HomeAnalysisLabeledRow> = persistentListOf(),
    val networkCharacterNotes: ImmutableList<String> = persistentListOf(),
    val strategyEffectivenessRows: ImmutableList<HomeAnalysisLabeledRow> = persistentListOf(),
    val routingSanitySummary: String? = null,
    val routingSanityFindings: ImmutableList<HomeAnalysisLabeledRow> = persistentListOf(),
    val regressionDeltaSummary: String? = null,
    val regressionDeltaFailures: ImmutableList<String> = persistentListOf(),
    val regressionDeltaRecoveries: ImmutableList<String> = persistentListOf(),
    val bufferbloatSummary: String? = null,
    val dnsCharacterizationSummary: String? = null,
    val dnsCharacterizationNotes: ImmutableList<String> = persistentListOf(),
)

@Immutable
data class HomeDiagnosticsVerificationSheetUiState(
    val sessionId: String,
    val success: Boolean,
    val headline: String,
    val summary: String,
    val detail: String? = null,
)

@Immutable
data class HomeDiagnosticsUiState(
    val analysisAction: HomeDiagnosticsActionUiState = HomeDiagnosticsActionUiState(),
    val verifiedVpnAction: HomeDiagnosticsActionUiState = HomeDiagnosticsActionUiState(),
    val latestAudit: HomeDiagnosticsLatestAuditUiState? = null,
    val remediationLadder: DiagnosticsRemediationLadderUiModel? = null,
    val analysisProgress: AnalysisProgressUiState? = null,
    val analysisRunStatus: HomeDiagnosticsRunUiStatus = HomeDiagnosticsRunUiStatus.IDLE,
    val quickScanBusy: Boolean = false,
    val analysisSheet: HomeDiagnosticsAnalysisSheetUiState? = null,
    val verificationSheet: HomeDiagnosticsVerificationSheetUiState? = null,
    val pcapRecordingRequested: Boolean = false,
    val pcapToggleVisible: Boolean = false,
)
