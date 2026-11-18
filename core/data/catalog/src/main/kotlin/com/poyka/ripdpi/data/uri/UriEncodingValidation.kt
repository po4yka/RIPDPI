package com.poyka.ripdpi.data.uri

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** URI and URLDecoder normally replace malformed UTF-8 escapes. Check a link before decoding can change it. */
fun String.hasValidUriEncoding(): Boolean = Charsets.UTF_8.newEncoder().canEncode(this) && hasValidPercentEscapes()

private fun String.hasValidPercentEscapes(): Boolean {
    var escapeIndex = indexOf('%')
    if (escapeIndex < 0) return true

    val decoder = Charsets.UTF_8.newDecoder()
    val bytes = ByteArrayOutputStream()
    return try {
        while (escapeIndex >= 0) {
            bytes.reset()
            do {
                bytes.write(escapedByteAt(escapeIndex))
                escapeIndex += PercentEscapeLength
            } while (getOrNull(escapeIndex) == '%')
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray()))
            escapeIndex = indexOf('%', escapeIndex)
        }
        true
    } catch (_: CharacterCodingException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun String.escapedByteAt(index: Int): Int {
    val high = requireNotNull(getOrNull(index + 1)?.digitToIntOrNull(HexRadix))
    val low = requireNotNull(getOrNull(index + PercentEscapeLength - 1)?.digitToIntOrNull(HexRadix))
    return high * HexRadix + low
}

private const val PercentEscapeLength = 3
private const val HexRadix = 16
