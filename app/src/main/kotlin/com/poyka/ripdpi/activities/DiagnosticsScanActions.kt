package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectionReason
import com.poyka.ripdpi.diagnostics.HiddenProbeConflictAction
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class DiagnosticsScanActions(
    private val mutations: DiagnosticsMutationRunner,
    private val scanLifecycle: MutableStateFlow<ScanLifecycleState>,
    private val appContext: Context,
    private val loadSessionDetail: suspend (sessionId: String, showSensitiveDetails: Boolean) -> Unit,
) {
    fun initialize() {
        mutations.launch {
            var prevProgress: com.poyka.ripdpi.diagnostics.ScanProgress? = null
            diagnosticsTimelineSource.activeScanProgress.collect { progress ->
                if (progress == null && prevProgress != null && scanLifecycle.value.scanStartedAt != null) {
                    emit(buildScanCompletionEffect(currentUiState().scan, appContext))
                    scanLifecycle.update {
                        it.copy(
                            scanStartedAt = null,
                            activeScanPathMode = null,
                            activeScanKind = null,
                            accumulatedProbes = persistentListOf(),
                        )
                    }
                } else if (progress == null) {
                    scanLifecycle.update {
                        it.copy(
                            scanStartedAt = null,
                            activeScanPathMode = null,
                            activeScanKind = null,
                            accumulatedProbes = persistentListOf(),
                        )
                    }
                }
                prevProgress = progress
            }
        }
        mutations.launch {
            diagnosticsTimelineSource.activeScanProgress.collect { progress ->
                val target = progress?.latestProbeTarget ?: return@collect
                val outcome = progress.latestProbeOutcome ?: return@collect
                val existing = scanLifecycle.value.accumulatedProbes
                if (existing.lastOrNull()?.target == target &&
                    existing.lastOrNull()?.outcome == outcome
                ) {
                    return@collect
                }
                scanLifecycle.update {
                    it.copy(
                        accumulatedProbes =
                            (
                                it.accumulatedProbes +
                                    uiStateFactory.toCompletedProbeUiModel(
                                        phase = progress.phase,
                                        target = target,
                                        outcome = outcome,
                                        pathMode = it.activeScanPathMode ?: ScanPathMode.RAW_PATH,
                                        scanKind = it.activeScanKind ?: ScanKind.CONNECTIVITY,
                                    )
                            ).toImmutableList(),
                    )
                }
            }
        }
        mutations.launch {
            val pendingAuditSessionId =
                scanLifecycle
                    .map { state -> state.pendingAutoOpenAuditSessionId }
                    .distinctUntilChanged()
            combine(
                diagnosticsTimelineSource.sessions,
                diagnosticsTimelineSource.activeScanProgress,
                pendingAuditSessionId,
            ) { sessions, progress, pendingSessionId ->
                Triple(sessions, progress, pendingSessionId)
            }.collect { (sessions, progress, pendingSessionId) ->
                if (pendingSessionId == null || progress != null) {
                    return@collect
                }
                val session =
                    sessions.firstOrNull { it.id == pendingSessionId && it.report != null }
                        ?: return@collect
                loadSessionDetail(session.id, false)
                scanLifecycle.update { state ->
                    if (state.pendingAutoOpenAuditSessionId == pendingSessionId) {
                        state.copy(pendingAutoOpenAuditSessionId = null)
                    } else {
                        state
                    }
                }
            }
        }
        mutations.launch {
            var hadHiddenProbe = diagnosticsScanController.hiddenAutomaticProbeActive.value
            diagnosticsScanController.hiddenAutomaticProbeActive.collect { hiddenProbeActive ->
                val queuedRequest = scanLifecycle.value.queuedManualScanRequest
                if (!hiddenProbeActive && hadHiddenProbe && queuedRequest != null) {
                    scanLifecycle.update { state ->
                        if (state.queuedManualScanRequest?.requestId == queuedRequest.requestId) {
                            state.copy(queuedManualScanRequest = null)
                        } else {
                            state
                        }
                    }
                    resolveHiddenProbeConflict(
                        request = queuedRequest,
                        action = HiddenProbeConflictAction.WAIT,
                    )
                }
                hadHiddenProbe = hiddenProbeActive
            }
        }
    }

    fun startRawScan() {
        startManualScan(
            pathMode = ScanPathMode.RAW_PATH,
            selectedProfile = mutations.currentUiState().scan.selectedProfile,
        )
    }

    fun startInPathScan() {
        startManualScan(
            pathMode = ScanPathMode.IN_PATH,
            selectedProfile = mutations.currentUiState().scan.selectedProfile,
        )
    }

    fun waitForHiddenProbeAndRun() {
        val dialogState = scanLifecycle.value.hiddenProbeConflictDialog ?: return
        val queuedRequest = dialogState.toQueuedManualScanRequest()
        mutations.launch {
            scanLifecycle.update {
                it.copy(hiddenProbeConflictDialog = null)
            }
            if (diagnosticsScanController.hiddenAutomaticProbeActive.value) {
                scanLifecycle.update {
                    it.copy(queuedManualScanRequest = queuedRequest)
                }
                emit(
                    DiagnosticsEffect.ScanQueued(
                        appContext.getString(
                            R.string.diagnostics_hidden_probe_wait_queued_format,
                            queuedRequest.profileName,
                        ),
                    ),
                )
            } else {
                resolveHiddenProbeConflict(
                    request = queuedRequest,
                    action = HiddenProbeConflictAction.WAIT,
                )
            }
        }
    }

    fun cancelHiddenProbeAndRun() {
        val dialogState = scanLifecycle.value.hiddenProbeConflictDialog ?: return
        mutations.launch {
            scanLifecycle.update {
                it.copy(
                    hiddenProbeConflictDialog = null,
                    queuedManualScanRequest = null,
                )
            }
            resolveHiddenProbeConflict(
                request = dialogState.toQueuedManualScanRequest(),
                action = HiddenProbeConflictAction.CANCEL_AND_RUN,
            )
        }
    }

    fun dismissHiddenProbeConflictDialog() {
        scanLifecycle.update {
            it.copy(hiddenProbeConflictDialog = null)
        }
    }

    fun cancelScan() {
        scanLifecycle.update {
            it.copy(
                scanStartedAt = null,
                activeScanPathMode = null,
                activeScanKind = null,
                accumulatedProbes = persistentListOf(),
            )
        }
        mutations.launch {
            scanLifecycle.update { it.copy(pendingAutoOpenAuditSessionId = null) }
            diagnosticsScanController.cancelActiveScan()
        }
    }

    fun keepResolverRecommendationForSession(sessionId: String?) {
        val targetSessionId =
            sessionId ?: mutations
                .currentUiState()
                .scan.latestSession
                ?.id ?: return
        mutations.launch {
            diagnosticsResolverActions.keepResolverRecommendationForSession(targetSessionId)
        }
    }

    fun saveResolverRecommendation(sessionId: String?) {
        val targetSessionId =
            sessionId ?: mutations
                .currentUiState()
                .scan.latestSession
                ?.id ?: return
        mutations.launch {
            diagnosticsResolverActions.saveResolverRecommendation(targetSessionId)
            emit(
                DiagnosticsEffect.ScanCompleted(
                    summary = appContext.getString(R.string.diagnostics_snackbar_dns_setting_saved),
                    tone = DiagnosticsTone.Positive,
                    actionLabel = appContext.getString(R.string.title_dns_settings),
                    action = DiagnosticsEffect.SnackbarAction.OpenDnsSettings,
                ),
            )
        }
    }

    private fun startManualScan(
        pathMode: ScanPathMode,
        selectedProfile: DiagnosticsProfileOptionUiModel?,
    ) {
        val request =
            ManualScanUiRequest(
                profileName = selectedProfile?.name ?: "Scan",
                pathMode = pathMode,
                scanKind = selectedProfile?.kind ?: ScanKind.CONNECTIVITY,
                isFullAudit = selectedProfile?.isFullAudit == true,
            )
        mutations.launch {
            try {
                selectedProfile?.id?.let { diagnosticsScanController.setActiveProfile(it) }
                when (val result = diagnosticsScanController.startScan(pathMode, selectedProfile?.id)) {
                    is DiagnosticsManualScanStartResult.Started -> {
                        handleStartedScan(
                            sessionId = result.sessionId,
                            request = request,
                        )
                    }

                    is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution -> {
                        scanLifecycle.update {
                            it.copy(
                                scanStartedAt = null,
                                activeScanPathMode = null,
                                activeScanKind = null,
                                pendingAutoOpenAuditSessionId = null,
                                accumulatedProbes = persistentListOf(),
                                hiddenProbeConflictDialog =
                                    HiddenProbeConflictDialogState(
                                        requestId = result.requestId,
                                        profileName = result.profileName,
                                        pathMode = result.pathMode,
                                        scanKind = result.scanKind,
                                        isFullAudit = result.isFullAudit,
                                    ),
                                queuedManualScanRequest = null,
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                handleStartFailure(error)
            }
        }
    }

    private suspend fun DiagnosticsMutationRunner.handleStartFailure(error: Throwable) {
        scanLifecycle.update {
            it.copy(
                scanStartedAt = null,
                activeScanPathMode = null,
                activeScanKind = null,
                pendingAutoOpenAuditSessionId = null,
                accumulatedProbes = persistentListOf(),
                hiddenProbeConflictDialog = null,
                queuedManualScanRequest = null,
            )
        }
        emit(
            DiagnosticsEffect.ScanStartFailed(
                message =
                    when ((error as? DiagnosticsScanStartRejectedException)?.reason) {
                        DiagnosticsScanStartRejectionReason.HiddenAutomaticProbeRunning -> {
                            appContext.getString(R.string.diagnostics_error_hidden_probe_running)
                        }

                        else -> {
                            appContext.getString(R.string.diagnostics_error_start_failed)
                        }
                    },
            ),
        )
    }

    private suspend fun DiagnosticsMutationRunner.resolveHiddenProbeConflict(
        request: QueuedManualScanRequest,
        action: HiddenProbeConflictAction,
    ) {
        try {
            when (val resolution = diagnosticsScanController.resolveHiddenProbeConflict(request.requestId, action)) {
                is DiagnosticsManualScanResolution.Started -> {
                    handleStartedScan(
                        sessionId = resolution.sessionId,
                        request = request.toManualScanUiRequest(),
                    )
                }

                is DiagnosticsManualScanResolution.Failed -> {
                    scanLifecycle.update {
                        it.copy(
                            scanStartedAt = null,
                            activeScanPathMode = null,
                            activeScanKind = null,
                            pendingAutoOpenAuditSessionId = null,
                            accumulatedProbes = persistentListOf(),
                            queuedManualScanRequest = null,
                        )
                    }
                    emit(
                        DiagnosticsEffect.ScanStartFailed(
                            appContext.getString(R.string.diagnostics_hidden_probe_takeover_failed),
                        ),
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            scanLifecycle.update {
                it.copy(
                    scanStartedAt = null,
                    activeScanPathMode = null,
                    activeScanKind = null,
                    pendingAutoOpenAuditSessionId = null,
                    accumulatedProbes = persistentListOf(),
                    queuedManualScanRequest = null,
                )
            }
            emit(
                DiagnosticsEffect.ScanStartFailed(
                    appContext.getString(R.string.diagnostics_hidden_probe_takeover_failed),
                ),
            )
        }
    }

    private suspend fun DiagnosticsMutationRunner.handleStartedScan(
        sessionId: String,
        request: ManualScanUiRequest,
    ) {
        scanLifecycle.update {
            it.copy(
                scanStartedAt = System.currentTimeMillis(),
                activeScanPathMode = request.pathMode,
                activeScanKind = request.scanKind,
                pendingAutoOpenAuditSessionId = if (request.isFullAudit) sessionId else null,
                hiddenProbeConflictDialog = null,
                queuedManualScanRequest = null,
            )
        }
        emit(DiagnosticsEffect.ScanStarted(scanTypeLabel = request.profileName))
    }
}

private data class ManualScanUiRequest(
    val profileName: String,
    val pathMode: ScanPathMode,
    val scanKind: ScanKind,
    val isFullAudit: Boolean,
)

private fun HiddenProbeConflictDialogState.toQueuedManualScanRequest(): QueuedManualScanRequest =
    QueuedManualScanRequest(
        requestId = requestId,
        profileName = profileName,
        pathMode = pathMode,
        scanKind = scanKind,
        isFullAudit = isFullAudit,
    )

private fun QueuedManualScanRequest.toManualScanUiRequest(): ManualScanUiRequest =
    ManualScanUiRequest(
        profileName = profileName,
        pathMode = pathMode,
        scanKind = scanKind,
        isFullAudit = isFullAudit,
    )

internal fun buildScanCompletionEffect(
    scan: DiagnosticsScanUiModel,
    context: Context,
): DiagnosticsEffect.ScanCompleted {
    val latestSummary = scan.latestSession?.summary ?: context.getString(R.string.diagnostics_snackbar_scan_complete)
    val resolverMessage =
        when {
            scan.resolverRecommendation != null -> {
                context.getString(
                    R.string.diagnostics_snackbar_dns_recommendation_format,
                    scan.resolverRecommendation.headline,
                )
            }

            latestSummary.contains("resolver override recommended", ignoreCase = true) -> {
                context.getString(R.string.diagnostics_snackbar_dns_recommendation_generic)
            }

            else -> {
                null
            }
        }
    return DiagnosticsEffect.ScanCompleted(
        summary = resolverMessage ?: latestSummary,
        tone = if (resolverMessage != null) DiagnosticsTone.Warning else scanCompletedTone(scan.latestSession),
        actionLabel = scan.resolverRecommendation?.let { context.getString(R.string.title_dns_settings) },
        action = scan.resolverRecommendation?.let { DiagnosticsEffect.SnackbarAction.OpenDnsSettings },
    )
}
