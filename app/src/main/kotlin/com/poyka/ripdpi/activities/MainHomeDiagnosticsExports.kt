package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import kotlinx.coroutines.flow.StateFlow

/** Captures the displayed Home run before dispatch; only the preview owner can prepare or hand it off. */
internal class MainHomeDiagnosticsExports(
    private val mutations: MainMutationRunner,
    private val state: StateFlow<HomeDiagnosticsRuntimeState>,
) {
    fun share() = request(DiagnosticsExportPurpose.ShareArchive)

    fun save() = request(DiagnosticsExportPurpose.SaveArchive)

    private fun request(purpose: DiagnosticsExportPurpose) {
        val outcome = state.value.latestCompositeOutcome ?: return
        val preparation =
            DiagnosticsExportPreparation.Archive(
                request =
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds.toList(),
                        homeRunId = outcome.runId,
                        reason =
                            if (purpose == DiagnosticsExportPurpose.SaveArchive) {
                                DiagnosticsArchiveReason.SAVE_ARCHIVE
                            } else {
                                DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS
                            },
                        requestedAt = System.currentTimeMillis(),
                    ),
                purpose = purpose,
            )
        mutations.launch { mutations.emit(MainEffect.PrepareDiagnosticsExport(preparation)) }
    }
}
