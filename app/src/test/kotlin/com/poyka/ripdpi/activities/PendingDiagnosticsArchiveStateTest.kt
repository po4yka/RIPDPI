package com.poyka.ripdpi.activities

import android.net.Uri
import android.os.Bundle
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDiagnosticsArchiveStateTest {
    @Test fun `only opaque lease survives recreation and result consumes once`() {
        val id = DiagnosticsExportLeaseId.create()
        val original = PendingDiagnosticsArchiveState().apply { begin(id) }
        val saved = original.save()
        assertEquals(setOf("lease-id"), saved.keySet())
        val recreated = PendingDiagnosticsArchiveState().apply { restore(saved) }
        val uri = Uri.parse("content://documents/archive.zip")
        assertEquals(PendingDiagnosticsArchiveResult(id, uri), recreated.onPickerResult(uri))
        assertNull(recreated.onPickerResult(uri))
    }

    @Test fun `cancel returns owning lease for cleanup and cannot consume replacement`() {
        val id = DiagnosticsExportLeaseId.create()
        val state = PendingDiagnosticsArchiveState().apply { begin(id) }
        assertThrows(IllegalStateException::class.java) { state.begin(DiagnosticsExportLeaseId.create()) }
        assertEquals(PendingDiagnosticsArchiveResult(id, null), state.onPickerResult(null))
        assertNull(state.onPickerResult(null))
    }

    @Test fun `untrusted restored path and invalid lease never become delete capability`() {
        val state =
            PendingDiagnosticsArchiveState().apply {
                restore(
                    Bundle().apply {
                        putString("lease-id", "../../other-private-file")
                        putString("file-path", "/private/other-file")
                    },
                )
            }
        assertNull(state.onPickerResult(Uri.parse("content://documents/archive.zip")))
        assertEquals(emptySet<String>(), state.save().keySet())
    }
}
