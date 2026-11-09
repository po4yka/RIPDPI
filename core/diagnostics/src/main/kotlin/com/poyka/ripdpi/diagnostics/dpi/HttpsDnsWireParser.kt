package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import org.xbill.DNS.DClass
import org.xbill.DNS.DNSInput
import org.xbill.DNS.Flags
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.IOException

object Rfc9460HttpsRecordCodec {
    fun parseHttpsRdata(rdata: ByteArray): HttpsRrRecord? =
        DnsMessages.httpsRecord(rdata)?.takeIf { it.hasCompatibleServiceParams() }?.toHttpsRrRecord()
}

internal object HttpsDnsWireParser {
    fun parseResponse(
        bytes: ByteArray,
        query: ByteArray,
    ): List<HttpsRrRecord> = parseSafely { DnsMessages.response(bytes, query) }

    fun parseSystemResponse(
        bytes: ByteArray,
        host: String,
    ): List<HttpsRrRecord> = parseSafely { DnsMessages.systemResponse(bytes, host, Type.HTTPS) }

    private fun parseSafely(message: () -> Message): List<HttpsRrRecord> =
        try {
            records(message())
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    private fun records(message: Message): List<HttpsRrRecord> {
        if (message.rcode != 0 || message.header.getFlag(Flags.TC.toInt()) || message.question.type != Type.HTTPS) {
            return emptyList()
        }
        DnsMessages.answerChain(message)
        return message
            .getSection(Section.ANSWER)
            .filterIsInstance<HTTPSRecord>()
            .filter { it.dClass == DClass.IN && it.hasCompatibleServiceParams() }
            .map { it.toHttpsRrRecord() ?: throw IOException("Invalid HTTPS record") }
    }
}

private fun HTTPSRecord.hasCompatibleServiceParams(): Boolean =
    when {
        svcPriority == 0 -> {
            true
        }

        getSvcParamValue(SVCBBase.NO_DEFAULT_ALPN) != null && getSvcParamValue(SVCBBase.ALPN) == null -> {
            false
        }

        else -> {
            val mandatory = getSvcParamValue(SVCBBase.MANDATORY) as? SVCBBase.ParameterMandatory
            mandatory?.values?.let { keys ->
                keys.isNotEmpty() && keys.distinct().size == keys.size &&
                    keys.all { key -> key in SVCBBase.ALPN..SVCBBase.IPV6HINT && getSvcParamValue(key) != null }
            } ?: true
        }
    }

private fun HTTPSRecord.toHttpsRrRecord(): HttpsRrRecord? {
    val ech = (getSvcParamValue(SVCBBase.ECH) as? SVCBBase.ParameterEch)?.data
    if (ech != null) {
        // ECHConfigList has a non-empty, length-prefixed payload (RFC 9460 section 7.5).
        if (ech.size <= Short.SIZE_BYTES || DNSInput(ech).readU16() != ech.size - Short.SIZE_BYTES) return null
    }
    return HttpsRrRecord(
        priority = svcPriority,
        targetName = if (targetName == Name.root) "." else targetName.toString(true),
        echConfig = ech.takeIf { svcPriority != 0 },
    )
}
