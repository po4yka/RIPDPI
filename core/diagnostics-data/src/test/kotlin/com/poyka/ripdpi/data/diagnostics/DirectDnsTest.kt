package com.poyka.ripdpi.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DirectDnsTest {
    @Test
    fun `query packets cannot supply resolution answers`() {
        val response =
            hex(
                "123401000001000100000000" +
                    "076578616d706c6503636f6d0000010001" +
                    "c00c000100010000003c0004c0000201",
            )
        assertThrows(IOException::class.java) {
            DnsMessages.response(response, DnsMessages.query("example.com", transactionId = 0x1234))
        }
    }

    @Test
    fun `build query emits RFC1035 A record wire format`() {
        val bytes =
            DnsMessages.query(
                hostname = "example.com",
                type = org.xbill.DNS.Type.A,
                transactionId = 0x1234,
            )

        assertEquals(
            "123401000001000000000000076578616d706c6503636f6d0000010001",
            bytes.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `parse response handles compressed A record answer`() {
        val response =
            hex(
                "123481800001000100000000" +
                    "076578616d706c6503636f6d0000010001" +
                    "c00c000100010000003c0004c0000201",
            )

        val addresses =
            DnsMessages
                .response(response, DnsMessages.query("example.com", transactionId = 0x1234))
                .getSection(org.xbill.DNS.Section.ANSWER)
                .filterIsInstance<org.xbill.DNS.ARecord>()
                .map { it.address }

        assertEquals(listOf("192.0.2.1"), addresses.map { it.hostAddress })
    }

    private fun hex(value: String): ByteArray =
        value
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
