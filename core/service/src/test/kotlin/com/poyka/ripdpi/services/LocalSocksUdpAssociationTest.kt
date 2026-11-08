package com.poyka.ripdpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ProtocolException

class LocalSocksUdpAssociationTest {
    private val peer = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun `domain relay localhost uses the control peer without DNS`() {
        for (name in listOf("localhost", "LOCALHOST.")) {
            val result = associate(domainReply(name))
            assertEquals(peer, result.address)
            assertEquals(5296, result.port)
        }
    }

    @Test
    fun `domain relay numeric literals preserve the address family`() {
        val ipv4 = associate(domainReply("127.0.0.2"))
        assertArrayEquals(byteArrayOf(127, 0, 0, 2), ipv4.address.address)
        val ipv6 = associate(domainReply("::1"))
        assertArrayEquals(ByteArray(15) + byteArrayOf(1), ipv6.address.address)
    }

    @Test
    fun `arbitrary or empty relay names fail instead of using system DNS`() {
        for (name in listOf("relay.example", "", "localhost.invalid", "localhost..")) {
            assertThrows(ProtocolException::class.java) { associate(domainReply(name)) }
        }
    }

    @Test
    fun `invalid header and port zero remain rejected`() {
        for (header in listOf(byteArrayOf(4, 0, 0, 1), byteArrayOf(5, 0, 1, 1), byteArrayOf(5, 1, 0, 1))) {
            assertThrows(ProtocolException::class.java) {
                associate(header + byteArrayOf(127, 0, 0, 1, 20, 0xb0.toByte()))
            }
        }
        assertThrows(ProtocolException::class.java) {
            associate(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
        }
    }

    private fun associate(reply: ByteArray): java.net.InetSocketAddress {
        val output = ByteArrayOutputStream()
        val result =
            openUdpAssociation(peer, DataInputStream(ByteArrayInputStream(reply)), DataOutputStream(output))
        assertArrayEquals(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0), output.toByteArray())
        return result
    }

    private fun domainReply(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        return byteArrayOf(5, 0, 0, 3, bytes.size.toByte()) + bytes + byteArrayOf(20, 0xb0.toByte())
    }
}
