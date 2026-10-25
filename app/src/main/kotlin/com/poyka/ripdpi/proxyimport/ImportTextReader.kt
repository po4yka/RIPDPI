package com.poyka.ripdpi.proxyimport

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/** U+FEFF, which some editors put at the start of a UTF-8 file and the `.conf` parser does not trim. */
internal val ByteOrderMark: String = Char(0xFEFF).toString()

/** Largest shared or picked import file RIPDPI reads; real `.conf` files and keys are a few KiB. */
internal const val MaxImportFileBytes = PendingProxyImportStore.MaxAmneziaWgConfBytes

/**
 * Reads a picked or shared import file as text. Blocking: call it off the main thread.
 * Returns `null` when the file cannot be opened or read, is larger than [MaxImportFileBytes],
 * or is not UTF-8 text.
 */
internal fun readImportText(
    resolver: ContentResolver,
    uri: Uri,
): String? =
    try {
        resolver.openInputStream(uri)?.use(::readAtMostLimit)?.let(::decodeImportText)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

/** Reads up to one byte past [MaxImportFileBytes], so an oversized file is detectable. */
private fun readAtMostLimit(input: InputStream): ByteArray {
    val buffer = ByteArray(MaxImportFileBytes + 1)
    var total = 0
    while (total < buffer.size) {
        val read = input.read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}

/**
 * Decodes import file bytes as text. Returns `null` for an oversized or non-UTF-8 (binary)
 * file; strips a UTF-8 byte-order mark, which the `.conf` parser does not trim.
 */
internal fun decodeImportText(bytes: ByteArray): String? {
    if (bytes.size > MaxImportFileBytes) return null
    val text = String(bytes, Charsets.UTF_8).removePrefix(ByteOrderMark)
    return text.takeUnless { '�' in it }
}
