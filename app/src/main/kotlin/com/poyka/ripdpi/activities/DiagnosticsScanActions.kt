package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectedException
import com.poyka.ripdpi.diagnostics.DiagnosticsScanStartRejectionReason
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
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
                    val latestSession = currentUiState().scan.latestSession
                    emit(
                        DiagnosticsEffect.ScanCompleted(
                            summary = latestSession?.summary ?: "Scan complete",
                            tone = scanCompletedTone(latestSession),
                        ),
                    )
                    scanLifecycle.update {
                        it.copy(
                            scanStartedAt = null,
                            activeScanPathMode = null,
                            activeScanKind = null,
                            accumulatedProbes = emptyList(),
                        )
                    }
                } else if (progress == null) {
                    scanLifecycle.update {
                        it.copy(
                            scanStartedAt = null,
                            activeScanPathMode = null,
                            activeScanKind = null,
                            accumulatedProbes = emptyList(),
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
                            it.accumulatedProbes +
                                uiStateFactory.toCompletedProbeUiModel(
                                    phase = progress.phase,
                                    target = target,
                                    outcome = outcome,
                                    pathMode = it.activeScanPathMode ?: ScanPathMode.RAW_PATH,
                                    scanKind = it.activeScanKind ?: ScanKind.CONNECTIVITY,
                                ),
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
    }

    fun startRawScan() {
        val selectedProfile = mutations.currentUiState().scan.selectedProfile
        val scanKind = selectedProfile?.kind ?: ScanKind.CONNECTIVITY
        val profileName = selectedProfile?.name ?: "Scan"
        val isFullAudit = selectedProfile?.isFullAudit == true
        mutations.launch {
            try {
                val sessionId = diagnosticsScanController.startScan(ScanPathMode.RAW_PATH)
                scanLifecycle.update {
                    it.copy(
                        scanStartedAt = System.currentTimeMillis(),
                        activeScanPathMode = ScanPathMode.RAW_PATH,
                        activeScanKind = scanKind,
                        pendingAutoOpenAuditSessionId = if (isFullAudit) sessionId else null,
                    )
                }
                emit(DiagnosticsEffect.ScanStarted(scanTypeLabel = profileName))
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                handleStartFailure(error)
            }
        }
    }

    fun startInPathScan() {
        val selectedProfile = mutations.currentUiState().scan.selectedProfile
        val scanKind = selectedProfile?.kind ?: ScanKind.CONNECTIVITY
        val profileName = selectedProfile?.name ?: "Scan"
        mutations.launch {
            try {
                diagnosticsScanController.startScan(ScanPathMode.IN_PATH)
                scanLifecycle.update {
                    it.copy(
                        scanStartedAt = System.currentTimeMillis(),
                        activeScanPathMode = ScanPathMode.IN_PATH,
                        activeScanKind = scanKind,
                        pendingAutoOpenAuditSessionId = null,
                    )
                }
                emit(DiagnosticsEffect.ScanStarted(scanTypeLabel = profileName))
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                handleStartFailure(error)
            }
        }
    }

    fun cancelScan() {
        scanLifecycle.update {
            it.copy(
                scanStartedAt = null,
                activeScanPathMode = null,
                activeScanKind = null,
                accumulatedProbes = emptyList(),
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
        }
    }

    private suspend fun DiagnosticsMutationRunner.handleStartFailure(error: Throwable) {
        scanLifecycle.update {
            it.copy(
                scanStartedAt = null,
                activeScanPathMode = null,
                activeScanKind = null,
                pendingAutoOpenAuditSessionId = null,
                accumulatedProbes = emptyList(),
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
}
