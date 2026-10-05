package com.poyka.ripdpi.diagnostics.export

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal data class PreparedExportContent(
    val byteCount: Long,
    val sha256: String,
    val summary: String,
    val entryNames: List<String>,
)

/** Preview reads the final file, after every typed/correlation redactor and ZIP packaging step. */
internal object PreparedDiagnosticsExportInspector {
    private const val LogExcerptCodePoints = 4096
    private const val CopyBufferBytes = 8192

    fun inspect(
        file: File,
        purpose: DiagnosticsExportPurpose,
    ): PreparedExportContent {
        check(file.isFile) { "Prepared export file is unavailable" }
        val digest = sha256(file)
        if (purpose == DiagnosticsExportPurpose.SaveLogs) {
            val text = file.readText(Charsets.UTF_8)
            val excerpt =
                text.substring(
                    0,
                    text.offsetByCodePoints(0, minOf(LogExcerptCodePoints, text.codePointCount(0, text.length))),
                )
            return PreparedExportContent(file.length(), digest, excerpt, listOf(file.name))
        }
        return ZipFile(file).use { zip ->
            val names = mutableListOf<String>()
            var summary: String? = null
            zip.entries().asSequence().forEach { entry ->
                check(!entry.isDirectory && !entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) {
                    "Invalid prepared ZIP entry"
                }
                check(names.addUnique(entry.name)) { "Duplicate prepared ZIP entry" }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val crc = CRC32().apply { update(bytes) }.value
                check(entry.size == bytes.size.toLong() && entry.crc == crc) { "Prepared ZIP entry integrity mismatch" }
                if (entry.name == "summary.txt") summary = bytes.toString(Charsets.UTF_8)
            }
            PreparedExportContent(
                file.length(),
                digest,
                requireNotNull(summary) { "Prepared ZIP summary is unavailable" },
                names,
            )
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CopyBufferBytes)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MutableList<String>.addUnique(name: String): Boolean {
        if (name in this) return false
        add(name)
        return true
    }
}
