package com.poyka.ripdpi.diagnostics.dpi

internal class DnsPacketReader(
    val bytes: ByteArray,
) {
    var offset = HeaderLength

    fun u16(at: Int): Int = (bytes[at].toInt() and ByteMask shl Byte.SIZE_BITS) or (bytes[at + 1].toInt() and ByteMask)

    fun u32(at: Int): Long = (u16(at).toLong() shl Short.SIZE_BITS) or u16(at + 2).toLong()

    fun name(): String {
        var cursor = offset
        var end: Int? = null
        val visited = mutableSetOf<Int>()
        val labels = mutableListOf<String>()
        while (true) {
            require(visited.add(cursor))
            val length = bytes[cursor].toInt() and ByteMask
            when {
                length == 0 -> {
                    offset = end ?: (cursor + 1)
                    return labels.joinToString(".").also { require(it.length <= MaxNameLength) }.lowercase()
                }

                length and PointerMask == PointerMask -> {
                    val pointer = u16(cursor) and PointerOffsetMask
                    require(pointer < cursor)
                    end = end ?: (cursor + 2)
                    cursor = pointer
                }

                else -> {
                    require(length in 1..MaxLabelLength && cursor + length < bytes.size)
                    val label = bytes.copyOfRange(cursor + 1, cursor + 1 + length)
                    require(label.all { it.toInt() in MinPrintable..MaxPrintable && it != '.'.code.toByte() })
                    labels += label.toString(Charsets.US_ASCII)
                    require(labels.sumOf { it.length + 1 } <= MaxNameLength + 1)
                    cursor += length + 1
                }
            }
        }
    }

    fun question(): DnsQuestion {
        val name = name()
        val question = DnsQuestion(name, u16(offset), u16(offset + 2))
        offset += QuestionTrailerLength
        return question
    }

    fun record(section: Int): DnsResourceRecord {
        val owner = name()
        val record =
            DnsResourceRecord(
                owner,
                u16(offset),
                u16(offset + 2),
                u32(offset + TtlOffset),
                section,
                offset + RecordHeaderLength,
                u16(offset + LengthOffset),
            )
        offset += RecordHeaderLength + record.length
        require(offset <= bytes.size)
        return record
    }

    companion object {
        const val HeaderLength = 12
        private const val TtlOffset = 4
        private const val LengthOffset = 8
        private const val ByteMask = 255
        private const val PointerMask = 192
        private const val PointerOffsetMask = 16383
        private const val MaxNameLength = 253
        private const val MaxLabelLength = 63
        private const val MinPrintable = 33
        private const val MaxPrintable = 126
        private const val QuestionTrailerLength = 4
        private const val RecordHeaderLength = 10
    }
}

internal data class DnsQuestion(
    val name: String,
    val type: Int,
    val recordClass: Int,
)

internal data class DnsResourceRecord(
    val owner: String,
    val type: Int,
    val recordClass: Int,
    val ttl: Long,
    val section: Int,
    val start: Int,
    val length: Int,
)
