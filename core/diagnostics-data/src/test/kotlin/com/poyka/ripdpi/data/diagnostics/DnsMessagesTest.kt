package com.poyka.ripdpi.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Name
import org.xbill.DNS.Record
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
    fun `padding preserves wire length and advertised datagram size`() {
        for (size in listOf(128, 512, 1232, 4096)) {
            val wire = DnsMessages.query("probe.invalid", transactionId = 9, payloadSize = size)
            assertEquals(size, wire.size)
            val message = DnsMessages.parseQuery(wire)
            assertEquals(1232, message.opt.payloadSize)
            assertEquals(1, message.getSection(Section.ADDITIONAL).size)
            assertEquals(
                12,
                message.opt.options
                    .single()
                    .code,
            )
        }
    }

    @Test
    fun `registered records support standalone library RDATA decoding`() {
        DnsMessages.parseQuery(query)
        val cases =
            mapOf(
                Type.A to hex("c0000201"),
                Type.AAAA to hex("20010db8000000000000000000000001"),
                Type.SOA to hex("00000000000100000002000000030000000400000005"),
                Type.OPT to byteArrayOf(),
            )
        for ((type, rdata) in cases) {
            val record = requireNotNull(Record.newRecord(Name.root, type, DClass.IN, 42, rdata))
            assertEquals(type, record.type)
            assertEquals(42L, record.ttl)
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
