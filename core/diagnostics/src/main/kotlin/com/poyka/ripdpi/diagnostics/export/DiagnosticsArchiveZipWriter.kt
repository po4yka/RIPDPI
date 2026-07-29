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
            validateArchiveEntries(entries)
            val temporary =
                File.createTempFile(
                    "${DiagnosticsArchiveFormat.fileNamePrefix}.",
                    ".tmp",
                    target.parentFile,
                )
            restrictToOwner(temporary)
            try {
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    entries.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(entry.bytes)
                        zip.closeEntry()
                    }
                }
                moveAtomically(temporary, target)
                restrictToOwner(target)
            } finally {
                check(!temporary.exists() || temporary.delete()) {
                    "Unable to delete temporary diagnostics archive"
                }
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

        private fun validateArchiveEntries(entries: List<DiagnosticsArchiveEntry>) {
            val names = mutableSetOf<String>()
            entries.forEach { entry ->
                require(entry.name.isNotBlank() && entry.name == entry.name.trim()) {
                    "Diagnostics archive entry name must be non-blank and canonical"
                }
                require('\\' !in entry.name && !entry.name.startsWith('/')) {
                    "Diagnostics archive entry must be a relative POSIX path: ${entry.name}"
                }
                require(
                    entry.name
                        .split('/')
                        .all { segment -> segment.matches(ArchiveEntrySegmentRegex) },
                ) { "Diagnostics archive entry contains an unsafe path segment: ${entry.name}" }
                require(names.add(entry.name)) { "Duplicate diagnostics archive entry: ${entry.name}" }
            }
        }
    }

private val ArchiveEntrySegmentRegex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
