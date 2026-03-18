package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class DiagnosticsShareActions(
    private val mutations: DiagnosticsMutationRunner,
    private val scanLifecycle: MutableStateFlow<ScanLifecycleState>,
) {
    fun shareSummary(sessionId: String?) {
        mutations.launch {
            val targetId = sessionId ?: currentUiState().share.targetSessionId
            val summary = diagnosticsManager.buildShareSummary(targetId)
            emit(
                DiagnosticsEffect.ShareSummaryRequested(
                    title = summary.title,
                    body = summary.body,
                ),
            )
        }
    }

    fun shareArchive(sessionId: String?) {
        mutations.launch {
            runArchiveAction(
                busyMessage = "Generating archive for sharing",
                successMessage = "Archive ready to share",
                failureMessage = "Failed to generate archive",
            ) { targetSessionId ->
                val archive = diagnosticsManager.createArchive(targetSessionId)
                emit(
                    DiagnosticsEffect.ShareArchiveRequested(
                        absolutePath = archive.absolutePath,
                        fileName = archive.fileName,
                    ),
                )
                archive
            }
        }
    }

    fun saveArchive(sessionId: String?) {
        mutations.launch {
            runArchiveAction(
                busyMessage = "Preparing archive for saving",
                successMessage = "Archive saved to export flow",
                failureMessage = "Failed to prepare archive",
            ) { targetSessionId ->
                val archive = diagnosticsManager.createArchive(targetSessionId)
                emit(
                    DiagnosticsEffect.SaveArchiveRequested(
                        absolutePath = archive.absolutePath,
                        fileName = archive.fileName,
                    ),
                )
                archive
            }
        }
    }

    private suspend fun DiagnosticsMutationRunner.runArchiveAction(
        busyMessage: String,
        successMessage: String,
        failureMessage: String,
        action: suspend DiagnosticsMutationRunner.(String?) -> DiagnosticsArchive,
    ) {
        val targetSessionId = currentUiState().share.targetSessionId
        scanLifecycle.update {
            it.copy(
                archiveActionState =
                    ArchiveActionState(
                        message = busyMessage,
                        tone = DiagnosticsTone.Info,
                        isBusy = true,
                        latestArchiveFileName = it.archiveActionState.latestArchiveFileName,
                    ),
            )
        }
        runCatching { action(targetSessionId) }
            .onSuccess { archive ->
                scanLifecycle.update {
                    it.copy(
                        archiveActionState =
                            ArchiveActionState(
                                message = successMessage,
                                tone = DiagnosticsTone.Positive,
                                isBusy = false,
                                latestArchiveFileName = archive.fileName,
                            ),
                    )
                }
            }.onFailure {
                scanLifecycle.update { state ->
                    state.copy(
                        archiveActionState =
                            ArchiveActionState(
                                message = failureMessage,
                                tone = DiagnosticsTone.Negative,
                                isBusy = false,
                                latestArchiveFileName = state.archiveActionState.latestArchiveFileName,
                            ),
                    )
                }
            }
    }
}
