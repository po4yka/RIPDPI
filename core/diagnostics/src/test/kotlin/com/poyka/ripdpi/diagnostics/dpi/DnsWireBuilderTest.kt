package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireBuilderTest {
    @Test
    fun buildQueryProducesValidARecordWireFormat() {
        val query = DnsWireBuilder.buildQuery("example.com", transactionId = 0x1234)

        assertEquals(0x12.toByte(), query[0])
        assertEquals(0x34.toByte(), query[1])
        assertEquals(0x01.toByte(), query[2])
        assertEquals(0x00.toByte(), query[3])
        assertEquals(0x00.toByte(), query[4])
        assertEquals(0x01.toByte(), query[5])
        assertArrayEquals(byteArrayOf(7, 'e'.code.toByte()), query.copyOfRange(12, 14))
        assertEquals(0x00.toByte(), query[query.lastIndex - 3])
        assertEquals(0x01.toByte(), query[query.lastIndex - 2])
        assertEquals(0x00.toByte(), query[query.lastIndex - 1])
        assertEquals(0x01.toByte(), query[query.lastIndex])
    }

    @Test
    fun parseResponseExtractsIpv4FromValidResponse() {
        val response = dnsResponse(answerIp = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))

        assertEquals(listOf("93.184.216.34"), DnsWireBuilder.parseResponse(response, transactionId = 0x1234))
    }

    @Test
    fun parseResponseReturnsNxdomainOnRcode3() {
        val response =
            byteArrayOf(
                0x12,
                0x34,
                0x81.toByte(),
                0x83.toByte(),
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            ) + question()

        assertEquals(listOf(DnsWireBuilder.NXDOMAIN), DnsWireBuilder.parseResponse(response, transactionId = 0x1234))
    }

    @Test
    fun parseResponseHandlesPointerCompression() {
        val response = dnsResponse(answerIp = byteArrayOf(1, 2, 3, 4), compressedAnswerName = true)

        assertTrue(DnsWireBuilder.parseResponse(response, transactionId = 0x1234).contains("1.2.3.4"))
    }

    private fun dnsResponse(
        answerIp: ByteArray,
        compressedAnswerName: Boolean = true,
    ): ByteArray =
        byteArrayOf(
            0x12,
            0x34,
            0x81.toByte(),
            0x80.toByte(),
            0x00,
            0x01,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
        ) + question() +
            if (compressedAnswerName) {
                byteArrayOf(0xC0.toByte(), 0x0C)
            } else {
                byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
            } +
            byteArrayOf(
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x3C,
                0x00,
                0x04,
            ) + answerIp

    private fun question(): ByteArray =
        byteArrayOf(
            7,
            'e'.code.toByte(),
            'x'.code.toByte(),
            'a'.code.toByte(),
            'm'.code.toByte(),
            'p'.code.toByte(),
            'l'.code.toByte(),
            'e'.code.toByte(),
            3,
            'c'.code.toByte(),
            'o'.code.toByte(),
            'm'.code.toByte(),
            0,
            0x00,
            0x01,
            0x00,
            0x01,
        )
}
