package com.poyka.ripdpi.diagnostics

import java.io.File
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
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                entries.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(entry.bytes)
                    zip.closeEntry()
                }
            }
        }
    }
