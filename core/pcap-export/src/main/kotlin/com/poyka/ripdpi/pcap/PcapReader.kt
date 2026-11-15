package com.poyka.ripdpi.pcap

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Mirror of `ripdpi_pcap::PcapRecord` (the reader-side type in
 * native/rust/crates/ripdpi-pcap/src/reader.rs).
 *
 * Kept module-internal-typed (not the UI's `PcapPacket`) so
 * `:core:pcap-export` stays UI-agnostic; callers translate to the
 * UI shape at the presentation boundary.
 */
data class PcapReaderRecord(
    val index: Int,
    val tsMicros: Long,
    val origLen: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcapReaderRecord) return false
        return index == other.index &&
            tsMicros == other.tsMicros &&
            origLen == other.origLen &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = index
        r = HASH_SEED * r + tsMicros.hashCode()
        r = HASH_SEED * r + origLen.hashCode()
        r = HASH_SEED * r + bytes.contentHashCode()
        return r
    }

    private companion object {
        private const val HASH_SEED = 31
    }
}

/**
 * Reads classic PCAP 2.4 little-endian raw IP captures with microsecond timestamps.
 *
 * The constructor consumes the 24-byte global header and validates
 * the format before reading records. A partial record throws [EOFException].
 */
class PcapReader(
    private val inputStream: InputStream,
) : AutoCloseable {
    private val snaplen: Long
    private var nextIndex: Int = 1

    init {
        snaplen =
            runCatching {
                val header = ByteArray(GLOBAL_HEADER_LEN)
                inputStream.readFully(header)
                val magic = leU32(header, 0)
                if (magic != MAGIC) {
                    throw IOException("unsupported pcap magic 0x${magic.toUInt().toString(HEX_RADIX)}")
                }
                if (leU32(header, GLOBAL_VERSION_OFFSET) != VERSION_2_4) {
                    throw IOException("unsupported pcap version")
                }
                val parsedSnaplen = leU32(header, GLOBAL_SNAPLEN_OFFSET).toLong() and U32_MASK
                if (parsedSnaplen == 0L) {
                    throw IOException("zero pcap snaplen")
                }
                val linktype = leU32(header, GLOBAL_LINKTYPE_OFFSET)
                if (linktype != LINKTYPE_RAW) {
                    throw IOException("unsupported linktype $linktype, expected $LINKTYPE_RAW")
                }
                parsedSnaplen
            }.getOrElse { failure ->
                runCatching { inputStream.close() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
    }

    /** Read every remaining record into an immutable list. */
    fun read(): ImmutableList<PcapReaderRecord> =
        buildList {
            while (true) {
                val record = readOne() ?: break
                add(record)
            }
        }.toPersistentList()

    /** Read one record, or null at a record boundary at EOF. */
    fun readOne(): PcapReaderRecord? {
        val header = ByteArray(PACKET_HEADER_LEN)
        val firstByte = inputStream.read()
        if (firstByte < 0) return null
        header[0] = firstByte.toByte()
        inputStream.readFully(header, offset = 1)

        val tsSec = leU32(header, 0).toLong() and U32_MASK
        val tsUsec = leU32(header, PACKET_TS_USEC_OFFSET).toLong() and U32_MASK
        val inclLen = leU32(header, PACKET_INCL_LEN_OFFSET).toLong() and U32_MASK
        val origLen = leU32(header, PACKET_ORIG_LEN_OFFSET).toLong() and U32_MASK
        if (tsUsec >= MICROS_PER_SECOND) {
            throw IOException("invalid pcap microsecond fraction")
        }
        if (inclLen > snaplen || inclLen > MAX_CAPTURED_BYTES || inclLen > origLen) {
            throw IOException("invalid pcap captured length $inclLen for snaplen $snaplen and original length $origLen")
        }
        val bytes = ByteArray(inclLen.toInt())
        inputStream.readFully(bytes)
        return PcapReaderRecord(
            index = nextIndex++,
            tsMicros = tsSec * MICROS_PER_SECOND + tsUsec,
            origLen = origLen,
            bytes = bytes,
        )
    }

    override fun close() {
        inputStream.close()
    }

    companion object {
        const val GLOBAL_HEADER_LEN: Int = 24
        const val PACKET_HEADER_LEN: Int = 16
        const val MAGIC: Int = 0xa1b2c3d4.toInt()
        const val LINKTYPE_RAW: Int = 101

        private const val GLOBAL_VERSION_OFFSET = 4
        private const val VERSION_2_4 = 0x0004_0002
        private const val MAX_CAPTURED_BYTES = 262_144L
        private const val GLOBAL_SNAPLEN_OFFSET = 16
        private const val GLOBAL_LINKTYPE_OFFSET = 20
        private const val PACKET_TS_USEC_OFFSET = 4
        private const val PACKET_INCL_LEN_OFFSET = 8
        private const val PACKET_ORIG_LEN_OFFSET = 12
        private const val MICROS_PER_SECOND: Long = 1_000_000L
        private const val U32_MASK: Long = 0xffffffffL
        private const val HEX_RADIX = 16
        private const val BYTE_MASK = 0xff
        private const val BYTE_1_OFFSET = 1
        private const val BYTE_2_OFFSET = 2
        private const val BYTE_3_OFFSET = 3
        private const val SHIFT_8 = 8
        private const val SHIFT_16 = 16
        private const val SHIFT_24 = 24

        fun open(file: File): PcapReader = PcapReader(file.inputStream().buffered())

        private fun leU32(
            buf: ByteArray,
            off: Int,
        ): Int =
            (buf[off].toInt() and BYTE_MASK) or
                ((buf[off + BYTE_1_OFFSET].toInt() and BYTE_MASK) shl SHIFT_8) or
                ((buf[off + BYTE_2_OFFSET].toInt() and BYTE_MASK) shl SHIFT_16) or
                ((buf[off + BYTE_3_OFFSET].toInt() and BYTE_MASK) shl SHIFT_24)

        private fun InputStream.readFully(
            buf: ByteArray,
            offset: Int = 0,
        ) {
            var off = offset
            while (off < buf.size) {
                val n = read(buf, off, buf.size - off)
                if (n <= 0) throw EOFException("incomplete pcap header or packet data")
                off += n
            }
        }
    }
}
