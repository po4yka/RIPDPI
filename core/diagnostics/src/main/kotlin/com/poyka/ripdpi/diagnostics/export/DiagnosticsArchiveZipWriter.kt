package com.poyka.ripdpi.diagnostics.export

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class DiagnosticsArchiveZipWriter
    @Inject
    constructor() {
        internal fun write(
            target: File,
            entries: List<DiagnosticsArchiveEntry>,
        ) {
            val temporary =
                File.createTempFile(
                    "${DiagnosticsArchiveFormat.fileNamePrefix}.",
                    ".tmp",
                    target.parentFile,
                )
            try {
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    entries.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(entry.bytes)
                        zip.closeEntry()
                    }
                }
                moveAtomically(temporary, target)
            } finally {
                temporary.delete()
            }
        }

        private fun moveAtomically(
            source: File,
            target: File,
        ) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
        }
    }
