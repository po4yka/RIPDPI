package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages

internal const val MaxDoqBase64Length = (DnsMessages.MAX_MESSAGE_BYTES + 2) / 3 * 4

internal fun ByteArray.unframeDoqMessage(): ByteArray? {
    if (size < DoqLengthPrefixBytes) return null
    val length = ((this[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (this[1].toInt() and ByteMask)
    return if (length >= DnsHeaderBytes && size == DoqLengthPrefixBytes + length) {
        copyOfRange(DoqLengthPrefixBytes, size)
    } else {
        null
    }
}

internal fun ByteArray.frameDoqMessage(): ByteArray? {
    if (size !in DnsHeaderBytes..DnsMessages.MAX_MESSAGE_BYTES) return null
    val frame = ByteArray(size + DoqLengthPrefixBytes)
    frame[0] = (size ushr Byte.SIZE_BITS).toByte()
    frame[1] = size.toByte()
    return copyInto(frame, destinationOffset = DoqLengthPrefixBytes)
}

private const val DnsHeaderBytes = 12
private const val DoqLengthPrefixBytes = 2
private const val ByteMask = 0xFF
