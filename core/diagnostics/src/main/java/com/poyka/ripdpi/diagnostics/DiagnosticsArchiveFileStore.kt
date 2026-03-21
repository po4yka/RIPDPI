package com.poyka.ripdpi.diagnostics

import java.io.File

fun interface DiagnosticsArchiveClock {
    fun now(): Long
}

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
