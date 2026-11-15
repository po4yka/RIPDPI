package com.poyka.ripdpi.diagnostics.export

import java.io.File
import java.util.UUID

fun interface DiagnosticsArchiveClock {
    fun now(): Long
}

class DiagnosticsArchiveFileStore(
    private val cacheDir: File,
    private val clock: DiagnosticsArchiveClock,
    private val cleanupCaptureStorage: suspend () -> Unit = {},
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    fun cleanup(reservedSlots: Int = 0) {
        require(reservedSlots in 0..DiagnosticsArchiveFormat.maxArchiveFiles)
        val archiveDirectory = ensureArchiveDirectory()
        val now = clock.now()
        val archiveFiles =
            archiveDirectory
                .listFiles()
                .orEmpty()
                .filter(::isManagedArchive)
                .sortedByDescending { it.lastModified() }

        archiveFiles
            .filter { now - it.lastModified() > DiagnosticsArchiveFormat.maxArchiveAgeMs }
            .forEach(::deleteVerified)

        archiveDirectory
            .listFiles()
            .orEmpty()
            .filter(::isManagedArchive)
            .sortedByDescending { it.lastModified() }
            .drop(DiagnosticsArchiveFormat.maxArchiveFiles - reservedSlots)
            .forEach(::deleteVerified)
    }

    internal fun reconcileFiles(referencedPaths: Set<String>) {
        val archiveDirectory = ensureArchiveDirectory()
        archiveDirectory
            .listFiles()
            .orEmpty()
            .filter(::isTemporaryArchive)
            .forEach(::deleteVerified)
        archiveDirectory
            .listFiles()
            .orEmpty()
            .filter(::isManagedArchive)
            .filterNot { it.absolutePath in referencedPaths }
            .forEach(::deleteVerified)
    }

    internal fun managedArchivePaths(): Set<String> =
        ensureArchiveDirectory()
            .listFiles()
            .orEmpty()
            .filter(::isManagedArchive)
            .mapTo(mutableSetOf()) { it.absolutePath }

    internal fun deleteArchive(file: File) = deleteVerified(file)

    suspend fun cleanupPcapFiles() = cleanupCaptureStorage()

    internal fun createTarget(): DiagnosticsArchiveTarget {
        val createdAt = clock.now()
        val fileName = "${DiagnosticsArchiveFormat.fileNamePrefix}$createdAt-${UUID.randomUUID()}.zip"
        val file = File(ensureArchiveDirectory(), fileName)
        return DiagnosticsArchiveTarget(
            file = file,
            fileName = fileName,
            createdAt = createdAt,
        )
    }

    private fun ensureArchiveDirectory(): File {
        val directory = File(cacheDir, DiagnosticsArchiveFormat.directoryName)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create diagnostics archive directory" }
        restrictToOwner(directory, directory = true)
        return directory
    }

    private fun deleteVerified(file: File) {
        check(!file.exists() || deleteFile(file)) { "Unable to delete diagnostics archive artifact: ${file.name}" }
    }

    private fun isManagedArchive(file: File): Boolean =
        file.isFile &&
            file.name.startsWith(DiagnosticsArchiveFormat.fileNamePrefix) &&
            file.extension == "zip"

    private fun isTemporaryArchive(file: File): Boolean =
        file.isFile &&
            file.name.startsWith("${DiagnosticsArchiveFormat.fileNamePrefix}.") &&
            file.name.endsWith(".tmp")
}

internal fun restrictToOwner(
    file: File,
    directory: Boolean = false,
) {
    check(file.setReadable(false, false) && file.setReadable(true, true)) {
        "Unable to restrict diagnostics artifact read permissions"
    }
    check(file.setWritable(false, false) && file.setWritable(true, true)) {
        "Unable to restrict diagnostics artifact write permissions"
    }
    check(file.setExecutable(false, false)) { "Unable to clear diagnostics artifact execute permissions" }
    if (directory) {
        check(file.setExecutable(true, true)) { "Unable to restrict diagnostics directory execute permissions" }
    }
}
