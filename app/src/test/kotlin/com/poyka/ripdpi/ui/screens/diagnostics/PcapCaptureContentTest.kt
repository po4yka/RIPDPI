package com.poyka.ripdpi.ui.screens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PcapCaptureContentTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `selected capture shows recorded packet bytes`() {
        val directory = temporaryFolder.newFolder("pcap")
        val file = File(directory, "capture.pcap")
        file.writeBytes(
            PcapHeader +
                """
                01 00 00 00 02 00 00 00 14 00 00 00 14 00 00 00
                45 00 00 14 00 00 00 00 40 06 00 00 0a 00 00 01 01 01 01 01
                """.decodeHex(),
        )

        val capture = readCapturePackets(directory, file.name)

        assertEquals(1, capture.packets.size)
        assertEquals("10.0.0.1", capture.packets.single().src)
        assertEquals("1.1.1.1", capture.packets.single().dst)
        assertTrue(formatPcapHex(capture.packets.single().rawBytes!!).contains("45 00 00 14"))
        assertFalse(capture.hasMore)
    }

    @Test
    fun `capture selection stays inside private directory`() {
        val directory = temporaryFolder.newFolder("pcap")
        val outside = temporaryFolder.newFile("secret.pcap")

        assertThrows(IOException::class.java) { readCapturePackets(directory, "../${outside.name}") }
        assertThrows(IOException::class.java) { readCapturePackets(directory, outside.absolutePath) }
        assertThrows(IOException::class.java) { readCapturePackets(directory, "missing.pcap") }
    }

    @Test
    fun `non-initial IPv4 fragment does not invent transport ports`() {
        val directory = temporaryFolder.newFolder("pcap")
        val file = File(directory, "fragment.pcap")
        file.writeBytes(
            PcapHeader +
                """
                01 00 00 00 02 00 00 00 18 00 00 00 18 00 00 00
                45 00 00 18 00 00 00 01 40 06 00 00 0a 00 00 01 01 01 01 01 1f 90 00 50
                """.decodeHex(),
        )

        val packet = readCapturePackets(directory, file.name).packets.single()

        assertEquals("10.0.0.1", packet.src)
        assertEquals("1.1.1.1", packet.dst)
    }

    @Test
    fun `symlink cannot escape capture directory`() {
        val directory = temporaryFolder.newFolder("pcap")
        val outside = temporaryFolder.newFile("secret.pcap")
        Files.createSymbolicLink(File(directory, "link.pcap").toPath(), outside.toPath())

        assertThrows(IOException::class.java) { readCapturePackets(directory, "link.pcap") }
    }

    @Test
    fun `empty capture and invalid header are distinct`() {
        val directory = temporaryFolder.newFolder("pcap")
        File(directory, "empty.pcap").writeBytes(PcapHeader)
        File(directory, "broken.pcap").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(readCapturePackets(directory, "empty.pcap").packets.isEmpty())
        assertThrows(IOException::class.java) { readCapturePackets(directory, "broken.pcap") }
    }

    @Test
    fun `viewer bounds packet list and reports more records`() {
        val directory = temporaryFolder.newFolder("pcap")
        val record = "01 00 00 00 00 00 00 00 01 00 00 00 01 00 00 00 45".decodeHex()
        val file = File(directory, "large.pcap")
        file.outputStream().use { stream ->
            stream.write(PcapHeader)
            repeat(2_001) { stream.write(record) }
        }

        val capture = readCapturePackets(directory, file.name)

        assertEquals(2_000, capture.packets.size)
        assertTrue(capture.hasMore)
    }
}

private val PcapHeader =
    """
    d4 c3 b2 a1 02 00 04 00 00 00 00 00 00 00 00 00
    00 00 01 00 65 00 00 00
    """.decodeHex()

private fun String.decodeHex(): ByteArray =
    filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
