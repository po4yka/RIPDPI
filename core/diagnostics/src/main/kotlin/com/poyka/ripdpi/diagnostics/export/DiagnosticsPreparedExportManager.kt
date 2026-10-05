package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticsExportRecordStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveFailureCode
import com.poyka.ripdpi.diagnostics.DiagnosticsLogRedactor
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject

internal data class PreparedArchiveContent(
    val entries: List<DiagnosticsArchiveEntry>,
    val sessionId: String?,
) {
    override fun toString(): String = "PreparedArchiveContent(entries=${entries.size})"
}

/** Owns prepared export transactions inside the exporter's single archiveMutex. */
internal class DiagnosticsPreparedExportManager
    @Inject
    constructor(
        private val files: DiagnosticsPreparedFileStore,
        private val records: DiagnosticsExportRecordStore,
        private val clock: PreparedExportClock,
        private val zipWriter: DiagnosticsArchiveZipWriter,
        private val logcatCollector: LogcatSnapshotCollector,
        private val logRedactor: DiagnosticsLogRedactor,
    ) {
        suspend fun prepare(
            id: DiagnosticsExportLeaseId,
            preparation: DiagnosticsExportPreparation,
            buildArchive: suspend (DiagnosticsArchiveTarget, DiagnosticsArchiveRequest) -> PreparedArchiveContent,
        ): PreparedDiagnosticsExport {
            check(records.getExportRecords().none { it.id == id.encoded }) { "Export record identity is already owned" }
            val purpose =
                when (preparation) {
                    is DiagnosticsExportPreparation.Archive -> {
                        preparation.purpose.also {
                            require(it != DiagnosticsExportPurpose.SaveLogs)
                            require(!preparation.request.includePcap)
                        }
                    }

                    DiagnosticsExportPreparation.Logs -> {
                        DiagnosticsExportPurpose.SaveLogs
                    }
                }
            val reservation = files.reserve(id, purpose, clock.snapshot())
            return runCatching {
                val target = files.artifact(reservation)
                var sessionId: String? = null
                when (preparation) {
                    is DiagnosticsExportPreparation.Archive -> {
                        val content =
                            buildArchive(
                                DiagnosticsArchiveTarget(target, reservation.fileName, reservation.createdAt),
                                preparation.request,
                            )
                        sessionId = content.sessionId
                        zipWriter.write(target, content.entries)
                    }

                    DiagnosticsExportPreparation.Logs -> {
                        val snapshot = requireNotNull(logcatCollector.capture()) { "Log snapshot is unavailable" }
                        target.writeText(logRedactor.redactLogcat(snapshot.content), Charsets.UTF_8)
                        restrictToOwner(target)
                    }
                }
                currentCoroutineContext().ensureActive()
                val content = PreparedDiagnosticsExportInspector.inspect(target, purpose)
                stage(DiagnosticsArchiveFailureCode.DATABASE) {
                    records.insertExportRecord(
                        ExportRecordEntity(
                            id.encoded,
                            sessionId,
                            target.absolutePath,
                            reservation.fileName,
                            reservation.createdAt,
                        ),
                    )
                }
                currentCoroutineContext().ensureActive()
                files.write(
                    reservation.copy(
                        phase = DiagnosticsExportLeasePhase.Ready,
                        byteCount = content.byteCount,
                        sha256 = content.sha256,
                    ),
                )
                inspect(id)
            }.getOrElse { error ->
                withContext(NonCancellable) {
                    runCatching { discard(id) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }

        suspend fun inspect(id: DiagnosticsExportLeaseId): PreparedDiagnosticsExport {
            val record = checkedRecord(id)
            val content = PreparedDiagnosticsExportInspector.inspect(files.artifact(record), record.purpose)
            check(content.byteCount == record.byteCount && equalDigest(content.sha256, record.sha256)) {
                "Prepared export bytes changed after preparation"
            }
            return PreparedDiagnosticsExport(
                id,
                record.purpose,
                record.phase,
                record.fileName,
                if (record.purpose == DiagnosticsExportPurpose.SaveLogs) "text/plain" else "application/zip",
                content.byteCount,
                content.summary,
                content.entryNames,
                record.expiresAt,
            )
        }

        suspend fun consume(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff {
            val preview = inspect(id)
            check(preview.phase == DiagnosticsExportLeasePhase.Ready) { "Prepared export was already consumed" }
            files.write(files.read(id).copy(phase = DiagnosticsExportLeasePhase.HandedOff))
            return validateHandoff(id)
        }

        suspend fun validateHandoff(id: DiagnosticsExportLeaseId): CheckedDiagnosticsExportHandoff {
            val preview = inspect(id)
            check(preview.phase == DiagnosticsExportLeasePhase.HandedOff) { "Prepared export has not been confirmed" }
            return CheckedDiagnosticsExportHandoff(preview, files.artifact(files.read(id)).absolutePath)
        }

        suspend fun copyPrepared(
            id: DiagnosticsExportLeaseId,
            destination: OutputStream,
        ) {
            val handoff = validateHandoff(id)
            check(
                handoff.preview.purpose == DiagnosticsExportPurpose.SaveArchive ||
                    handoff.preview.purpose == DiagnosticsExportPurpose.SaveLogs,
            ) {
                "Prepared export is not a document save"
            }
            files.artifact(files.read(id)).inputStream().use { input ->
                val buffer = ByteArray(CopyBufferBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
            }
            destination.flush()
            currentCoroutineContext().ensureActive()
        }

        suspend fun discard(id: DiagnosticsExportLeaseId) =
            withContext(NonCancellable) {
                if (!files.exists(id)) return@withContext
                val record = files.read(id)
                files.write(record.copy(phase = DiagnosticsExportLeasePhase.DiscardRequested))
                checkRecordOwnership(record)
                files.deleteArtifact(record)
                records.deleteExportRecords(listOf(id.encoded))
                files.deleteReservation(record)
            }

        suspend fun cleanup() {
            files.expiredLeases(clock).forEach { discard(requireNotNull(DiagnosticsExportLeaseId.parse(it.leaseId))) }
        }

        fun managedPaths(): Set<String> = files.records().mapTo(mutableSetOf()) { files.artifact(it).absolutePath }

        private suspend fun checkedRecord(id: DiagnosticsExportLeaseId): DiagnosticsExportLeaseRecord {
            val record = files.checkpointClock(id, clock)
            check(
                record.phase == DiagnosticsExportLeasePhase.Ready ||
                    record.phase == DiagnosticsExportLeasePhase.HandedOff,
            ) {
                "Prepared export is unavailable"
            }
            checkRecordOwnership(record, required = true)
            return record
        }

        private suspend fun checkRecordOwnership(
            record: DiagnosticsExportLeaseRecord,
            required: Boolean = false,
        ) {
            val persisted = records.getExportRecords().singleOrNull { it.id == record.leaseId }
            check(!required || persisted != null) { "Prepared export record is unavailable" }
            check(
                persisted == null ||
                    (
                        persisted.uri == files.artifact(record).absolutePath && persisted.fileName == record.fileName &&
                            persisted.createdAt == record.createdAt
                    ),
            ) {
                "Prepared export record identity mismatch"
            }
        }

        private fun equalDigest(
            actual: String,
            expected: String?,
        ): Boolean =
            expected != null &&
                MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))

        private suspend fun <T> stage(
            code: DiagnosticsArchiveFailureCode,
            operation: suspend () -> T,
        ): T =
            runCatching { operation() }.getOrElse { error ->
                when (error) {
                    is CancellationException, is DiagnosticsArchiveException, is Error -> throw error
                    else -> throw DiagnosticsArchiveException(code, error)
                }
            }

        private companion object {
            const val CopyBufferBytes = 8192
        }
    }
