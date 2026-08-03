package com.poyka.ripdpi.pcap

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
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
    val origLen: Int,
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
        r = HASH_SEED * r + origLen
        r = HASH_SEED * r + bytes.contentHashCode()
        return r
    }

    private companion object {
        private const val HASH_SEED = 31
    }
}

/**
 * Classic-libpcap reader that mirrors `ripdpi_pcap::PcapReader` on the
 * Rust side. Tolerant of truncated-tail records after a process-death
 * mid-write (returns null at the next read instead of throwing).
 *
 * The constructor consumes the 24-byte global header and validates
 * magic + linktype. Any subsequent IO error during record reads
 * surfaces as IOException; clean EOF / truncated-tail returns null.
 */
class PcapReader(
    private val inputStream: InputStream,
) : AutoCloseable {
    private val snaplen: Int
    private var nextIndex: Int = 1

    init {
        snaplen =
            runCatching {
                val header = ByteArray(GLOBAL_HEADER_LEN)
                if (!inputStream.readExactly(header)) {
                    throw IOException("incomplete global header")
                }
                val magic = leU32(header, 0)
                if (magic != MAGIC) {
                    throw IOException("unsupported pcap magic 0x${magic.toUInt().toString(HEX_RADIX)}")
                }
                val parsedSnaplen = leU32(header, GLOBAL_SNAPLEN_OFFSET)
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

    /** Read one record, or null at EOF / truncated tail. */
    fun readOne(): PcapReaderRecord? {
        val header = ByteArray(PACKET_HEADER_LEN)
        val record: PcapReaderRecord? =
            if (!inputStream.readExactly(header)) {
                null
            } else {
                val tsSec = leU32(header, 0).toLong() and U32_MASK
                val tsUsec = leU32(header, PACKET_TS_USEC_OFFSET).toLong() and U32_MASK
                val inclLen = leU32(header, PACKET_INCL_LEN_OFFSET)
                if (inclLen < 0 || inclLen > snaplen) {
                    throw IOException("packet inclLen $inclLen exceeds snaplen $snaplen")
                }
                val bytes = ByteArray(inclLen)
                if (bytes.isNotEmpty() && !inputStream.readExactly(bytes)) {
                    null
                } else {
                    val origLen = leU32(header, PACKET_ORIG_LEN_OFFSET)
                    PcapReaderRecord(
                        index = nextIndex++,
                        tsMicros = tsSec * MICROS_PER_SECOND + tsUsec,
                        origLen = origLen,
                        bytes = bytes,
                    )
                }
            }
        return record
    }

    override fun close() {
        inputStream.close()
    }

    companion object {
        const val GLOBAL_HEADER_LEN: Int = 24
        const val PACKET_HEADER_LEN: Int = 16
        const val MAGIC: Int = 0xa1b2c3d4.toInt()
        const val LINKTYPE_RAW: Int = 101

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

        fun open(file: File): PcapReader = PcapReader(file.inputStream())

        private fun leU32(
            buf: ByteArray,
            off: Int,
        ): Int =
            (buf[off].toInt() and BYTE_MASK) or
                ((buf[off + BYTE_1_OFFSET].toInt() and BYTE_MASK) shl SHIFT_8) or
                ((buf[off + BYTE_2_OFFSET].toInt() and BYTE_MASK) shl SHIFT_16) or
                ((buf[off + BYTE_3_OFFSET].toInt() and BYTE_MASK) shl SHIFT_24)

        private fun InputStream.readExactly(buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }
    }
}
