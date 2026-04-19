package com.poyka.ripdpi.diagnostics.export

import java.io.File

fun interface DiagnosticsArchiveClock {
    fun now(): Long
}

private const val PcapRetentionWindowMs = 24L * 60L * 60L * 1000L

class DiagnosticsArchiveFileStore(
    private val cacheDir: File,
    private val clock: DiagnosticsArchiveClock,
) {
    fun cleanup() {
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
            .forEach { it.delete() }

        archiveDirectory
            .listFiles()
            .orEmpty()
            .filter(::isManagedArchive)
            .sortedByDescending { it.lastModified() }
            .drop(DiagnosticsArchiveFormat.maxArchiveFiles)
            .forEach { it.delete() }
    }

    internal fun getRecentPcapFiles(maxFiles: Int = 3): List<File> {
        val pcapDir = File(cacheDir, "diagnostics")
        if (!pcapDir.exists()) return emptyList()
        return pcapDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "pcap" }
            .sortedByDescending { it.lastModified() }
            .take(maxFiles)
    }

    fun cleanupPcapFiles() {
        val pcapDir = File(cacheDir, "diagnostics")
        if (!pcapDir.exists()) return
        val now = clock.now()
        pcapDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "pcap" }
            .filter { now - it.lastModified() > PcapRetentionWindowMs }
            .forEach { it.delete() }
    }

    internal fun createTarget(): DiagnosticsArchiveTarget {
        val createdAt = clock.now()
        val fileName = "${DiagnosticsArchiveFormat.fileNamePrefix}$createdAt.zip"
        val file = File(ensureArchiveDirectory(), fileName)
        return DiagnosticsArchiveTarget(
            file = file,
            fileName = fileName,
            createdAt = createdAt,
        )
    }

    private fun ensureArchiveDirectory(): File =
        File(cacheDir, DiagnosticsArchiveFormat.directoryName).apply {
            mkdirs()
        }

    private fun isManagedArchive(file: File): Boolean =
        file.isFile &&
            file.name.startsWith(DiagnosticsArchiveFormat.fileNamePrefix) &&
            file.extension == "zip"
}
