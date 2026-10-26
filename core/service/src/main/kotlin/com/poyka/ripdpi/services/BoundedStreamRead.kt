package com.poyka.ripdpi.services

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads [input] to its end, failing with [IOException] once more than [maxBytes] arrive or
 * [deadlineNanos] (a [System.nanoTime] value), when set, passes. Per-read socket timeouts alone let a
 * slow-drip peer keep a read alive forever.
 */
internal fun readBoundedBytes(
    input: InputStream,
    maxBytes: Long,
    deadlineNanos: Long? = null,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        if (deadlineNanos != null &&
            System.nanoTime() - deadlineNanos > 0
        ) {
            throw IOException("Response body read exceeded its deadline")
        }
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Response body exceeds the download limit")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
