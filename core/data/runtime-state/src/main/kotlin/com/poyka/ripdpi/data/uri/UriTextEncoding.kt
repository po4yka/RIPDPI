package com.poyka.ripdpi.data.uri

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** URI and URLDecoder normally replace malformed UTF-8 escapes. Check a link before decoding can change it. */
internal fun String.hasValidUriEncoding(): Boolean {
    if (!Charsets.UTF_8.newEncoder().canEncode(this)) return false
    var escapeIndex = indexOf('%')
    if (escapeIndex < 0) return true

    val decoder = Charsets.UTF_8.newDecoder()
    val bytes = ByteArrayOutputStream()
    return try {
        while (escapeIndex >= 0) {
            bytes.reset()
            do {
                val high = getOrNull(escapeIndex + 1)?.digitToIntOrNull(16) ?: return false
                val low = getOrNull(escapeIndex + 2)?.digitToIntOrNull(16) ?: return false
                bytes.write(high * 16 + low)
                escapeIndex += 3
            } while (getOrNull(escapeIndex) == '%')
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray()))
            escapeIndex = indexOf('%', escapeIndex)
        }
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
