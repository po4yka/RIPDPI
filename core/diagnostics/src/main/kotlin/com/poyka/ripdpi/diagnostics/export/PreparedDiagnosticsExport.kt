package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable
import java.io.OutputStream
import java.util.UUID

/** Opaque lookup key; neither saved state nor a caller can supply a filesystem path. */
class DiagnosticsExportLeaseId private constructor(
    val encoded: String,
) {
    override fun equals(other: Any?): Boolean = other is DiagnosticsExportLeaseId && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "DiagnosticsExportLeaseId(<private>)"

    companion object {
        fun create(): DiagnosticsExportLeaseId = DiagnosticsExportLeaseId(UUID.randomUUID().toString())

        fun parse(value: String): DiagnosticsExportLeaseId? =
            value
                .takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }
                ?.let(::DiagnosticsExportLeaseId)
    }
}

@Serializable
enum class DiagnosticsExportPurpose { ShareSummary, ShareArchive, SaveArchive, SaveLogs }

sealed interface DiagnosticsExportPreparation {
    data class Archive(
        val request: DiagnosticsArchiveRequest,
        val purpose: DiagnosticsExportPurpose,
    ) : DiagnosticsExportPreparation {
        override fun toString(): String = "DiagnosticsExportPreparation.Archive(purpose=$purpose)"
    }

    data object Logs : DiagnosticsExportPreparation
}

@Serializable
enum class DiagnosticsExportLeasePhase { Preparing, Ready, HandedOff, DiscardRequested }

data class PreparedDiagnosticsExport(
    val leaseId: DiagnosticsExportLeaseId,
    val purpose: DiagnosticsExportPurpose,
    val phase: DiagnosticsExportLeasePhase,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
    val summary: String,
    val entryNames: List<String>,
    val expiresAt: Long,
) {
    override fun toString(): String = "PreparedDiagnosticsExport(purpose=$purpose, phase=$phase, byteCount=$byteCount)"
}

/** Returned only after durable one-shot consumption and checked private file/record identity. */
class CheckedDiagnosticsExportHandoff(
    val preview: PreparedDiagnosticsExport,
    val absolutePath: String,
) {
    override fun toString(): String = "CheckedDiagnosticsExportHandoff(purpose=${preview.purpose})"
}

interface PreparedDiagnosticsExportService {
    suspend fun prepare(
        id: DiagnosticsExportLeaseId,
        preparation: DiagnosticsExportPreparation,
    ): PreparedDiagnosticsExport

    suspend fun inspect(id: DiagnosticsExportLeaseId): PreparedDiagnosticsExport

    suspend fun consume(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff

    suspend fun validateHandoff(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff

    suspend fun copyPrepared(
        id: DiagnosticsExportLeaseId,
        destination: OutputStream,
    )

    suspend fun discard(id: DiagnosticsExportLeaseId)
}
