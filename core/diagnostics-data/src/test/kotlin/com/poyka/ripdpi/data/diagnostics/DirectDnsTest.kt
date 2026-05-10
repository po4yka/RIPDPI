package com.poyka.ripdpi.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectDnsTest {
    @Test
    fun `build query emits RFC1035 A record wire format`() {
        val bytes =
            DnsWireCodec.buildQuery(
                hostname = "example.com",
                recordType = DnsRecordType.A,
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
            DnsWireCodec.parseResponse(
                bytes = response,
                transactionId = 0x1234,
                recordType = DnsRecordType.A,
            )

        assertEquals(listOf("192.0.2.1"), addresses.map { it.hostAddress })
    }

    private fun hex(value: String): ByteArray =
        value
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
