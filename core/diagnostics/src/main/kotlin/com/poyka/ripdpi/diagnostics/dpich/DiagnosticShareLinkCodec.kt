package com.poyka.ripdpi.diagnostics.dpich

import java.util.Base64

private const val HeaderSizeBytes = 8
private const val BitsPerItem = 5
private const val AliveBits = 2
private const val ByteMask = 0xff

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
        val itemBytes = ByteArray((payload.items.size * BitsPerItem + 7) / 8)
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
            bytes[0] = payload.commitHash.toByte()
            writeU24(bytes, offset = 1, value = payload.timestampMinutes)
            writeU24(bytes, offset = 4, value = asn)
            bytes[7] = payload.items.size.toByte()
            itemBytes.copyInto(bytes, destinationOffset = HeaderSizeBytes)
        }
    }

    private fun decodePayload(bytes: ByteArray): ShareLinkPayload {
        val itemCount = bytes[7].toInt() and ByteMask
        val requiredSize = HeaderSizeBytes + (itemCount * BitsPerItem + 7) / 8
        if (bytes.size < requiredSize) {
            throw ShareLinkDecodeError("Share-link item payload is truncated")
        }
        val items =
            List(itemCount) { index ->
                val packed =
                    readBitsLsbFirst(
                        bytes = bytes,
                        startBit = HeaderSizeBytes * 8 + index * BitsPerItem,
                        bitCount = BitsPerItem,
                    )
                val alive = aliveStateOrThrow(packed and 0x03)
                val dpi = dpiStateOrThrow(packed shr AliveBits)
                ShareLinkItem(alive = alive, dpi = dpi)
            }
        return ShareLinkPayload(
            commitHash = bytes[0].toInt() and ByteMask,
            timestampMinutes = readU24(bytes, offset = 1),
            asn = readU24(bytes, offset = 4),
            items = items,
        )
    }

    private fun xorPayloadTail(bytes: ByteArray) {
        for (index in 1 until bytes.size) {
            bytes[index] = (bytes[index].toInt() xor XorKey[(index - 1) % XorKey.size].toInt()).toByte()
        }
    }

    private fun writeU24(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value shr 16).toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = value.toByte()
    }

    private fun readU24(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and ByteMask) shl 16) or
            ((bytes[offset + 1].toInt() and ByteMask) shl 8) or
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
                val byteIndex = absoluteBit / 8
                bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl (absoluteBit % 8))).toByte()
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
            val byte = bytes[absoluteBit / 8].toInt() and ByteMask
            if ((byte and (1 shl (absoluteBit % 8))) != 0) {
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
}
