package com.poyka.ripdpi.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.ARecord
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.IOException

class DnsMessagesTest {
    private val query = hex("123401000001000000000000076578616d706c6503636f6d0000010001")
    private val response =
        hex("123481800001000100000000076578616d706c6503636f6d0000010001c00c000100010000003c0004c0000201")

    @Test
    fun `responses bind all question fields and header identity`() {
        for (offset in listOf(0, 13, query.lastIndex - 2, query.lastIndex)) {
            val changed = response.copyOf().also { it[offset] = (it[offset] + 1).toByte() }
            assertThrows(IOException::class.java) { DnsMessages.response(changed, query) }
        }
        val opcode = response.copyOf().also { it[2] = 0x89.toByte() }
        assertThrows(IOException::class.java) { DnsMessages.response(opcode, query) }
        assertThrows(IOException::class.java) { DnsMessages.response(response + byteArrayOf(0), query) }
    }

    @Test
    fun `questionless stream errors preserve extended rcode without relaxing other callers`() {
        val refused = hex("123481850000000000000000")
        val extendedError = hex("12348180000000000000000100002904d0010000000000")

        assertThrows(IOException::class.java) { DnsMessages.response(refused, query) }
        assertEquals(5, DnsMessages.response(refused, query, allowQuestionlessError = true).rcode)
        assertEquals(16, DnsMessages.response(extendedError, query, allowQuestionlessError = true).rcode)

        val noError = refused.copyOf().also { it[3] = 0x80.toByte() }
        assertThrows(IOException::class.java) { DnsMessages.response(noError, query, allowQuestionlessError = true) }
        val mismatchedId = refused.copyOf().also { it[0] = 0 }
        assertThrows(
            IOException::class.java,
        ) { DnsMessages.response(mismatchedId, query, allowQuestionlessError = true) }
    }

    @Test
    fun `name comparison follows DNS case rules`() {
        val changed = response.copyOf().also { it[13] = 'E'.code.toByte() }
        assertTrue(DnsMessages.matchesResponse(query, changed))
    }

    @Test
    fun `truncated answers cannot be used as addresses or health evidence`() {
        val truncated = response.copyOf().also { it[2] = 0x83.toByte() }
        val message = DnsMessages.response(truncated, query)
        assertTrue(message.header.getFlag(Flags.TC.toInt()))
        assertThrows(IOException::class.java) { DnsMessages.answerChain(message) }
        assertFalse(DnsMessages.matchesResponse(query, truncated))
        assertThrows(IOException::class.java) { DnsMessages.response(truncated.copyOf(12), query) }
    }

    @Test
    fun `unrelated owners and compression loops are rejected`() {
        val unrelated = response.copyOf().also { it[query.size + 1] = 20 }
        assertThrows(IOException::class.java) {
            DnsMessages.answerChain(DnsMessages.response(unrelated, query))
        }
        val loop = response.copyOf().also { it[query.size + 1] = query.size.toByte() }
        assertThrows(IOException::class.java) { DnsMessages.response(loop, query) }
    }

    @Test
    fun `queries preserve trailing root and reject invalid names`() {
        assertTrue(DnsMessages.query("example.com.", transactionId = 0x1234).contentEquals(query))
        for (name in listOf("", "example..com", "example.com..", "éxample.com", "a".repeat(64) + ".com")) {
            assertThrows(Exception::class.java) { DnsMessages.query(name) }
        }
    }

    @Test
    fun `EDNS query advertises a version zero OPT with no sizing option`() {
        val request = DnsMessages.query("example.com", transactionId = 0x1234, edns = true)
        val expected = query.copyOf().also { it[11] = 1 } + hex("00002904d0000000000000")

        assertTrue(request.contentEquals(expected))
        val message = DnsMessages.parseQuery(request)
        assertEquals(0, message.opt.version)
        assertTrue(message.opt.options.isEmpty())
    }

    @Test
    fun `sized queries keep wire length without the cleartext padding option`() {
        for (size in listOf(128, 512, 1232, 4096)) {
            val wire = DnsMessages.query("probe.invalid", transactionId = 9, payloadSize = size)
            assertEquals(size, wire.size)
            val message = DnsMessages.parseQuery(wire)
            assertEquals(1232, message.opt.payloadSize)
            assertEquals(1, message.getSection(Section.ADDITIONAL).size)
            // RFC 6891 9: 65001 is a local/experimental code; 12 (Padding) must not go over cleartext UDP.
            assertEquals(
                65001,
                message.opt.options
                    .single()
                    .code,
            )
        }
    }

    @Test
    fun `ordinary TTL clamping remains local to dnsjava`() {
        val highBitTtl = response.copyOf().also { it[query.size + 6] = 0x80.toByte() }

        val parsedRecord =
            DnsMessages
                .response(highBitTtl, query)
                .getSection(Section.ANSWER)
                .single()
        assertEquals(0L, parsedRecord.ttl)
        val libraryRecord = Message(highBitTtl).getSection(Section.ANSWER).single()
        assertEquals(0L, libraryRecord.ttl)
        assertEquals(ARecord::class.java, libraryRecord.javaClass)
    }

    @Test
    fun `extended OPT control word survives ordinary TTL clamping`() {
        val adjusted = response.copyOf().also { it[11] = 2 }
        val opt = hex("00002904d0820180000000")
        val a = hex("c00c000100010000003c0004c0000201")
        val message = DnsMessages.response(adjusted + opt + a, query)

        assertEquals(0x820, message.rcode)
        assertEquals(1, message.opt.version)
        assertEquals(0x8000, message.opt.flags)
        assertEquals(listOf(Type.OPT, Type.A), message.getSection(Section.ADDITIONAL).map { it.type })
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
