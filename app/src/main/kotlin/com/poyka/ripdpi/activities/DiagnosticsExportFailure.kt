package com.poyka.ripdpi.activities

import android.content.ActivityNotFoundException
import android.content.Intent

internal object DiagnosticsExportSupportCodes {
    const val ArchiveStorage = "archive_storage"
    const val ArchiveIo = "archive_io"
    const val ArchiveInconsistentResult = "archive_inconsistent_result"
    const val ArchiveDestinationOpen = "archive_destination_open"
    const val ArchiveDestinationWrite = "archive_destination_write"
    const val ShareNoHandler = "share_no_handler"
}

/**
 * Runs an Android export/share launcher without exposing platform exceptions
 * to the user. Callers surface the returned stable code with the support-copy
 * action used by the host UI.
 */
internal fun launchDiagnosticsExport(
    intent: Intent,
    launch: (Intent) -> Unit,
): String? =
    try {
        launch(intent)
        null
    } catch (_: ActivityNotFoundException) {
        DiagnosticsExportSupportCodes.ShareNoHandler
    } catch (_: Exception) {
        DiagnosticsExportSupportCodes.ArchiveIo
    }
