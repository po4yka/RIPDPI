package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
internal data class DiagnosticsExportLeaseRecord(
    val leaseId: String,
    val purpose: DiagnosticsExportPurpose,
    val phase: DiagnosticsExportLeasePhase,
    val fileName: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastObservedAt: Long,
    val createdElapsedMs: Long,
    val lastObservedElapsedMs: Long,
    val bootCount: Int,
    val byteCount: Long? = null,
    val sha256: String? = null,
)

/** Private durable journal for prepared artifacts; all calls are serialized by archiveMutex. */
class DiagnosticsPreparedFileStore(
    private val filesDir: File,
    private val json: Json,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    internal fun reserve(
        id: DiagnosticsExportLeaseId,
        purpose: DiagnosticsExportPurpose,
        time: PreparedExportTime,
    ): DiagnosticsExportLeaseRecord {
        val now = time.wallTimeMs
        require(time.elapsedTimeMs >= 0)
        val bootCount = requireNotNull(time.bootCount) { "Export boot identity is unavailable" }
        require(bootCount >= 0)
        val record =
            DiagnosticsExportLeaseRecord(
                leaseId = id.encoded,
                purpose = purpose,
                phase = DiagnosticsExportLeasePhase.Preparing,
                fileName = if (purpose == DiagnosticsExportPurpose.SaveLogs) "ripdpi.log" else "ripdpi-diagnostics.zip",
                createdAt = now,
                expiresAt = Math.addExact(now, DiagnosticsArchiveFormat.maxArchiveAgeMs),
                lastObservedAt = now,
                createdElapsedMs = time.elapsedTimeMs,
                lastObservedElapsedMs = time.elapsedTimeMs,
                bootCount = bootCount,
            )
        val target = verifiedChild(id.encoded)
        check(!verifiedChild(".discard-${id.encoded}").exists()) { "Prepared lease cleanup is still pending" }
        check(!target.exists()) { "Prepared export lease already exists" }
        val temporary = File(root(), ".reserve-${id.encoded}-${UUID.randomUUID()}")
        check(temporary.mkdir()) { "Unable to create prepared reservation" }
        restrictToOwner(temporary, directory = true)
        runCatching {
            writeTo(record, temporary)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { error ->
            runCatching {
                temporary.listFiles().orEmpty().forEach(::deleteVerified)
                deleteVerified(temporary)
            }.exceptionOrNull()?.let(error::addSuppressed)
        }.getOrThrow()
        return record
    }

    internal fun read(id: DiagnosticsExportLeaseId): DiagnosticsExportLeaseRecord {
        val journal = File(directory(id), "lease.json")
        check(!Files.isSymbolicLink(journal.toPath())) { "Export journal cannot be a symbolic link" }
        val record = json.decodeFromString<DiagnosticsExportLeaseRecord>(journal.readText())
        check(record.leaseId == id.encoded) { "Prepared export lease identity mismatch" }
        check(
            record.fileName ==
                if (record.purpose == DiagnosticsExportPurpose.SaveLogs) "ripdpi.log" else "ripdpi-diagnostics.zip",
        ) {
            "Invalid prepared export filename"
        }
        check(record.expiresAt == Math.addExact(record.createdAt, DiagnosticsArchiveFormat.maxArchiveAgeMs)) {
            "Invalid prepared export expiry"
        }
        check(record.lastObservedAt >= record.createdAt && record.lastObservedAt < record.expiresAt) {
            "Invalid prepared export clock checkpoint"
        }
        check(
            record.bootCount >= 0 && record.createdElapsedMs >= 0 &&
                record.lastObservedElapsedMs >= record.createdElapsedMs &&
                record.lastObservedElapsedMs <
                Math.addExact(record.createdElapsedMs, DiagnosticsArchiveFormat.maxArchiveAgeMs),
        ) {
            "Invalid prepared export monotonic checkpoint"
        }
        return record
    }

    internal fun checkpointClock(
        id: DiagnosticsExportLeaseId,
        clock: PreparedExportClock,
    ): DiagnosticsExportLeaseRecord =
        synchronized(JournalLock) {
            val record = read(id)
            val time = clock.snapshot()
            check(record.isEligibleAt(time)) { "Prepared export clock is ineligible" }
            val checkpoint = record.copy(lastObservedAt = time.wallTimeMs, lastObservedElapsedMs = time.elapsedTimeMs)
            writeTo(checkpoint, directory(id))
            checkpoint
        }

    internal fun expiredLeases(clock: PreparedExportClock): List<DiagnosticsExportLeaseRecord> =
        synchronized(JournalLock) {
            val leases = records()
            val time = clock.snapshot()
            leases.filter {
                !it.isEligibleAt(time) ||
                    it.phase == DiagnosticsExportLeasePhase.DiscardRequested
            }
        }

    internal fun write(record: DiagnosticsExportLeaseRecord) =
        synchronized(JournalLock) {
            val id = requireNotNull(DiagnosticsExportLeaseId.parse(record.leaseId))
            val latest = read(id)
            check(record.bootCount == latest.bootCount && record.createdElapsedMs == latest.createdElapsedMs)
            writeTo(
                record.copy(
                    lastObservedAt = maxOf(record.lastObservedAt, latest.lastObservedAt),
                    lastObservedElapsedMs = maxOf(record.lastObservedElapsedMs, latest.lastObservedElapsedMs),
                ),
                directory(id),
            )
        }

    private fun writeTo(
        record: DiagnosticsExportLeaseRecord,
        directory: File,
    ) {
        val temporary = File.createTempFile("lease-", ".tmp", directory)
        restrictToOwner(temporary)
        val operation =
            runCatching {
                FileOutputStream(temporary).use {
                    it.write(json.encodeToString(record).toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    File(directory, "lease.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        val cleanup = runCatching { deleteVerified(temporary) }
        operation.exceptionOrNull()?.let { error -> cleanup.exceptionOrNull()?.let(error::addSuppressed) }
        operation.getOrThrow()
        cleanup.getOrThrow()
    }

    internal fun artifact(record: DiagnosticsExportLeaseRecord): File =
        File(directory(requireNotNull(DiagnosticsExportLeaseId.parse(record.leaseId))), record.fileName).also {
            check(!Files.isSymbolicLink(it.toPath())) { "Prepared export cannot be a symbolic link" }
            check(
                it.canonicalFile.parentFile == requireNotNull(it.parentFile).canonicalFile,
            ) { "Prepared export path escaped its lease" }
        }

    internal fun records(): List<DiagnosticsExportLeaseRecord> =
        root().listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { directory ->
            check(!Files.isSymbolicLink(directory.toPath())) { "Managed export directory cannot be a symbolic link" }
            when {
                isUnpublishedReservation(directory.name) -> {
                    null
                }

                directory.name.startsWith(".discard-") -> {
                    val id = requireNotNull(DiagnosticsExportLeaseId.parse(directory.name.removePrefix(".discard-")))
                    if (directory.listFiles()?.isEmpty() == true) {
                        // Atomic retirement proves ownership of this empty namespace.
                        // No arbitrary contents are deleted.
                        deleteVerified(verifiedChild(directory.name))
                        null
                    } else {
                        read(id).also {
                            check(it.phase == DiagnosticsExportLeasePhase.DiscardRequested) {
                                "Retired export journal has an invalid phase"
                            }
                        }
                    }
                }

                else -> {
                    read(
                        requireNotNull(DiagnosticsExportLeaseId.parse(directory.name)) {
                            "Invalid managed export directory"
                        },
                    )
                }
            }
        }

    private fun isUnpublishedReservation(name: String): Boolean {
        if (!name.startsWith(".reserve-")) return false
        val ids = name.removePrefix(".reserve-")
        return ids.length == ReservationSuffixLength && ids[EncodedLeaseLength] == '-' &&
            DiagnosticsExportLeaseId.parse(ids.take(EncodedLeaseLength)) != null &&
            DiagnosticsExportLeaseId.parse(ids.drop(EncodedLeaseLength + 1)) != null
    }

    internal fun deleteArtifact(record: DiagnosticsExportLeaseRecord) = deleteVerified(artifact(record))

    internal fun deleteReservation(record: DiagnosticsExportLeaseRecord) {
        val id = requireNotNull(DiagnosticsExportLeaseId.parse(record.leaseId))
        val retiring = record.copy(phase = DiagnosticsExportLeasePhase.DiscardRequested)
        write(retiring)
        val current = directory(id)
        val retired = verifiedChild(".discard-${id.encoded}")
        if (current != retired) Files.move(current.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE)
        retired
            .listFiles()
            .orEmpty()
            .filter { it.name != "lease.json" }
            .forEach(::deleteVerified)
        deleteVerified(File(retired, "lease.json"))
        runCatching { deleteVerified(retired) }
            .onFailure { error ->
                // Keep the known journal retryable after a deletion error.
                // Empty tombstones also recover after process death.
                runCatching { writeTo(retiring, retired) }.exceptionOrNull()?.let(error::addSuppressed)
            }.getOrThrow()
    }

    internal fun exists(id: DiagnosticsExportLeaseId): Boolean = File(directory(id), "lease.json").isFile

    private fun directory(id: DiagnosticsExportLeaseId): File {
        val published = verifiedChild(id.encoded)
        val retired = verifiedChild(".discard-${id.encoded}")
        check(!published.exists() || !retired.exists()) { "Prepared export has conflicting owned directories" }
        return if (retired.exists()) retired else published
    }

    private fun verifiedChild(name: String): File =
        File(root(), name).also {
            check(!Files.isSymbolicLink(it.toPath())) { "Prepared lease cannot be a symbolic link" }
            check(it.canonicalFile.parentFile == root().canonicalFile) { "Prepared lease path escaped managed storage" }
        }

    private fun root(): File =
        File(filesDir, "diagnostics-prepared").also {
            check(!Files.isSymbolicLink(it.toPath())) { "Prepared export storage cannot be a symbolic link" }
            check(it.isDirectory || it.mkdirs()) { "Unable to create prepared export storage" }
            restrictToOwner(it, directory = true)
        }

    private companion object {
        val JournalLock = Any()
        const val EncodedLeaseLength = 36
        const val ReservationSuffixLength = EncodedLeaseLength * 2 + 1
    }

    private fun deleteVerified(file: File) {
        check(!file.exists() || deleteFile(file)) { "Unable to remove prepared export artifact" }
    }
}
