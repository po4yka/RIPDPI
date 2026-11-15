package com.poyka.ripdpi.pcap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class PcapReaderTest {
    @Test
    fun globalHeader_validatesMagicAndLinktype() {
        // Wrong magic.
        val wrongMagic =
            byteArrayOfInts(
                0xde,
                0xad,
                0xbe,
                0xef, // magic (invalid)
                0x02,
                0x00,
                0x04,
                0x00, // version
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0xff,
                0xff,
                0x00,
                0x00,
                0x65,
                0x00,
                0x00,
                0x00,
            )
        assertThrows(IOException::class.java) { PcapReader(ByteArrayInputStream(wrongMagic)) }

        // Right magic, wrong linktype (1 = Ethernet, we require 101 = RAW).
        val wrongLinktype = headerBytes(linktype = 1)
        assertThrows(IOException::class.java) { PcapReader(ByteArrayInputStream(wrongLinktype)) }
    }

    @Test
    fun globalHeader_rejectsInvalidVersionAndZeroSnaplen() {
        val wrongVersion = headerBytes().also { it[6] = 9 }
        assertThrows(IOException::class.java) { PcapReader(ByteArrayInputStream(wrongVersion)) }
        assertThrows(IOException::class.java) { PcapReader(ByteArrayInputStream(headerBytes(snaplen = 0))) }
    }

    @Test
    fun globalHeader_validationFailure_closesInputStream() {
        val input = CloseTrackingInputStream(headerBytes(linktype = 1))

        assertThrows(IOException::class.java) { PcapReader(input) }

        assertTrue("header validation failures must close the owned stream", input.closed)
    }

    @Test
    fun globalHeader_validationFailure_preservesFailureWhenCloseFails() {
        val input =
            object : ByteArrayInputStream(headerBytes(linktype = 1)) {
                override fun close(): Unit = throw IOException("close failed")
            }

        val failure = assertThrows(IOException::class.java) { PcapReader(input) }

        assertTrue(failure.message.orEmpty().contains("unsupported linktype"))
        assertEquals("close failed", failure.suppressed.single().message)
    }

    @Test
    fun readsZeroPackets_fromEmptyFileBody() {
        val bytes = headerBytes()
        val reader = PcapReader(ByteArrayInputStream(bytes))
        val records = reader.read()
        assertTrue("expected no records, got ${records.size}", records.isEmpty())
    }

    @Test
    fun readsOnePacket_writtenByWriterContract() {
        val payload = byteArrayOfInts(0x45, 0x00, 0x00, 0x14) // truncated IPv4 sniff
        val tsSec = 0x6645_0001L
        val tsUsec = 0x0001_e240L // 123,456
        val pcap =
            headerBytes() +
                packetRecord(
                    tsSec = tsSec,
                    tsUsec = tsUsec,
                    inclLen = payload.size,
                    origLen = payload.size,
                    payload = payload,
                )
        val reader = PcapReader(ByteArrayInputStream(pcap))
        val records = reader.read()
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals(1, r.index)
        assertEquals(tsSec * 1_000_000L + tsUsec, r.tsMicros)
        assertEquals(payload.size.toLong(), r.origLen)
        assertArrayEquals(payload, r.bytes)
    }

    @Test
    fun distinguishesTruncatedHeaderFromCleanEof() {
        // Full header + one good packet + a partial packet header (8 bytes).
        val payload = byteArrayOfInts(0x45, 0x00, 0x00, 0x14)
        val full =
            headerBytes() +
                packetRecord(
                    tsSec = 1L,
                    tsUsec = 0L,
                    inclLen = payload.size,
                    origLen = payload.size,
                    payload = payload,
                )
        val truncatedTail = byteArrayOfInts(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val bytes = full + truncatedTail
        val reader = PcapReader(ByteArrayInputStream(bytes))
        val first = reader.readOne()
        assertNotNull("expected first record to parse", first)
        assertThrows(EOFException::class.java) { reader.readOne() }
    }

    @Test
    fun rejectsTruncatedPayload() {
        // Valid 16-byte record header claiming a 4-byte payload, but the
        // payload bytes are missing.
        val recordHeaderOnly =
            byteArrayOfInts(
                0x01,
                0x00,
                0x00,
                0x00, // ts_sec
                0x00,
                0x00,
                0x00,
                0x00, // ts_usec
                0x04,
                0x00,
                0x00,
                0x00, // incl_len = 4
                0x04,
                0x00,
                0x00,
                0x00, // orig_len = 4
            )
        val bytes = headerBytes() + recordHeaderOnly
        val reader = PcapReader(ByteArrayInputStream(bytes))
        assertThrows(EOFException::class.java) { reader.readOne() }
    }

    @Test
    fun rejectsInvalidRecordFieldsBeforeReadingItsBody() {
        val capturedBytes = 262_145
        val cases =
            listOf(
                headerBytes() + packetRecord(1, 1_000_000, 0, 0, byteArrayOf()),
                headerBytes(snaplen = 1) + packetRecord(1, 0, 2, 2, byteArrayOf()),
                headerBytes() + packetRecord(1, 0, 2, 1, byteArrayOf()),
                headerBytes(snaplen = -1) + packetRecord(1, 0, capturedBytes, capturedBytes, byteArrayOf()),
            )
        cases.forEach { capture ->
            PcapReader(ByteArrayInputStream(capture)).use { reader ->
                val failure = assertThrows(IOException::class.java) { reader.readOne() }
                assertEquals(IOException::class.java, failure.javaClass)
            }
        }
    }

    @Test
    fun interpretsUnsignedSnaplenAndOriginalLength() {
        val capture = headerBytes(snaplen = -1) + packetRecord(1, 999_999, 1, -1, byteArrayOf(0x45))
        PcapReader(ByteArrayInputStream(capture)).use { reader ->
            val record = checkNotNull(reader.readOne())
            assertEquals(4_294_967_295L, record.origLen)
            assertEquals(1_999_999L, record.tsMicros)
        }
    }

    // -- helpers --------------------------------------------------------

    private fun headerBytes(
        linktype: Int = PcapReader.LINKTYPE_RAW,
        snaplen: Int = 65_535,
    ): ByteArray =
        byteArrayOfInts(
            // magic (LE 0xa1b2c3d4)
            0xd4,
            0xc3,
            0xb2,
            0xa1,
            // version_major=2, version_minor=4
            0x02,
            0x00,
            0x04,
            0x00,
            // thiszone, sigfigs
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        ) + leU32(snaplen) + leU32(linktype)

    private class CloseTrackingInputStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun packetRecord(
        tsSec: Long,
        tsUsec: Long,
        inclLen: Int,
        origLen: Int,
        payload: ByteArray,
    ): ByteArray =
        leU32(tsSec.toInt()) +
            leU32(tsUsec.toInt()) +
            leU32(inclLen) +
            leU32(origLen) +
            payload

    private fun leU32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )

    private fun byteArrayOfInts(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }
}
