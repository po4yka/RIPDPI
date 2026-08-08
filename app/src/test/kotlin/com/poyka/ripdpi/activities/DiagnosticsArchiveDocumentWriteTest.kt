package com.poyka.ripdpi.activities

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DiagnosticsArchiveDocumentWriteTest {
    private val destination = Uri.parse("content://documents/diagnostics.zip")

    @Test
    fun `successful direct document export preserves the document`() =
        runTest {
            val output = ByteArrayOutputStream()
            var deleted = false

            writeDiagnosticsArchiveDocument(
                destination = destination,
                openDestinationStream = { output },
                writeArchive = { it.write("archive".toByteArray()) },
                deleteDocument = {
                    deleted = true
                    true
                },
            )

            assertArrayEquals("archive".toByteArray(), output.toByteArray())
            assertFalse(deleted)
        }

    @Test
    fun `missing direct document stream deletes the created document`() =
        runTest {
            var deletedUri: Uri? = null

            try {
                writeDiagnosticsArchiveDocument(
                    destination = destination,
                    openDestinationStream = { null },
                    writeArchive = { error("Archive writer must not run without a stream") },
                    deleteDocument = {
                        deletedUri = it
                        true
                    },
                )
                throw AssertionError("Expected a missing stream to fail the export")
            } catch (failure: DiagnosticsDocumentExportException) {
                assertEquals(DiagnosticsDocumentExportStage.DESTINATION_OPEN, failure.stage)
                assertTrue(failure.partialDocumentDeleted)
            }

            assertEquals(destination, deletedUri)
        }

    @Test
    fun `failed direct document export deletes the partial document`() =
        runTest {
            var deletedUri: Uri? = null

            try {
                writeDiagnosticsArchiveDocument(
                    destination = destination,
                    openDestinationStream = { ByteArrayOutputStream() },
                    writeArchive = { throw IOException("provider refused write") },
                    deleteDocument = {
                        deletedUri = it
                        true
                    },
                )
                throw AssertionError("Expected a failed write to fail the export")
            } catch (failure: DiagnosticsDocumentExportException) {
                assertEquals(DiagnosticsDocumentExportStage.ARCHIVE_WRITE, failure.stage)
                assertTrue(failure.partialDocumentDeleted)
                assertEquals("provider refused write", failure.cause?.message)
            }

            assertEquals(destination, deletedUri)
        }

    @Test
    fun `cancelled direct document export deletes the partial document and stays cancelled`() =
        runTest {
            var deletedUri: Uri? = null

            try {
                writeDiagnosticsArchiveDocument(
                    destination = destination,
                    openDestinationStream = { ByteArrayOutputStream() },
                    writeArchive = { throw CancellationException("user cancelled") },
                    deleteDocument = {
                        deletedUri = it
                        true
                    },
                )
                throw AssertionError("Expected cancellation to propagate")
            } catch (_: CancellationException) {
                // Expected: cancellation remains cancellation after best-effort cleanup.
            }

            assertEquals(destination, deletedUri)
        }

    @Test
    fun `direct document export retains original failure when provider refuses deletion`() =
        runTest {
            var deleteAttempts = 0

            try {
                writeDiagnosticsArchiveDocument(
                    destination = destination,
                    openDestinationStream = { ByteArrayOutputStream() },
                    writeArchive = { throw IOException("provider refused write") },
                    deleteDocument = {
                        deleteAttempts += 1
                        throw SecurityException("provider does not allow deletes")
                    },
                )
                throw AssertionError("Expected the archive write failure to propagate")
            } catch (failure: DiagnosticsDocumentExportException) {
                assertEquals(DiagnosticsDocumentExportStage.ARCHIVE_WRITE, failure.stage)
                assertFalse(failure.partialDocumentDeleted)
                assertEquals("provider refused write", failure.cause?.message)
            }

            assertEquals(1, deleteAttempts)
        }
}
