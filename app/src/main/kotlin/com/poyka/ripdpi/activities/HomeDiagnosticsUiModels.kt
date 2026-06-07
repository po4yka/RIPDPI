package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class HomeDiagnosticsActionUiState(
    val label: String = "",
    val supportingText: String = "",
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
    val appliedSettings: List<DiagnosticsAppliedSetting> = emptyList(),
    val capabilityEvidence: List<DiagnosticsCapabilityEvidenceUiModel> = emptyList(),
    val stageSummaries: List<HomeDiagnosticsStageUiState> = emptyList(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val shareBusy: Boolean = false,
    val detectionVerdict: String? = null,
    val detectionFindings: List<String> = emptyList(),
    val installedVpnDetectorCount: Int? = null,
    val installedVpnDetectorTopApps: List<String> = emptyList(),
    val pcapRecordingRequested: Boolean = false,
    val remediationLadder: DiagnosticsRemediationLadderUiModel? = null,
    val actionableHeadline: String? = null,
    val actionableNextSteps: List<String> = emptyList(),
    val networkCharacterRows: List<HomeAnalysisLabeledRow> = emptyList(),
    val networkCharacterNotes: List<String> = emptyList(),
    val strategyEffectivenessRows: List<HomeAnalysisLabeledRow> = emptyList(),
    val routingSanitySummary: String? = null,
    val routingSanityFindings: List<HomeAnalysisLabeledRow> = emptyList(),
    val regressionDeltaSummary: String? = null,
    val regressionDeltaFailures: List<String> = emptyList(),
    val regressionDeltaRecoveries: List<String> = emptyList(),
    val bufferbloatSummary: String? = null,
    val dnsCharacterizationSummary: String? = null,
    val dnsCharacterizationNotes: List<String> = emptyList(),
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
    val quickScanBusy: Boolean = false,
    val analysisSheet: HomeDiagnosticsAnalysisSheetUiState? = null,
    val verificationSheet: HomeDiagnosticsVerificationSheetUiState? = null,
    val pcapRecordingRequested: Boolean = false,
    val pcapToggleVisible: Boolean = false,
)
