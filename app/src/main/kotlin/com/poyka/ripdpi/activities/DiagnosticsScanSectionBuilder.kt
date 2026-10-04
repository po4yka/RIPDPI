package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.ui.diagnostics.toScopeLabel
import kotlinx.collections.immutable.toImmutableList

internal data class BuildScanUiModelParams(
    val profiles: List<DiagnosticProfile>,
    val omittedProfileCount: Int = 0,
    val activeProfile: DiagnosticProfile?,
    val activeProfileRequest: DiagnosticsProfileProjection?,
    val latestProfileSession: DiagnosticScanSession?,
    val activeScanPathMode: ScanPathMode?,
    val latestReportResults: List<DiagnosticsProbeResultUiModel>,
    val latestResolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    val latestStrategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    val progress: ScanProgress?,
    val rawArgsEnabled: Boolean,
    val serviceStatus: AppStatus,
    val serviceMode: Mode = Mode.VPN,
    val autoResumeAfterRawScan: Boolean = false,
    val scanStartedAt: Long?,
    val completedProbes: List<CompletedProbeUiModel> = emptyList(),
    val candidateTimeline: List<StrategyCandidateTimelineEntryUiModel> = emptyList(),
    val dnsBaselineStatus: DnsBaselineStatus? = null,
    val dpiFailureClass: DpiFailureClass? = null,
    val networkContext: ScanNetworkContextUiModel? = null,
    val vpnPermissionDisabled: Boolean = false,
    val hiddenProbeConflictDialog: HiddenProbeConflictDialogState? = null,
    val sensitiveProfileConsentDialog: SensitiveProfileConsentDialogState? = null,
    val queuedManualScanRequest: QueuedManualScanRequest? = null,
)

internal fun DiagnosticsUiFactorySupport.buildScanUiModel(params: BuildScanUiModelParams): DiagnosticsScanUiModel {
    val selectedProfile = params.activeProfile?.let(::toProfileOptionUiModel)
    val strategyProbeSelected = selectedProfile?.isStrategyProbe == true
    val serviceRunning = params.serviceStatus == AppStatus.Running
    val runRawEnabled = params.progress == null && !(strategyProbeSelected && params.rawArgsEnabled)
    val runInPathEnabled = params.progress == null && !strategyProbeSelected && serviceRunning
    val workflowRestriction = buildWorkflowRestriction(params, selectedProfile, strategyProbeSelected)
    val workflowLabel = buildWorkflowLabel(selectedProfile)
    val runRawHint = buildRawScanHint(strategyProbeSelected, workflowLabel, params.autoResumeAfterRawScan)
    val runInPathHint =
        when {
            strategyProbeSelected -> context.getString(R.string.diagnostics_scan_raw_only_format, workflowLabel)
            params.serviceStatus == AppStatus.Reconnecting -> context.getString(R.string.diagnostics_scope_reconnecting)
            !serviceRunning -> context.getString(R.string.diagnostics_scan_in_path_service_halted)
            params.serviceMode == Mode.VPN -> context.getString(R.string.diagnostics_scope_active_vpn_description)
            else -> context.getString(R.string.diagnostics_scope_active_proxy_description)
        }
    val remediationLadder =
        buildScanRemediationLadder(
            selectedProfile = selectedProfile,
            workflowRestriction = workflowRestriction,
            resolverRecommendation = params.latestResolverRecommendation,
            strategyProbeReport = params.latestStrategyProbeReport,
            latestSession = params.latestProfileSession?.let(::toSessionRowUiModel),
        )
    val activeProgress = buildActiveScanProgress(params, selectedProfile)

    return DiagnosticsScanUiModel(
        profiles = params.profiles.map(::toProfileOptionUiModel).toImmutableList(),
        selectedProfileId = params.activeProfile?.id,
        selectedProfile = selectedProfile,
        activePathMode =
            params.activeScanPathMode
                ?: params.latestProfileSession?.pathMode?.let(::parsePathMode)
                ?: ScanPathMode.RAW_PATH,
        activeProgress = activeProgress,
        latestSession = params.latestProfileSession?.let(::toSessionRowUiModel),
        diagnoses =
            params.latestProfileSession
                ?.report
                ?.diagnoses
                ?.map(::toDiagnosisUiModel)
                .orEmpty()
                .toImmutableList(),
        latestResults = params.latestReportResults.toImmutableList(),
        selectedProfileScopeLabel = toScopeLabel(params.activeProfileRequest, params.rawArgsEnabled),
        runRawEnabled = runRawEnabled,
        runInPathEnabled = runInPathEnabled,
        runRawHint = runRawHint,
        runInPathHint = runInPathHint,
        policyNoticeMessage =
            if (params.omittedProfileCount > 0) {
                context.getString(R.string.diagnostics_scan_policy_notice)
            } else {
                null
            },
        workflowRestriction = workflowRestriction,
        remediationLadder = remediationLadder,
        workflowPresentation =
            selectedProfile?.let { profile ->
                buildWorkflowPresentation(
                    profile = profile,
                    scanIsBusy = params.progress != null,
                    runRawEnabled = runRawEnabled,
                    strategyProbeSelected = strategyProbeSelected,
                    strategyProbeReport = params.latestStrategyProbeReport,
                    workflowRestriction = workflowRestriction,
                )
            },
        resolverRecommendation = params.latestResolverRecommendation,
        strategyProbeReport = params.latestStrategyProbeReport,
        hiddenProbeConflictDialog = params.hiddenProbeConflictDialog,
        sensitiveProfileConsentDialog = params.sensitiveProfileConsentDialog,
        queuedManualScanRequest = params.queuedManualScanRequest,
        isBusy = params.progress != null,
    )
}

private fun DiagnosticsUiFactorySupport.buildRawScanHint(
    strategyProbeSelected: Boolean,
    workflowLabel: String,
    autoResumeAfterRawScan: Boolean,
): String =
    listOfNotNull(
        context.getString(R.string.diagnostics_scope_direct_description),
        if (strategyProbeSelected) {
            context.getString(R.string.diagnostics_scan_raw_path_format, workflowLabel)
        } else {
            null
        },
        context.getString(
            if (autoResumeAfterRawScan) {
                R.string.diagnostics_scope_resume_enabled
            } else {
                R.string.diagnostics_scope_resume_disabled
            },
        ),
    ).joinToString(" ")

private fun DiagnosticsUiFactorySupport.buildActiveScanProgress(
    params: BuildScanUiModelParams,
    selectedProfile: DiagnosticsProfileOptionUiModel?,
): DiagnosticsProgressUiModel? =
    params.progress?.let { progress ->
        toProgressUiModel(
            progress = progress,
            scanKind = selectedProfile?.kind ?: ScanKind.CONNECTIVITY,
            isFullAudit = selectedProfile?.isFullAudit == true,
            scanStartedAt = params.scanStartedAt ?: System.currentTimeMillis(),
            completedProbes = params.completedProbes,
            candidateTimeline = params.candidateTimeline,
            dnsBaselineStatus = params.dnsBaselineStatus,
            dpiFailureClass = params.dpiFailureClass,
            networkContext = params.networkContext,
        )
    }
