package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.LatestDirectModeOutcomeStore
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeProgress
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeRunOptions
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
    val quickScanActive: Boolean = false,
    val activeStageStepProgress: Float = 0f,
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
    val pcapRecordingRequested: Boolean = false,
)

internal class MainHomeDiagnosticsActions(
    private val mutations: MainMutationRunner,
    private val diagnosticsTimelineSource: DiagnosticsTimelineSource,
    private val diagnosticsScanController: DiagnosticsScanController,
    private val diagnosticsShareService: DiagnosticsShareService,
    private val diagnosticsHomeWorkflowService: DiagnosticsHomeWorkflowService,
    private val diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService,
    private val serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
    private val latestDirectModeOutcomeStore: LatestDirectModeOutcomeStore,
    private val runtimeState: MutableStateFlow<ConnectionRuntimeState>,
    private val permissionState: MutableStateFlow<PermissionRuntimeState>,
    private val homeDiagnosticsState: MutableStateFlow<HomeDiagnosticsRuntimeState>,
    private val stringResolver: StringResolver,
    private val requestVpnStart: () -> Unit,
) {
    private var activeRunObservation: Job? = null

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun initialize() {
        mutations.launch {
            refreshFingerprint()
        }
        mutations.launch {
            diagnosticsTimelineSource.activeScanProgress.collect { progress ->
                val progressSessionId = progress?.sessionId
                val progressMessage = progress?.message
                val isActiveRunSession =
                    progressSessionId == homeDiagnosticsState.value.activeRunProgress?.activeSessionId
                homeDiagnosticsState.update { current ->
                    current.copy(
                        activeRunStageProgress =
                            if (progressSessionId == current.activeRunProgress?.activeSessionId) {
                                progressMessage
                            } else {
                                current.activeRunStageProgress
                            },
                        activeStageStepProgress =
                            if (isActiveRunSession && progress != null && progress.totalSteps > 0) {
                                progress.completedSteps.toFloat() / progress.totalSteps
                            } else if (!isActiveRunSession) {
                                current.activeStageStepProgress
                            } else {
                                0f
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
            val pcapRequested = homeDiagnosticsState.value.pcapRecordingRequested
            homeDiagnosticsState.update {
                it.copy(
                    activeRunId = null,
                    activeRunProgress = null,
                    activeRunStageProgress = null,
                    quickScanActive = false,
                    latestCompositeOutcome = null,
                    analysisSheetVisible = false,
                    verificationSheet = null,
                    activeVerificationSessionId = null,
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                )
            }
            runCatching {
                diagnosticsHomeCompositeRunService.startHomeAnalysis(
                    DiagnosticsHomeRunOptions(pcapRecordingRequested = pcapRequested),
                )
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
                            val running = progress.status == DiagnosticsHomeCompositeRunStatus.RUNNING
                            homeDiagnosticsState.update { current ->
                                current.copy(
                                    activeRunId = if (running) progress.runId else null,
                                    quickScanActive = if (running) current.quickScanActive else false,
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
                                publishLatestDirectModeOutcome(outcome)
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

    fun runQuickAnalysis() {
        mutations.launch {
            activeRunObservation?.cancel()
            val pcapRequested = homeDiagnosticsState.value.pcapRecordingRequested
            homeDiagnosticsState.update {
                it.copy(
                    activeRunId = null,
                    activeRunProgress = null,
                    activeRunStageProgress = null,
                    quickScanActive = true,
                    latestCompositeOutcome = null,
                    analysisSheetVisible = false,
                    verificationSheet = null,
                    activeVerificationSessionId = null,
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                )
            }
            runCatching {
                diagnosticsHomeCompositeRunService.startQuickAnalysis(
                    DiagnosticsHomeRunOptions(pcapRecordingRequested = pcapRequested),
                )
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
                            val running = progress.status == DiagnosticsHomeCompositeRunStatus.RUNNING
                            homeDiagnosticsState.update { current ->
                                current.copy(
                                    activeRunId = if (running) progress.runId else null,
                                    quickScanActive = if (running) current.quickScanActive else false,
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
                                publishLatestDirectModeOutcome(outcome)
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

    fun togglePcapRecording() {
        homeDiagnosticsState.update { current ->
            current.copy(pcapRecordingRequested = !current.pcapRecordingRequested)
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

    // Hand off the latest direct-mode verdict to the singleton store so other
    // ViewModels (notably ConfigViewModel) can ground their relay-preset
    // suggestion on the same evidence the Diagnostics ladder uses
    // (ADR-010 P4.3.4). Verdicts without a direct-mode result clear the
    // store so a stale entry from a previous run never leaks into the
    // Config surface.
    private fun publishLatestDirectModeOutcome(outcome: DiagnosticsHomeCompositeOutcome) {
        val verdict = outcome.directModeVerdict
        val snapshot =
            if (verdict == null) {
                null
            } else {
                LatestDirectModeOutcomeSnapshot(
                    result = verdict.result,
                    reasonCode = verdict.reasonCode,
                    transportClass = verdict.transportClass,
                    recordedAt = System.currentTimeMillis(),
                )
            }
        latestDirectModeOutcomeStore.publish(snapshot)
    }
}
