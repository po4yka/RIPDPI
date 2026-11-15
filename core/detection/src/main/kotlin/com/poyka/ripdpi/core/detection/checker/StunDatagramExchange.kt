package com.poyka.ripdpi.core.detection.checker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.TimeUnit

internal fun <T : Any> DatagramSocket.receiveStunResponse(
    timeoutMs: Int,
    decode: (ByteArray) -> T?,
): T? {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
    val buffer = ByteArray(MaxStunDatagramBytes + 1)
    val response = DatagramPacket(buffer, buffer.size)
    while (true) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return null
        soTimeout =
            TimeUnit.NANOSECONDS
                .toMillis(remaining)
                .coerceAtLeast(1)
                .toInt()
        response.length = buffer.size
        receive(response)
        if (response.length <= MaxStunDatagramBytes) {
            decode(buffer.copyOf(response.length))?.let { return it }
        }
    }
}

private const val MaxStunDatagramBytes = 1_500
