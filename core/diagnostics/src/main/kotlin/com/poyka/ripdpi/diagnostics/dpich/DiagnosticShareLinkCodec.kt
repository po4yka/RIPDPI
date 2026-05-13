package com.poyka.ripdpi.diagnostics.dpich

import java.util.Base64

private const val HeaderSizeBytes = 8
private const val BitsPerByte = 8
private const val BitsPerItem = 5
private const val AliveBits = 2
private const val AliveStateMask = 0x03
private const val ByteMask = 0xff
private const val Unsigned24HighShift = 16
private const val Unsigned24MiddleShift = 8
private const val CommitHashOffset = 0
private const val TimestampOffset = 1
private const val AsnOffset = 4
private const val ItemCountOffset = 7
const val DiagnosticShareLinkMaxFragmentChars = 600

private val XorKey = byteArrayOf(0x72, 0x69, 0x70, 0x64, 0x70, 0x69)

class ShareLinkDecodeError(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object DiagnosticShareLinkCodec {
    fun encode(
        payload: ShareLinkPayload,
        privacyMode: Boolean = false,
    ): String {
        val payloadBytes = encodePayload(payload, privacyMode)
        val encodedBytes = payloadBytes.copyOf()
        xorPayloadTail(encodedBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encodedBytes)
    }

    fun decode(fragment: String): ShareLinkPayload {
        if (fragment.length > DiagnosticShareLinkMaxFragmentChars) {
            throw ShareLinkDecodeError("Share-link fragment is too large")
        }
        val encodedBytes =
            try {
                Base64.getUrlDecoder().decode(fragment)
            } catch (error: IllegalArgumentException) {
                throw ShareLinkDecodeError("Malformed share-link fragment", error)
            }
        if (encodedBytes.size < HeaderSizeBytes) {
            throw ShareLinkDecodeError("Share-link payload is truncated")
        }
        val payloadBytes = encodedBytes.copyOf()
        xorPayloadTail(payloadBytes)
        return decodePayload(payloadBytes)
    }

    internal fun encodePayload(
        payload: ShareLinkPayload,
        privacyMode: Boolean,
    ): ByteArray {
        val itemBytes = ByteArray(payload.items.size.packedItemByteCount())
        payload.items.forEachIndexed { index, item ->
            val value = item.alive.ordinal or (item.dpi.ordinal shl AliveBits)
            writeBitsLsbFirst(
                bytes = itemBytes,
                startBit = index * BitsPerItem,
                bitCount = BitsPerItem,
                value = value,
            )
        }

        val asn = if (privacyMode) 0 else payload.asn
        return ByteArray(HeaderSizeBytes + itemBytes.size).also { bytes ->
            bytes[CommitHashOffset] = payload.commitHash.toByte()
            writeU24(bytes, offset = TimestampOffset, value = payload.timestampMinutes)
            writeU24(bytes, offset = AsnOffset, value = asn)
            bytes[ItemCountOffset] = payload.items.size.toByte()
            itemBytes.copyInto(bytes, destinationOffset = HeaderSizeBytes)
        }
    }

    private fun decodePayload(bytes: ByteArray): ShareLinkPayload {
        val itemCount = bytes[ItemCountOffset].toInt() and ByteMask
        val requiredSize = HeaderSizeBytes + itemCount.packedItemByteCount()
        if (bytes.size < requiredSize) {
            throw ShareLinkDecodeError("Share-link item payload is truncated")
        }
        if (bytes.size > requiredSize) {
            throw ShareLinkDecodeError("Share-link payload has trailing data")
        }
        val items =
            List(itemCount) { index ->
                val packed =
                    readBitsLsbFirst(
                        bytes = bytes,
                        startBit = HeaderSizeBytes * BitsPerByte + index * BitsPerItem,
                        bitCount = BitsPerItem,
                    )
                val alive = aliveStateOrThrow(packed and AliveStateMask)
                val dpi = dpiStateOrThrow(packed shr AliveBits)
                ShareLinkItem(alive = alive, dpi = dpi)
            }
        return ShareLinkPayload(
            commitHash = bytes[CommitHashOffset].toInt() and ByteMask,
            timestampMinutes = readU24(bytes, offset = TimestampOffset),
            asn = readU24(bytes, offset = AsnOffset),
            items = items,
        )
    }
}

private fun xorPayloadTail(bytes: ByteArray) {
    for (index in TimestampOffset until bytes.size) {
        bytes[index] = (bytes[index].toInt() xor XorKey[(index - TimestampOffset) % XorKey.size].toInt()).toByte()
    }
}

private fun writeU24(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    bytes[offset] = (value shr Unsigned24HighShift).toByte()
    bytes[offset + 1] = (value shr Unsigned24MiddleShift).toByte()
    bytes[offset + 2] = value.toByte()
}

private fun readU24(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and ByteMask) shl Unsigned24HighShift) or
        ((bytes[offset + 1].toInt() and ByteMask) shl Unsigned24MiddleShift) or
        (bytes[offset + 2].toInt() and ByteMask)

private fun writeBitsLsbFirst(
    bytes: ByteArray,
    startBit: Int,
    bitCount: Int,
    value: Int,
) {
    repeat(bitCount) { bitOffset ->
        if ((value and (1 shl bitOffset)) != 0) {
            val absoluteBit = startBit + bitOffset
            val byteIndex = absoluteBit / BitsPerByte
            bytes[byteIndex] =
                (bytes[byteIndex].toInt() or (1 shl (absoluteBit % BitsPerByte))).toByte()
        }
    }
}

private fun readBitsLsbFirst(
    bytes: ByteArray,
    startBit: Int,
    bitCount: Int,
): Int {
    var value = 0
    repeat(bitCount) { bitOffset ->
        val absoluteBit = startBit + bitOffset
        val byte = bytes[absoluteBit / BitsPerByte].toInt() and ByteMask
        if ((byte and (1 shl (absoluteBit % BitsPerByte))) != 0) {
            value = value or (1 shl bitOffset)
        }
    }
    return value
}

private fun aliveStateOrThrow(ordinal: Int): AliveState =
    AliveState.entries.getOrNull(ordinal)
        ?: throw ShareLinkDecodeError("Unknown alive state ordinal: $ordinal")

private fun dpiStateOrThrow(ordinal: Int): DpiState =
    DpiState.entries.getOrNull(ordinal)
        ?: throw ShareLinkDecodeError("Unknown DPI state ordinal: $ordinal")

private fun Int.packedItemByteCount(): Int = (this * BitsPerItem + BitsPerByte - 1) / BitsPerByte
