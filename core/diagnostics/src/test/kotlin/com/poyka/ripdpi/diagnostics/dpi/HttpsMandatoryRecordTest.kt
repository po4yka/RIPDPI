package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.Section
import org.xbill.DNS.Type

class HttpsMandatoryRecordTest {
    @Test
    fun unknownMandatoryKeyDoesNotDiscardCompatibleAlternative() {
        val records = parseRecords(listOf(SVCBBase.ParameterMandatory(listOf(65400)), unknownParameter()))

        assertEquals(listOf(2), records.map { it.priority })
    }

    @Test
    fun knownMandatoryAndUnknownOptionalKeysKeepPreferredRecord() {
        val records = parseRecords(listOf(SVCBBase.ParameterMandatory(listOf(SVCBBase.ECH)), unknownParameter()))

        assertEquals(listOf(1, 2), records.map { it.priority })
    }

    @Test
    fun noDefaultAlpnRequiresAlpn() {
        assertEquals(listOf(2), parseRecords(listOf(SVCBBase.ParameterNoDefaultAlpn())).map { it.priority })
        assertEquals(
            listOf(1, 2),
            parseRecords(listOf(SVCBBase.ParameterAlpn(listOf("h2")), SVCBBase.ParameterNoDefaultAlpn()))
                .map { it.priority },
        )
    }

    @Test
    fun invalidMandatoryListsDoNotSupplyEch() {
        for (keys in listOf(listOf(SVCBBase.MANDATORY), listOf(SVCBBase.ECH, SVCBBase.ECH))) {
            assertEquals(listOf(2), parseRecords(listOf(SVCBBase.ParameterMandatory(keys))).map { it.priority })
        }
    }

    @Test
    fun emptyMandatoryListDoesNotSupplyEch() {
        assertEquals(listOf(2), parseRecords(listOf(SVCBBase.ParameterMandatory(emptyList()))).map { it.priority })
    }

    private fun unknownParameter() = SVCBBase.ParameterUnknown(65400, byteArrayOf(1))

    private fun parseRecords(params: List<SVCBBase.ParameterBase>): List<HttpsRrRecord> {
        val owner = Name.fromString("fixture.example.")
        val query = Message.newQuery(Record.newRecord(owner, Type.HTTPS, DClass.IN))
        val response = Message(query.header.id)
        response.header.setFlag(Flags.QR.toInt())
        response.addRecord(query.question, Section.QUESTION)
        val ech = SVCBBase.ParameterEch(byteArrayOf(0, 2, 3, 4))
        response.addRecord(HTTPSRecord(owner, DClass.IN, 60, 1, Name.root, params + ech), Section.ANSWER)
        response.addRecord(HTTPSRecord(owner, DClass.IN, 60, 2, Name.root, listOf(ech)), Section.ANSWER)
        return HttpsDnsWireParser.parseResponse(response.toWire(), query.toWire())
    }
}
