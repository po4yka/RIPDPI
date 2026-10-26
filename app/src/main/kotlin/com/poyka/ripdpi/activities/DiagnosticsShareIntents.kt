package com.poyka.ripdpi.activities

import android.content.Intent
import android.net.Uri
import com.poyka.ripdpi.platform.withoutBidiIsolates

internal object DiagnosticsShareIntents {
    fun createSummaryShareIntent(
        title: String,
        body: String,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title.withoutBidiIsolates())
            putExtra(Intent.EXTRA_TEXT, body.withoutBidiIsolates())
        }

    fun createArchiveShareIntent(
        archiveUri: Uri,
        fileName: String,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, archiveUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
