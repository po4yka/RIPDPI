package com.poyka.ripdpi.diagnostics.export

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class DiagnosticsArchiveZipWriter
    @Inject
    constructor() {
        internal fun write(
            target: OutputStream,
            entries: List<DiagnosticsArchiveEntry>,
        ) {
            validateArchiveEntries(entries)
            ZipOutputStream(target.buffered()).use { zip ->
                entries.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(entry.bytes)
                    zip.closeEntry()
                }
            }
        }

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
                copyCreateNew(temporary, target)
            } finally {
                check(!temporary.exists() || temporary.delete()) {
                    "Unable to delete temporary diagnostics archive"
                }
            }
        }

        private fun copyCreateNew(
            source: File,
            target: File,
        ) {
            var targetCreated = false
            val copyResult =
                runCatching {
                    Files
                        .newOutputStream(
                            target.toPath(),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        ).use { output ->
                            targetCreated = true
                            source.inputStream().buffered().use { input -> input.copyTo(output) }
                        }
                    restrictToOwner(target)
                }
            copyResult.exceptionOrNull()?.let { error ->
                if (targetCreated) {
                    runCatching { Files.deleteIfExists(target.toPath()) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                throw error
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
