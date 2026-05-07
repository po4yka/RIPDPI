package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection

internal data class ResolvedDiagnosticsUiInput(
    val activeProfile: DiagnosticProfile?,
    val visibleProfiles: List<DiagnosticProfile>,
    val omittedProfileCount: Int,
    val activeProfileRequest: DiagnosticsProfileProjection?,
    val selectedProfileUi: DiagnosticsProfileOptionUiModel?,
    val latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    val latestContext: DiagnosticContextModel?,
    val latestCompletedSession: DiagnosticScanSession?,
    val recentAutomaticProbe: DiagnosticsAutomaticProbeCalloutUiModel?,
    val latestProfileSession: DiagnosticScanSession?,
    val latestReport: DiagnosticsSessionProjection?,
    val latestReportResults: List<DiagnosticsProbeResultUiModel>,
    val latestResolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    val latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    val currentTelemetry: DiagnosticTelemetrySample?,
    val health: DiagnosticsHealth,
    val warnings: List<DiagnosticsEventUiModel>,
    val rememberedNetworkRows: List<DiagnosticsRememberedNetworkUiModel>,
    val selectedSection: DiagnosticsSection,
    val sessionDetailWithVisibility: DiagnosticsSessionDetailUiModel?,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel?,
)

internal data class DiagnosticsEventsState(
    val model: DiagnosticsEventsUiModel,
    val selectedEvent: DiagnosticsEventUiModel?,
)
