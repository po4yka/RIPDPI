package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DiagnosticsExportFeedbackTest {
    private val context =
        DiagnosticsExportSupportContext(
            appVersion = "1.2.3",
            versionCode = 42,
            buildType = "release",
            gitCommit = "abc123",
            androidRelease = "15",
            androidApi = 35,
            deviceManufacturer = "Samsung",
            deviceModel = "SM-S928B",
            primaryAbi = "arm64-v8a",
            occurredAtEpochMs = 1_785_000_000_000,
        )

    @Test
    fun `destination open failure gives actionable feedback and complete safe support payload`() {
        val failure =
            DiagnosticsDocumentExportException(
                stage = DiagnosticsDocumentExportStage.DESTINATION_OPEN,
                partialDocumentDeleted = true,
                cause = SecurityException("content://private-provider/secret/document/42"),
            )

        val feedback = diagnosticsArchiveSaveFeedback(failure, context)

        assertEquals(DiagnosticsExportSupportCodes.ArchiveDestinationOpen, feedback.supportCode)
        assertEquals(R.string.diagnostics_archive_destination_open_failed, feedback.messageRes)
        assertTrue(feedback.supportPayload.contains("operation=diagnostics_archive_save"))
        assertTrue(feedback.supportPayload.contains("stage=destination_open"))
        assertTrue(feedback.supportPayload.contains("partial_document_cleanup=removed"))
        assertTrue(
            feedback.supportPayload.contains(
                "exception_chain=DiagnosticsDocumentExportException > SecurityException",
            ),
        )
        assertTrue(feedback.supportPayload.contains("app_version=1.2.3 (42)"))
        assertTrue(feedback.supportPayload.contains("device=Samsung SM-S928B"))
        assertFalse(feedback.supportPayload.contains("content://"))
        assertFalse(feedback.supportPayload.contains("secret"))
    }

    @Test
    fun `typed archive database failure is preserved through document wrapper`() {
        val failure =
            DiagnosticsDocumentExportException(
                stage = DiagnosticsDocumentExportStage.ARCHIVE_WRITE,
                partialDocumentDeleted = false,
                cause =
                    DiagnosticsArchiveException(
                        DiagnosticsArchiveFailureCode.DATABASE,
                        IOException("/data/user/0/private-database"),
                    ),
            )

        val feedback = diagnosticsArchiveSaveFeedback(failure, context)

        assertEquals("archive_database", feedback.supportCode)
        assertEquals(R.string.diagnostics_archive_database_failed, feedback.messageRes)
        assertTrue(feedback.supportPayload.contains("archive_failure=database"))
        assertTrue(feedback.supportPayload.contains("partial_document_cleanup=not_removed"))
        assertFalse(feedback.supportPayload.contains("/data/user"))
    }

    @Test
    fun `typed inconsistent result gets rerun guidance`() {
        val failure =
            DiagnosticsArchiveException(
                DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT,
                IllegalStateException("session id changed"),
            )

        val feedback = diagnosticsArchiveSaveFeedback(failure, context)

        assertEquals(DiagnosticsExportSupportCodes.ArchiveInconsistentResult, feedback.supportCode)
        assertEquals(R.string.diagnostics_archive_inconsistent_result_failed, feedback.messageRes)
        assertTrue(feedback.supportPayload.contains("stage=archive_prepare"))
    }

    @Test
    fun `typed storage failure gives device storage guidance`() {
        val failure =
            DiagnosticsArchiveException(
                DiagnosticsArchiveFailureCode.STORAGE,
                IOException("private cache unavailable"),
            )

        val feedback = diagnosticsArchiveSaveFeedback(failure, context)

        assertEquals(DiagnosticsExportSupportCodes.ArchiveStorage, feedback.supportCode)
        assertEquals(R.string.diagnostics_archive_storage_failed, feedback.messageRes)
        assertTrue(feedback.supportPayload.contains("archive_failure=storage"))
    }

    @Test
    fun `untyped document write failure gets selected location guidance`() {
        val failure =
            DiagnosticsDocumentExportException(
                stage = DiagnosticsDocumentExportStage.ARCHIVE_WRITE,
                partialDocumentDeleted = true,
                cause = IOException("provider path must remain private"),
            )

        val feedback = diagnosticsArchiveSaveFeedback(failure, context)

        assertEquals(DiagnosticsExportSupportCodes.ArchiveDestinationWrite, feedback.supportCode)
        assertEquals(R.string.diagnostics_archive_destination_write_failed, feedback.messageRes)
        assertFalse(feedback.supportPayload.contains("provider path"))
    }
}
