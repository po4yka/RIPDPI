package com.poyka.ripdpi.ui.screens.subscription

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.poyka.ripdpi.BuildConfig
import java.io.File
import java.util.UUID

internal fun migrationReceiptShareIntent(
    context: Context,
    receipt: String,
): Intent {
    val directory = File(context.cacheDir, "migration-receipts")
    check(directory.isDirectory || directory.mkdirs())
    val file = File(directory, "ripdpi-migration-${UUID.randomUUID()}.json")
    file.writeText(receipt, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
