package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.proto.AppSettings

internal data class DiagnosticsUiStateInput(
    val profiles: List<DiagnosticProfile>,
    val settings: AppSettings,
    val progress: ScanProgress?,
    val sessions: List<DiagnosticScanSession>,
    val approachStats: List<BypassApproachSummary>,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val contexts: List<DiagnosticContextSnapshot>,
    val currentTelemetry: DiagnosticTelemetrySample?,
    val telemetry: List<DiagnosticTelemetrySample>,
    val nativeEvents: List<DiagnosticEvent>,
    val activeConnectionSession: DiagnosticConnectionSession?,
    val liveSnapshots: List<DiagnosticNetworkSnapshot>,
    val liveContexts: List<DiagnosticContextSnapshot>,
    val liveTelemetry: List<DiagnosticTelemetrySample>,
    val liveNativeEvents: List<DiagnosticEvent>,
    val exports: List<DiagnosticExportRecord>,
    val rememberedPolicies: List<DiagnosticsRememberedPolicy>,
    val activeConnectionPolicy: DiagnosticActiveConnectionPolicy?,
    val serviceStatus: AppStatus,
    val selectedSectionRequest: DiagnosticsSection,
    val selectedProfileId: String?,
    val selectedApproachMode: DiagnosticsApproachMode,
    val selectedProbe: DiagnosticsProbeResultUiModel?,
    val selectedEventId: String?,
    val sessionPathMode: String?,
    val sessionStatus: String?,
    val sessionSearch: String,
    val eventSource: String?,
    val eventSeverity: String?,
    val eventSearch: String,
    val eventAutoScroll: Boolean,
    val selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
    val selectedApproachDetail: DiagnosticsApproachDetailUiModel?,
    val sensitiveSessionDetailsVisible: Boolean,
    val archiveActionState: ArchiveActionState,
    val scanStartedAt: Long?,
    val activeScanPathMode: ScanPathMode?,
    val completedProbes: List<CompletedProbeUiModel> = emptyList(),
    val candidateTimeline: List<StrategyCandidateTimelineEntryUiModel> = emptyList(),
    val dnsBaselineStatus: DnsBaselineStatus? = null,
    val dpiFailureClass: DpiFailureClass? = null,
    val hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    val sensitiveProfileConsentDialog: SensitiveProfileConsentDialogState? = null,
    val queuedManualScanRequest: QueuedManualScanRequest? = null,
)
