package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ScanPathMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

internal class DiagnosticsScanActions(
    private val mutations: DiagnosticsMutationRunner,
    private val scanLifecycle: MutableStateFlow<ScanLifecycleState>,
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
                    scanLifecycle.update { it.copy(scanStartedAt = null, accumulatedProbes = emptyList()) }
                } else if (progress == null) {
                    scanLifecycle.update { it.copy(scanStartedAt = null, accumulatedProbes = emptyList()) }
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
                                uiStateFactory.toCompletedProbeUiModel(target, outcome),
                    )
                }
            }
        }
        mutations.launch {
            combine(
                diagnosticsTimelineSource.sessions,
                diagnosticsTimelineSource.activeScanProgress,
            ) { sessions, progress ->
                Pair(sessions, progress)
            }.collect { (sessions, progress) ->
                val pendingSessionId = scanLifecycle.value.pendingAutoOpenAuditSessionId
                if (pendingSessionId == null || progress != null) {
                    return@collect
                }
                val session =
                    sessions.firstOrNull { it.id == pendingSessionId && it.reportJson != null }
                        ?: return@collect
                loadSessionDetail(session.id, false)
                scanLifecycle.update { it.copy(pendingAutoOpenAuditSessionId = null) }
            }
        }
    }

    fun startRawScan() {
        scanLifecycle.update { it.copy(scanStartedAt = System.currentTimeMillis()) }
        mutations.launch {
            val profileName = currentUiState().scan.selectedProfile?.name ?: "Scan"
            emit(DiagnosticsEffect.ScanStarted(scanTypeLabel = profileName))
            val sessionId = diagnosticsScanController.startScan(ScanPathMode.RAW_PATH)
            if (currentUiState().scan.selectedProfile?.isFullAudit == true) {
                scanLifecycle.update { it.copy(pendingAutoOpenAuditSessionId = sessionId) }
            }
        }
    }

    fun startInPathScan() {
        scanLifecycle.update { it.copy(scanStartedAt = System.currentTimeMillis()) }
        mutations.launch {
            val profileName = currentUiState().scan.selectedProfile?.name ?: "Scan"
            emit(DiagnosticsEffect.ScanStarted(scanTypeLabel = profileName))
            diagnosticsScanController.startScan(ScanPathMode.IN_PATH)
        }
    }

    fun cancelScan() {
        scanLifecycle.update { it.copy(scanStartedAt = null) }
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
}
