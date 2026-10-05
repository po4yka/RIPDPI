package com.poyka.ripdpi.activities

import android.net.Uri
import android.os.Bundle
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId

internal const val PendingDiagnosticsArchiveStateKey = "pending-diagnostics-archive"
private const val PendingLeaseKey = "lease-id"

internal data class PendingDiagnosticsArchiveResult(
    val leaseId: DiagnosticsExportLeaseId,
    val uri: Uri?,
)

/** Only a checked opaque ID crosses Activity recreation, never a caller-supplied path or request. */
internal class PendingDiagnosticsArchiveState {
    private var pendingLease: DiagnosticsExportLeaseId? = null

    fun begin(id: DiagnosticsExportLeaseId) {
        check(pendingLease == null) { "An export destination is already pending" }
        pendingLease = id
    }

    fun save(): Bundle = Bundle().apply { pendingLease?.let { putString(PendingLeaseKey, it.encoded) } }

    fun restore(saved: Bundle?) {
        pendingLease = saved?.getString(PendingLeaseKey)?.let(DiagnosticsExportLeaseId::parse)
    }

    fun onPickerResult(uri: Uri?): PendingDiagnosticsArchiveResult? =
        pendingLease?.let { id ->
            pendingLease = null
            PendingDiagnosticsArchiveResult(id, uri)
        }
}
