package com.poyka.ripdpi.diagnostics.export

import java.io.File
import java.util.UUID

fun interface DiagnosticsArchiveClock {
    fun now(): Long
}

private const val PcapRetentionWindowMs = 24L * 60L * 60L * 1000L
private const val PcapRetentionMaxFiles = 4
private const val PcapRetentionMaxBytes = 64L * 1024L * 1024L

class DiagnosticsArchiveFileStore(
    private val cacheDir: File,
    private val clock: DiagnosticsArchiveClock,
    private val pcapDirectory: File,
    private val withCaptureStorageLock: suspend ((Long?) -> Unit) -> Unit = { it(null) },
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

    internal fun getRecentPcapFiles(maxFiles: Int = 3): List<File> {
        val pcapDir = pcapDirectory
        if (!pcapDir.exists()) return emptyList()
        return pcapDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "pcap" }
            .sortedByDescending { it.lastModified() }
            .take(maxFiles)
    }

    suspend fun cleanupPcapFiles() =
        withCaptureStorageLock { activeCaptureSetId ->
            val pcapDir = pcapDirectory
            if (!pcapDir.exists()) return@withCaptureStorageLock
            val now = clock.now()
            val activeSetIds = setOfNotNull(activeCaptureSetId?.toString(16)?.padStart(16, '0'))
            pcapDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(PcapActiveFileSuffix) }
                .filterNot { it.belongsToActivePcapSet(activeSetIds) }
                .forEach(::deleteVerified)
            pcapFiles(pcapDir)
                .filterNot { it.belongsToActivePcapSet(activeSetIds) }
                .filter { now - it.lastModified() > PcapRetentionWindowMs }
                .forEach(::deleteVerified)
            var retainedCount = 0
            var retainedBytes = 0L
            pcapFiles(pcapDir)
                .filterNot { it.belongsToActivePcapSet(activeSetIds) }
                .sortedByDescending { it.lastModified() }
                .forEach { file ->
                    val fileBytes = file.length()
                    if (retainedCount < PcapRetentionMaxFiles && retainedBytes <= PcapRetentionMaxBytes - fileBytes) {
                        retainedCount += 1
                        retainedBytes += fileBytes
                    } else {
                        deleteVerified(file)
                    }
                }
        }

    private fun pcapFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "pcap" }

    private fun File.belongsToActivePcapSet(activeSetIds: Set<String>): Boolean =
        name.substringBefore('-') in activeSetIds

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

    private companion object {
        const val PcapActiveFileSuffix = ".pcap.active"
    }
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
