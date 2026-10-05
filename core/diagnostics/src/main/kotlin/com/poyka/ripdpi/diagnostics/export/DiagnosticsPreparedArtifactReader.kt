package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Checks a delayed URI read using only the committed private journal; never starts Room or Hilt. */
class DiagnosticsPreparedArtifactReader(
    filesDir: File,
    private val clock: PreparedExportClock,
) {
    private val files = DiagnosticsPreparedFileStore(filesDir, Json)

    fun checkedFile(
        id: DiagnosticsExportLeaseId,
        fileName: String,
    ): File {
        val record = files.checkpointClock(id, clock)
        check(record.phase == DiagnosticsExportLeasePhase.HandedOff) { "Export was not confirmed" }
        check(record.fileName == fileName) { "Export filename mismatch" }
        val artifact = files.artifact(record)
        val content = PreparedDiagnosticsExportInspector.inspect(artifact, record.purpose)
        check(content.byteCount == record.byteCount && record.sha256 != null) { "Export size mismatch" }
        check(
            MessageDigest.isEqual(
                content.sha256.toByteArray(Charsets.US_ASCII),
                record.sha256.toByteArray(Charsets.US_ASCII),
            ),
        ) { "Export content mismatch" }
        return artifact
    }
}
