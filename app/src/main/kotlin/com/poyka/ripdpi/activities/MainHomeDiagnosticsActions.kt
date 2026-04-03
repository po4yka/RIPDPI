package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeProgress
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private const val HomeVerificationProfileId = "default"

internal data class HomeDiagnosticsRuntimeState(
    val activeRunId: String? = null,
    val activeRunProgress: DiagnosticsHomeCompositeProgress? = null,
    val activeRunStageProgress: String? = null,
    val latestCompositeOutcome: DiagnosticsHomeCompositeOutcome? = null,
    val analysisSheetVisible: Boolean = false,
    val shareBusy: Boolean = false,
    val activeVerificationSessionId: String? = null,
    val waitingForVerifiedVpnStart: Boolean = false,
    val verificationProgress: String? = null,
    val verificationSheet: DiagnosticsHomeVerificationOutcome? = null,
    val currentFingerprintHash: String? = null,
    val externalScanActive: Boolean = false,
    val externalScanMessage: String? = null,
)

internal class MainHomeDiagnosticsActions(
    private val mutations: MainMutationRunner,
    private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
    private val diagnosticsScanController: DiagnosticsScanController,
    private val diagnosticsShareService: DiagnosticsShareService,
    private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
    private val diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService,
    private val serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
    private val runtimeState: MutableStateFlow<ConnectionRuntimeState>,
    private val permissionState: MutableStateFlow<PermissionRuntimeState>,
    private val homeDiagnosticsState: MutableStateFlow<HomeDiagnosticsRuntimeState>,
    private val stringResolver: StringResolver,
    private val requestVpnStart: () -> Unit,
) {
    private var activeRunObservation: Job? = null

    fun initialize() {
        mutations.launch {
            refreshFingerprint()
        }
        mutations.launch {
            diagnosticsTimelineSource.activeScanProgress.collect { progress ->
                val progressSessionId = progress?.sessionId
                val progressMessage = progress?.message
                homeDiagnosticsState.update { current ->
                    current.copy(
                        activeRunStageProgress =
                            if (progressSessionId == current.activeRunProgress?.activeSessionId) {
                                progressMessage
                            } else {
                                current.activeRunStageProgress
                            },
                        verificationProgress =
                            when {
                                progressSessionId == current.activeVerificationSessionId -> {
                                    progressMessage
                                }

                                current.waitingForVerifiedVpnStart -> {
                                    current.verificationProgress
                                }

                                else -> {
                                    null
                                }
                            },
                        externalScanActive =
                            progress != null &&
                                progressSessionId != current.activeRunProgress?.activeSessionId &&
                                progressSessionId != current.activeVerificationSessionId,
                        externalScanMessage =
                            if (
                                progress != null &&
                                progressSessionId != current.activeRunProgress?.activeSessionId &&
                                progressSessionId != current.activeVerificationSessionId
                            ) {
                                progressMessage
                            } else {
                                null
                            },
                    )
                }
                if (progress == null) {
                    homeDiagnosticsState.update { current ->
                        current.copy(
                            activeRunStageProgress =
                                if (current.activeRunId == null) {
                                    null
                                } else {
                                    current.activeRunStageProgress
                                },
                            verificationProgress =
                                if (current.activeVerificationSessionId == null &&
                                    !current.waitingForVerifiedVpnStart
                                ) {
                                    null
                                } else {
                                    current.verificationProgress
                                },
                        )
                    }
                }
            }
        }
        mutations.launch {
            diagnosticsTimelineSource.sessions.collect { sessions ->
                homeDiagnosticsState.value.activeVerificationSessionId?.let { sessionId ->
                    val session = sessions.firstOrNull { it.id == sessionId && it.status != "running" }
                    if (session != null) {
                        val outcome =
                            runCatching { diagnosticsHomeWorkflowService.summarizeVerification(sessionId) }
                                .getOrElse {
                                    DiagnosticsHomeVerificationOutcome(
                                        sessionId = sessionId,
                                        success = false,
                                        headline = "VPN verification was incomplete",
                                        summary = session.summary,
                                    )
                                }
                        homeDiagnosticsState.update {
                            it.copy(
                                activeVerificationSessionId = null,
                                waitingForVerifiedVpnStart = false,
                                verificationProgress = null,
                                verificationSheet = outcome,
                            )
                        }
                        refreshFingerprint()
                    }
                }
            }
        }
        mutations.launch {
            runtimeState
                .map { it.connectionState }
                .distinctUntilChanged()
                .collect { state ->
                    when {
                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                            state == ConnectionState.Connected &&
                            serviceStateStore.status.value.first == AppStatus.Running &&
                            serviceStateStore.status.value.second == Mode.VPN
                        -> {
                            startVerificationScan()
                        }

                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                            state == ConnectionState.Error
                        -> {
                            homeDiagnosticsState.update {
                                it.copy(
                                    waitingForVerifiedVpnStart = false,
                                    verificationProgress = null,
                                    verificationSheet =
                                        DiagnosticsHomeVerificationOutcome(
                                            sessionId = "",
                                            success = false,
                                            headline = "VPN failed to start",
                                            summary =
                                                mutations.currentUiState().errorMessage
                                                    ?: stringResolver.getString(R.string.connection_timed_out),
                                        ),
                                )
                            }
                        }
                    }
                }
        }
        mutations.launch {
            permissionState
                .map { it.issue?.kind }
                .distinctUntilChanged()
                .collect { issueKind ->
                    if (
                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                        issueKind == PermissionKind.VpnConsent
                    ) {
                        homeDiagnosticsState.update {
                            it.copy(
                                waitingForVerifiedVpnStart = false,
                                verificationProgress = null,
                                verificationSheet =
                                    DiagnosticsHomeVerificationOutcome(
                                        sessionId = "",
                                        success = false,
                                        headline = "VPN permission is required",
                                        summary =
                                            stringResolver.getString(
                                                R.string.home_diagnostics_permission_required,
                                            ),
                                    ),
                            )
                        }
                    }
                }
        }
        mutations.launch {
            serviceStateStore.status.collect {
                refreshFingerprint()
            }
        }
    }

    fun runFullAnalysis() {
        mutations.launch {
            activeRunObservation?.cancel()
            homeDiagnosticsState.update {
                it.copy(
                    activeRunId = null,
                    activeRunProgress = null,
                    activeRunStageProgress = null,
                    latestCompositeOutcome = null,
                    analysisSheetVisible = false,
                    verificationSheet = null,
                    activeVerificationSessionId = null,
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                )
            }
            runCatching {
                diagnosticsHomeCompositeRunService.startHomeAnalysis()
            }.onSuccess { started ->
                homeDiagnosticsState.update {
                    it.copy(
                        activeRunId = started.runId,
                        activeRunStageProgress = stringResolver.getString(R.string.home_diagnostics_analysis_running),
                    )
                }
                activeRunObservation =
                    mutations.launch {
                        diagnosticsHomeCompositeRunService.observeHomeRun(started.runId).collect { progress ->
                            homeDiagnosticsState.update { current ->
                                current.copy(
                                    activeRunId =
                                        if (progress.status == DiagnosticsHomeCompositeRunStatus.RUNNING) {
                                            progress.runId
                                        } else {
                                            null
                                        },
                                    activeRunProgress = progress,
                                    latestCompositeOutcome = progress.outcome ?: current.latestCompositeOutcome,
                                    analysisSheetVisible =
                                        if (progress.outcome != null) {
                                            true
                                        } else {
                                            current.analysisSheetVisible
                                        },
                                )
                            }
                            progress.outcome?.let { outcome ->
                                refreshFingerprint(outcome.fingerprintHash)
                            }
                        }
                    }
            }.onFailure { error ->
                val message =
                    when (error) {
                        is DiagnosticsScanStartRejectedException -> {
                            stringResolver.getString(R.string.diagnostics_error_start_failed)
                        }

                        else -> {
                            stringResolver.getString(R.string.diagnostics_error_start_failed)
                        }
                    }
                mutations.emit(MainEffect.ShowError(message))
            }
        }
    }

    fun startVerifiedVpn() {
        mutations.launch {
            val latestOutcome = homeDiagnosticsState.value.latestCompositeOutcome ?: return@launch
            val currentFingerprint = diagnosticsHomeWorkflowService.currentFingerprintHash()
            homeDiagnosticsState.update { it.copy(currentFingerprintHash = currentFingerprint) }
            if (
                latestOutcome.fingerprintHash != null &&
                currentFingerprint != null &&
                latestOutcome.fingerprintHash != currentFingerprint
            ) {
                mutations.emit(MainEffect.ShowError(stringResolver.getString(R.string.home_diagnostics_run_again)))
                return@launch
            }
            homeDiagnosticsState.update {
                it.copy(
                    waitingForVerifiedVpnStart = true,
                    verificationProgress = stringResolver.getString(R.string.home_diagnostics_vpn_starting),
                    verificationSheet = null,
                )
            }
            requestVpnStart()
        }
    }

    fun shareLatestHomeAnalysis() {
        mutations.launch {
            if (homeDiagnosticsState.value.shareBusy) return@launch
            val outcome = homeDiagnosticsState.value.latestCompositeOutcome ?: return@launch
            homeDiagnosticsState.update { it.copy(shareBusy = true) }
            runCatching {
                diagnosticsShareService.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = null,
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = System.currentTimeMillis(),
                    ),
                )
            }.onSuccess { archive ->
                homeDiagnosticsState.update { it.copy(shareBusy = false) }
                mutations.emit(
                    MainEffect.ShareDiagnosticsArchive(
                        absolutePath = archive.absolutePath,
                        fileName = archive.fileName,
                    ),
                )
            }.onFailure { error ->
                Logger.withTag(LogTags.DIAGNOSTICS).e(error) {
                    "Failed to create home analysis archive"
                }
                homeDiagnosticsState.update { it.copy(shareBusy = false) }
                mutations.emit(
                    MainEffect.ShowError(
                        stringResolver.getString(R.string.home_diagnostics_share_failed),
                    ),
                )
            }
        }
    }

    fun dismissAnalysisSheet() {
        homeDiagnosticsState.update { it.copy(analysisSheetVisible = false) }
    }

    fun dismissVerificationSheet() {
        homeDiagnosticsState.update { it.copy(verificationSheet = null) }
    }

    private suspend fun startVerificationScan() {
        if (homeDiagnosticsState.value.activeVerificationSessionId != null) {
            return
        }
        runCatching {
            diagnosticsScanController.startScan(
                pathMode = ScanPathMode.IN_PATH,
                selectedProfileId = HomeVerificationProfileId,
            )
        }.onSuccess { result ->
            when (result) {
                is DiagnosticsManualScanStartResult.Started -> {
                    homeDiagnosticsState.update {
                        it.copy(
                            waitingForVerifiedVpnStart = false,
                            activeVerificationSessionId = result.sessionId,
                            verificationProgress = stringResolver.getString(R.string.home_diagnostics_verifying),
                        )
                    }
                }

                is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                    homeDiagnosticsState.update {
                        it.copy(
                            waitingForVerifiedVpnStart = false,
                            verificationProgress = null,
                            verificationSheet =
                                DiagnosticsHomeVerificationOutcome(
                                    sessionId = "",
                                    success = false,
                                    headline = "VPN verification is busy",
                                    summary = stringResolver.getString(R.string.diagnostics_error_hidden_probe_running),
                                ),
                        )
                    }
                }
            }
        }.onFailure {
            homeDiagnosticsState.update {
                it.copy(
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                    verificationSheet =
                        DiagnosticsHomeVerificationOutcome(
                            sessionId = "",
                            success = false,
                            headline = "VPN verification could not start",
                            summary = stringResolver.getString(R.string.home_diagnostics_verification_failed),
                        ),
                )
            }
        }
    }

    private suspend fun refreshFingerprint(fallback: String? = null) {
        val fingerprint = fallback ?: diagnosticsHomeWorkflowService.currentFingerprintHash()
        homeDiagnosticsState.update { it.copy(currentFingerprintHash = fingerprint) }
    }
}

internal fun buildHomeDiagnosticsUiState(
    settings: AppSettings,
    appStatus: AppStatus,
    connectionState: ConnectionState,
    runtime: HomeDiagnosticsRuntimeState,
    stringResolver: StringResolver,
): HomeDiagnosticsUiState {
    val fingerprintMismatch =
        runtime.latestCompositeOutcome?.fingerprintHash != null &&
            runtime.currentFingerprintHash != null &&
            runtime.latestCompositeOutcome.fingerprintHash != runtime.currentFingerprintHash
    val analysisBusy = runtime.activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.RUNNING
    val verificationBusy = runtime.waitingForVerifiedVpnStart || runtime.activeVerificationSessionId != null
    val analysisEnabled =
        !analysisBusy &&
            !verificationBusy &&
            !runtime.externalScanActive &&
            !settings.enableCmdSettings
    val analysisSupportingText =
        when {
            analysisBusy -> {
                val progress = runtime.activeRunProgress
                val activeStageIndex = progress.activeStageIndex
                val stageLabel = progress.stages.getOrNull(activeStageIndex ?: -1)?.stageLabel
                val stagePrefix =
                    if (activeStageIndex != null) {
                        "Stage ${activeStageIndex + 1} of ${progress.stages.size}"
                    } else {
                        null
                    }
                listOfNotNull(stagePrefix, runtime.activeRunStageProgress ?: stageLabel)
                    .joinToString(" · ")
                    .ifBlank { stringResolver.getString(R.string.home_diagnostics_analysis_running) }
            }

            verificationBusy -> {
                stringResolver.getString(R.string.home_diagnostics_busy_verifying)
            }

            runtime.externalScanActive -> {
                runtime.externalScanMessage
                    ?: stringResolver.getString(R.string.home_diagnostics_busy_other_scan)
            }

            settings.enableCmdSettings -> {
                stringResolver.getString(R.string.home_diagnostics_command_line_blocked)
            }

            else -> {
                stringResolver.getString(R.string.home_diagnostics_analysis_body)
            }
        }
    val verificationEnabled =
        !analysisBusy &&
            !verificationBusy &&
            !runtime.externalScanActive &&
            appStatus == AppStatus.Halted &&
            connectionState != ConnectionState.Connecting &&
            runtime.latestCompositeOutcome?.actionable == true &&
            !fingerprintMismatch
    val verificationSupportingText =
        when {
            verificationBusy -> {
                runtime.verificationProgress
                    ?: stringResolver.getString(R.string.home_diagnostics_verifying)
            }

            analysisBusy -> {
                stringResolver.getString(R.string.home_diagnostics_finish_analysis_first)
            }

            runtime.externalScanActive -> {
                runtime.externalScanMessage
                    ?: stringResolver.getString(R.string.home_diagnostics_busy_other_scan)
            }

            runtime.latestCompositeOutcome == null -> {
                stringResolver.getString(R.string.home_diagnostics_run_analysis_first)
            }

            runtime.latestCompositeOutcome.actionable.not() -> {
                stringResolver.getString(R.string.home_diagnostics_no_actionable_result)
            }

            fingerprintMismatch -> {
                stringResolver.getString(R.string.home_diagnostics_run_again)
            }

            appStatus == AppStatus.Running || connectionState == ConnectionState.Connected -> {
                stringResolver.getString(R.string.home_diagnostics_disconnect_first)
            }

            else -> {
                stringResolver.getString(R.string.home_diagnostics_verified_vpn_body)
            }
        }

    return HomeDiagnosticsUiState(
        analysisAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_run_analysis),
                supportingText = analysisSupportingText,
                enabled = analysisEnabled,
                busy = analysisBusy,
            ),
        verifiedVpnAction =
            HomeDiagnosticsActionUiState(
                label = stringResolver.getString(R.string.home_diagnostics_start_verified_vpn),
                supportingText = verificationSupportingText,
                enabled = verificationEnabled,
                busy = verificationBusy,
            ),
        latestAudit =
            runtime.latestCompositeOutcome?.let { outcome ->
                HomeDiagnosticsLatestAuditUiState(
                    headline = outcome.headline,
                    summary = outcome.summary,
                    recommendationSummary = outcome.recommendationSummary,
                    completedStageCount = outcome.completedStageCount,
                    failedStageCount = outcome.failedStageCount,
                    totalStageCount = outcome.stageSummaries.size,
                    stale = fingerprintMismatch,
                    actionable = outcome.actionable && !fingerprintMismatch,
                )
            },
        analysisProgress =
            runtime.activeRunProgress?.takeIf { analysisBusy }?.let { progress ->
                AnalysisProgressUiState(
                    stages =
                        progress.stages.map { stage ->
                            AnalysisStageUiState(
                                status =
                                    when (stage.status) {
                                        DiagnosticsHomeCompositeStageStatus.PENDING -> AnalysisStageStatus.PENDING

                                        DiagnosticsHomeCompositeStageStatus.RUNNING -> AnalysisStageStatus.RUNNING

                                        DiagnosticsHomeCompositeStageStatus.COMPLETED,
                                        DiagnosticsHomeCompositeStageStatus.SKIPPED,
                                        -> AnalysisStageStatus.COMPLETED

                                        DiagnosticsHomeCompositeStageStatus.FAILED,
                                        DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                                        -> AnalysisStageStatus.FAILED
                                    },
                            )
                        },
                    activeStageIndex = progress.activeStageIndex,
                )
            },
        analysisSheet =
            runtime.latestCompositeOutcome
                ?.takeIf { runtime.analysisSheetVisible }
                ?.let { outcome ->
                    HomeDiagnosticsAnalysisSheetUiState(
                        runId = outcome.runId,
                        headline = outcome.headline,
                        summary = outcome.summary,
                        confidenceSummary = outcome.confidenceSummary,
                        coverageSummary = outcome.coverageSummary,
                        recommendationSummary = outcome.recommendationSummary,
                        appliedSettings = outcome.appliedSettings,
                        stageSummaries =
                            outcome.stageSummaries.map { stage ->
                                HomeDiagnosticsStageUiState(
                                    label = stage.stageLabel,
                                    headline = stage.headline,
                                    summary = stage.summary,
                                    failed =
                                        stage.status == DiagnosticsHomeCompositeStageStatus.FAILED,
                                    skipped =
                                        stage.status == DiagnosticsHomeCompositeStageStatus.SKIPPED ||
                                            stage.status == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE,
                                    recommendationContributor = stage.recommendationContributor,
                                )
                            },
                        completedStageCount = outcome.completedStageCount,
                        failedStageCount = outcome.failedStageCount,
                        shareBusy = runtime.shareBusy,
                    )
                },
        verificationSheet =
            runtime.verificationSheet?.let { outcome ->
                HomeDiagnosticsVerificationSheetUiState(
                    sessionId = outcome.sessionId,
                    success = outcome.success,
                    headline = outcome.headline,
                    summary = outcome.summary,
                    detail = outcome.detail,
                )
            },
    )
}
