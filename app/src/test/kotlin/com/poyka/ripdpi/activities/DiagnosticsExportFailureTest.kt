package com.poyka.ripdpi.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsExportFailureTest {
    @Test
    fun `missing export handler becomes stable share code`() {
        val code =
            launchDiagnosticsExport(Intent(Intent.ACTION_SEND)) {
                throw ActivityNotFoundException("No activity")
            }

        assertEquals(DiagnosticsExportSupportCodes.ShareNoHandler, code)
    }

    @Test
    fun `unexpected export launcher failure becomes safe io code`() {
        val code =
            launchDiagnosticsExport(Intent(Intent.ACTION_SEND)) {
                throw SecurityException("provider internals")
            }

        assertEquals(DiagnosticsExportSupportCodes.ArchiveIo, code)
    }

    @Test
    fun `successful export launcher has no error code`() {
        assertNull(launchDiagnosticsExport(Intent(Intent.ACTION_SEND)) {})
    }
}
