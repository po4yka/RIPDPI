package com.poyka.ripdpi.activities

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsShareIntentsTest {
    @Test
    fun `archive share intent grants read access for zip attachment`() {
        val uri = Uri.parse("content://com.poyka.ripdpi.diagnostics.fileprovider/diagnostics/archive.zip")

        val intent = DiagnosticsShareIntents.createArchiveShareIntent(uri, "archive.zip")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals("archive.zip", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(uri, intent.extras?.get(Intent.EXTRA_STREAM))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `summary share intent uses plain text payload`() {
        val intent = DiagnosticsShareIntents.createSummaryShareIntent("Summary", "Body")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Summary", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Body", intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
