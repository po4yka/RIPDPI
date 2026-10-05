package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose

internal class DiagnosticsShareActions(
    private val mutations: DiagnosticsMutationRunner,
) {
    fun shareSummary(sessionId: String?) = request(sessionId, DiagnosticsExportPurpose.ShareSummary)

    fun shareArchive(sessionId: String?) = request(sessionId, DiagnosticsExportPurpose.ShareArchive)

    fun saveArchive(sessionId: String?) = request(sessionId, DiagnosticsExportPurpose.SaveArchive)

    private fun request(
        sessionId: String?,
        purpose: DiagnosticsExportPurpose,
    ) {
        mutations.launch {
            val targetId = sessionId ?: currentUiState().share.targetSessionId
            emit(
                DiagnosticsEffect.PrepareExportRequested(
                    DiagnosticsExportPreparation.Archive(
                        request =
                            DiagnosticsArchiveRequest(
                                requestedSessionId = targetId,
                                reason =
                                    if (purpose == DiagnosticsExportPurpose.SaveArchive) {
                                        DiagnosticsArchiveReason.SAVE_ARCHIVE
                                    } else {
                                        DiagnosticsArchiveReason.SHARE_ARCHIVE
                                    },
                                requestedAt = System.currentTimeMillis(),
                            ),
                        purpose = purpose,
                    ),
                ),
            )
        }
    }
}
