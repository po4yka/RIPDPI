package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.LatestDirectModeOutcomeStore
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeProgress
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeRunOptions
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeVerificationOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeState
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.CancellationException
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
    val latestManualDiagnosticSession: DiagnosticScanSession? = null,
    val externalScanActive: Boolean = false,
    val externalScanMessage: String? = null,
    val pcapRecordingRequested: Boolean = false,
    val analysisStarting: Boolean = false,
    val analysisStartFailed: Boolean = false,
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
    private val pcapCaptureRuntimeController: PcapCaptureRuntimeController? = null,
) {
    private var activeRunObservation: Job? = null

    fun initialize() {
        mutations.launch { refreshFingerprint() }
        observeActiveScanProgress()
        observeLatestManualDiagnosticSession()
        observeVerificationSessions()
        observeVerifiedVpnConnection()
        observeBlockingPermissionWhileWaiting()
        observeServiceStatusForFingerprint()
        observePcapCaptureState()
    }

    private fun observeLatestManualDiagnosticSession() {
        mutations.launch {
            diagnosticsTimelineSource.sessions.collect { sessions ->
                val latestManualSession = sessions.latestCompletedManualDiagnosticSession()
                homeDiagnosticsState.update { current ->
                    if (current.latestManualDiagnosticSession == latestManualSession) {
                        current
                    } else {
                        current.copy(latestManualDiagnosticSession = latestManualSession)
                    }
                }
            }
        }
    }

    private fun observeActiveScanProgress() {
        mutations.launch {
            diagnosticsTimelineSource.activeScanProgress.collect { progress ->
                homeDiagnosticsState.update { current -> current.withActiveScanProgress(progress) }
            }
        }
    }

    private fun observeVerificationSessions() {
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
                                        headline =
                                            stringResolver.getString(
                                                R.string.home_diagnostics_verification_incomplete,
                                            ),
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
    }

    private fun observeVerifiedVpnConnection() {
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
                                            headline =
                                                stringResolver.getString(
                                                    R.string.home_diagnostics_vpn_failed_to_start,
                                                ),
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
    }

    private fun observeBlockingPermissionWhileWaiting() {
        mutations.launch {
            permissionState
                .map { it.issue }
                .distinctUntilChanged()
                .collect { issue ->
                    if (
                        homeDiagnosticsState.value.waitingForVerifiedVpnStart &&
                        issue?.blocking == true
                    ) {
                        homeDiagnosticsState.update {
                            it.copy(
                                waitingForVerifiedVpnStart = false,
                                verificationProgress = null,
                                verificationSheet = blockedPermissionVerificationOutcome(issue, stringResolver),
                            )
                        }
                    }
                }
        }
    }

    private fun observeServiceStatusForFingerprint() {
        mutations.launch {
            serviceStateStore.status.collect {
                refreshFingerprint()
            }
        }
    }

    fun runFullAnalysis() {
        mutations.launch {
            if (homeDiagnosticsState.value.analysisInProgress()) return@launch
            activeRunObservation?.cancel()
            homeDiagnosticsState.update {
                it.copy(
                    activeRunId = null,
                    activeRunProgress = null,
                    activeRunStageProgress = null,
                    activeStageStepProgress = 0f,
                    quickScanActive = false,
                    latestCompositeOutcome = null,
                    analysisSheetVisible = false,
                    verificationSheet = null,
                    activeVerificationSessionId = null,
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                    analysisStartFailed = false,
                    analysisStarting = true,
                )
            }
            runCatching {
                diagnosticsHomeCompositeRunService.startHomeAnalysis(
                    DiagnosticsHomeRunOptions(),
                )
            }.onSuccess { started ->
                homeDiagnosticsState.update {
                    it.copy(
                        activeRunId = started.runId,
                        activeRunStageProgress = stringResolver.getString(R.string.home_diagnostics_analysis_running),
                        analysisStarting = false,
                    )
                }
                activeRunObservation =
                    mutations.launch {
                        diagnosticsHomeCompositeRunService.observeHomeRun(started.runId).collect { progress ->
                            homeDiagnosticsState.update { current ->
                                current.withCompositeProgress(progress)
                            }
                            progress.outcome?.let { outcome ->
                                refreshFingerprint(outcome.fingerprintHash)
                                publishLatestDirectModeOutcome(outcome)
                            }
                        }
                    }
            }.onFailure { error ->
                homeDiagnosticsState.update { it.copy(analysisStarting = false, analysisStartFailed = true) }
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

    fun cancelAnalysis() {
        mutations.launch {
            val runId = homeDiagnosticsState.value.activeRunId ?: return@launch
            runCatching { diagnosticsHomeCompositeRunService.cancelHomeRun(runId) }
                .onFailure {
                    mutations.emit(
                        MainEffect.ShowError(
                            stringResolver.getString(R.string.diagnostics_error_cancel_failed),
                        ),
                    )
                }
        }
    }

    fun runQuickAnalysis() {
        mutations.launch {
            if (homeDiagnosticsState.value.analysisInProgress()) return@launch
            activeRunObservation?.cancel()
            homeDiagnosticsState.update {
                it.copy(
                    activeRunId = null,
                    activeRunProgress = null,
                    activeRunStageProgress = null,
                    activeStageStepProgress = 0f,
                    quickScanActive = true,
                    latestCompositeOutcome = null,
                    analysisSheetVisible = false,
                    verificationSheet = null,
                    activeVerificationSessionId = null,
                    waitingForVerifiedVpnStart = false,
                    verificationProgress = null,
                    analysisStartFailed = false,
                    analysisStarting = true,
                )
            }
            runCatching {
                diagnosticsHomeCompositeRunService.startQuickAnalysis(
                    DiagnosticsHomeRunOptions(),
                )
            }.onSuccess { started ->
                homeDiagnosticsState.update {
                    it.copy(
                        activeRunId = started.runId,
                        activeRunStageProgress = stringResolver.getString(R.string.home_diagnostics_analysis_running),
                        analysisStarting = false,
                    )
                }
                activeRunObservation =
                    mutations.launch {
                        diagnosticsHomeCompositeRunService.observeHomeRun(started.runId).collect { progress ->
                            homeDiagnosticsState.update { current ->
                                current.withCompositeProgress(progress)
                            }
                            progress.outcome?.let { outcome ->
                                refreshFingerprint(outcome.fingerprintHash)
                                publishLatestDirectModeOutcome(outcome)
                            }
                        }
                    }
            }.onFailure { error ->
                homeDiagnosticsState.update { it.copy(analysisStarting = false, analysisStartFailed = true) }
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
        val controller = pcapCaptureRuntimeController ?: return
        mutations.launch {
            if (controller.state.value is PcapCaptureRuntimeState.Recording) {
                controller.stop()
            } else {
                controller.start()
            }
        }
    }

    private fun observePcapCaptureState() {
        val controller = pcapCaptureRuntimeController ?: return
        mutations.launch {
            controller.state.collect { state ->
                homeDiagnosticsState.update { current ->
                    current.copy(pcapRecordingRequested = state is PcapCaptureRuntimeState.Recording)
                }
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
                if (error is CancellationException) throw error
                Logger.withTag(LogTags.DIAGNOSTICS).e(error) {
                    "Failed to create home analysis archive"
                }
                homeDiagnosticsState.update { it.copy(shareBusy = false) }
                mutations.emit(
                    MainEffect.ShowError(
                        stringResolver.getString(R.string.home_diagnostics_share_failed),
                        supportCode =
                            (error as? DiagnosticsArchiveException)
                                ?.failureCode
                                ?.supportCode
                                ?: ArchiveIoSupportCode,
                    ),
                )
            }
        }
    }

    val saveLatestHomeAnalysis: () -> Unit = {
        mutations.launch {
            val outcome = homeDiagnosticsState.value.latestCompositeOutcome ?: return@launch
            mutations.emit(
                MainEffect.SaveDiagnosticsArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = null,
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SAVE_ARCHIVE,
                        requestedAt = System.currentTimeMillis(),
                    ),
                ),
            )
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
                                    headline = stringResolver.getString(R.string.home_diagnostics_verification_busy),
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
                            headline = stringResolver.getString(R.string.home_diagnostics_verification_could_not_start),
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
    // suggestion on the same evidence the Diagnostics ladder uses. Verdicts without a direct-mode result clear the
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

private const val ArchiveIoSupportCode = "archive_io"

private fun blockedPermissionVerificationOutcome(
    issue: PermissionIssueUiState,
    stringResolver: StringResolver,
): DiagnosticsHomeVerificationOutcome =
    if (issue.kind == PermissionKind.VpnConsent) {
        DiagnosticsHomeVerificationOutcome(
            sessionId = "",
            success = false,
            headline = stringResolver.getString(R.string.home_diagnostics_vpn_permission_required_headline),
            summary = stringResolver.getString(R.string.home_diagnostics_permission_required),
        )
    } else {
        DiagnosticsHomeVerificationOutcome(
            sessionId = "",
            success = false,
            headline = issue.title,
            summary = issue.message,
        )
    }

private fun HomeDiagnosticsRuntimeState.analysisInProgress(): Boolean =
    analysisStarting || activeRunId != null ||
        activeRunProgress?.status == DiagnosticsHomeCompositeRunStatus.RUNNING

private data class ActiveRunProgressSelection(
    val progress: DiagnosticsHomeCompositeProgress?,
    val ownsSession: Boolean,
)

private fun HomeDiagnosticsRuntimeState.selectActiveRunProgress(sessionId: String?): ActiveRunProgressSelection {
    val matchingStageIndex =
        activeRunProgress
            ?.stages
            ?.indexOfFirst { stage ->
                stage.sessionId == sessionId && stage.status == DiagnosticsHomeCompositeStageStatus.RUNNING
            }?.takeIf { it >= 0 }
    val selectedProgress =
        matchingStageIndex?.let { stageIndex ->
            activeRunProgress.copy(activeStageIndex = stageIndex, activeSessionId = sessionId)
        } ?: activeRunProgress
    return ActiveRunProgressSelection(
        progress = selectedProgress,
        ownsSession = matchingStageIndex != null || sessionId == selectedProgress?.activeSessionId,
    )
}

private fun HomeDiagnosticsRuntimeState.withActiveScanProgress(progress: ScanProgress?): HomeDiagnosticsRuntimeState {
    val selection = selectActiveRunProgress(progress?.sessionId)
    val verificationOwnsSession = progress?.sessionId == activeVerificationSessionId
    val external = progress != null && !selection.ownsSession && !verificationOwnsSession
    return copy(
        activeRunProgress = selection.progress,
        activeRunStageProgress =
            when {
                selection.ownsSession -> progress?.message
                progress == null && activeRunId == null -> null
                else -> activeRunStageProgress
            },
        activeStageStepProgress = nextActiveStageStepProgress(progress, selection.ownsSession),
        verificationProgress =
            when {
                verificationOwnsSession -> progress?.message
                waitingForVerifiedVpnStart -> verificationProgress
                else -> null
            },
        externalScanActive = external,
        externalScanMessage = progress?.message?.takeIf { external },
    )
}

private fun HomeDiagnosticsRuntimeState.nextActiveStageStepProgress(
    progress: ScanProgress?,
    ownsSession: Boolean,
): Float =
    when {
        ownsSession && progress != null && progress.totalSteps > 0 -> {
            progress.completedSteps.toFloat() / progress.totalSteps
        }

        ownsSession || progress == null -> {
            0f
        }

        else -> {
            activeStageStepProgress
        }
    }

private fun HomeDiagnosticsRuntimeState.withCompositeProgress(
    progress: DiagnosticsHomeCompositeProgress,
): HomeDiagnosticsRuntimeState {
    val running = progress.status == DiagnosticsHomeCompositeRunStatus.RUNNING
    val activeSessionChanged = activeRunProgress?.activeSessionId != progress.activeSessionId
    return copy(
        activeRunId = if (running) progress.runId else null,
        quickScanActive = running && quickScanActive,
        activeRunProgress = progress,
        activeRunStageProgress = if (activeSessionChanged) null else activeRunStageProgress,
        activeStageStepProgress = if (activeSessionChanged) 0f else activeStageStepProgress,
        latestCompositeOutcome = progress.outcome ?: latestCompositeOutcome,
        analysisSheetVisible = progress.outcome != null || analysisSheetVisible,
        analysisStartFailed = false,
    )
}

private fun List<DiagnosticScanSession>.latestCompletedManualDiagnosticSession(): DiagnosticScanSession? =
    filter { session ->
        session.launchOrigin == DiagnosticsScanLaunchOrigin.USER_INITIATED &&
            session.finishedAt != null &&
            session.status.equals("completed", ignoreCase = true)
    }.maxByOrNull { session -> session.finishedAt ?: session.startedAt }
