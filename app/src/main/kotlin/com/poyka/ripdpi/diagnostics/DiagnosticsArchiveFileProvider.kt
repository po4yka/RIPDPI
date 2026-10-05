package com.poyka.ripdpi.diagnostics

import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.poyka.ripdpi.diagnostics.export.AndroidPreparedExportClock
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedArtifactReader
import java.io.FileNotFoundException

/** Checked read-only, expiring prepared exports. Other paths retain their existing contract. */
class DiagnosticsArchiveFileProvider : FileProvider() {
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val parts = uri.pathSegments
        if (parts.firstOrNull() != "diagnostics_prepared") return requireNotNull(super.openFile(uri, mode))
        try {
            check(mode == "r" && parts.size == PreparedPathSegments) { "Invalid export read" }
            check(Looper.myLooper() != Looper.getMainLooper()) { "Export validation requires a reader thread" }
            val id = requireNotNull(DiagnosticsExportLeaseId.parse(parts[1]))
            val owner = requireNotNull(context)
            val file =
                DiagnosticsPreparedArtifactReader(
                    owner.filesDir,
                    AndroidPreparedExportClock(owner),
                ).checkedFile(id, parts[2])
            check(getUriForFile(owner, requireNotNull(uri.authority), file) == uri) { "Invalid export URI" }
            return requireNotNull(super.openFile(uri, mode))
        } catch (_: Exception) {
            throw FileNotFoundException("Prepared export is unavailable")
        }
    }

    private companion object {
        const val PreparedPathSegments = 3
    }
}
