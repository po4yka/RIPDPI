package com.poyka.ripdpi.activities

import android.net.Uri
import android.os.Bundle
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveRequest
import kotlinx.serialization.json.Json

internal const val PendingDiagnosticsArchiveStateKey = "pending-diagnostics-archive"
private const val PendingArchiveRequestKey = "request"
private const val PendingArchiveFilePathKey = "file-path"
private const val PendingArchiveFileNameKey = "file-name"

internal sealed interface PendingDiagnosticsArchiveResult {
    data class Request(
        val uri: Uri,
        val request: DiagnosticsArchiveRequest,
    ) : PendingDiagnosticsArchiveResult

    data class File(
        val uri: Uri,
        val filePath: String,
        val fileName: String,
    ) : PendingDiagnosticsArchiveResult
}

internal class PendingDiagnosticsArchiveState {
    var pendingRequest: DiagnosticsArchiveRequest? = null
        set(value) {
            field = value
            if (value != null) pendingFile = null
        }
    var pendingFile: Pair<String, String>? = null
        set(value) {
            field = value
            if (value != null) pendingRequest = null
        }

    fun save(): Bundle =
        Bundle().apply {
            pendingRequest?.let { putString(PendingArchiveRequestKey, Json.encodeToString(it)) }
            pendingFile?.let { (path, name) ->
                putString(PendingArchiveFilePathKey, path)
                putString(PendingArchiveFileNameKey, name)
            }
        }

    fun restore(saved: Bundle?) {
        saved ?: return
        pendingRequest =
            saved
                .getString(PendingArchiveRequestKey)
                ?.let { encoded ->
                    runCatching { Json.decodeFromString<DiagnosticsArchiveRequest>(encoded) }.getOrNull()
                }
        if (pendingRequest == null) {
            val path = saved.getString(PendingArchiveFilePathKey)
            val name = saved.getString(PendingArchiveFileNameKey)
            if (path != null && name != null) pendingFile = path to name
        }
    }

    fun onPickerResult(uri: Uri?): PendingDiagnosticsArchiveResult? {
        val request = pendingRequest
        val file = pendingFile
        pendingRequest = null
        pendingFile = null
        uri ?: return null
        return when {
            request != null -> PendingDiagnosticsArchiveResult.Request(uri, request)
            file != null -> PendingDiagnosticsArchiveResult.File(uri, file.first, file.second)
            else -> null
        }
    }
}
