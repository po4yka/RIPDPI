package com.poyka.ripdpi.activities

import android.net.Uri
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDiagnosticsArchiveStateTest {
    @Test
    fun `archive request survives recreation before picker URI callback`() {
        val request =
            DiagnosticsArchiveRequest(
                requestedSessionId = "session-42",
                reason = DiagnosticsArchiveReason.SAVE_ARCHIVE,
                requestedAt = 42L,
                includePcap = true,
            )
        val original = PendingDiagnosticsArchiveState().apply { pendingRequest = request }
        val recreated = PendingDiagnosticsArchiveState().apply { restore(original.save()) }
        val uri = Uri.parse("content://documents/archive.zip")

        assertEquals(PendingDiagnosticsArchiveResult.Request(uri, request), recreated.onPickerResult(uri))
        assertNull(recreated.onPickerResult(uri))
    }

    @Test
    fun `prepared archive survives recreation before picker URI callback`() {
        val original = PendingDiagnosticsArchiveState().apply { pendingFile = "/tmp/archive.zip" to "archive.zip" }
        val recreated = PendingDiagnosticsArchiveState().apply { restore(original.save()) }
        val uri = Uri.parse("content://documents/archive.zip")

        assertEquals(
            PendingDiagnosticsArchiveResult.File(uri, "/tmp/archive.zip", "archive.zip"),
            recreated.onPickerResult(uri),
        )
        assertNull(recreated.onPickerResult(uri))
    }

    @Test
    fun `cancelled picker clears restored archive request`() {
        val original = PendingDiagnosticsArchiveState().apply { pendingFile = "/tmp/archive.zip" to "archive.zip" }
        val recreated = PendingDiagnosticsArchiveState().apply { restore(original.save()) }

        assertNull(recreated.onPickerResult(null))
        assertNull(recreated.onPickerResult(Uri.parse("content://documents/archive.zip")))
    }
}
