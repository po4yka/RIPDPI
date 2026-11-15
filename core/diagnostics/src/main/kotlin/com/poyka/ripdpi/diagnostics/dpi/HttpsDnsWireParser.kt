package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import org.xbill.DNS.DClass
import org.xbill.DNS.DNSInput
import org.xbill.DNS.Flags
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
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
    ): HttpsRrResponse = parseSafely { DnsMessages.response(bytes, query) }

    fun parseSystemResponse(
        bytes: ByteArray,
        host: String,
    ): HttpsRrResponse = parseSafely { DnsMessages.systemResponse(bytes, host, Type.HTTPS) }

    private fun parseSafely(message: () -> Message): HttpsRrResponse =
        try {
            records(message())
        } catch (_: IOException) {
            HttpsRrResponse(HttpsRrOutcome.MALFORMED)
        } catch (_: IllegalArgumentException) {
            HttpsRrResponse(HttpsRrOutcome.MALFORMED)
        }

    private fun records(message: Message): HttpsRrResponse {
        require(message.question.type == Type.HTTPS)
        val rcode = message.rcode
        if (message.header.getFlag(Flags.TC.toInt())) return HttpsRrResponse(HttpsRrOutcome.TRUNCATED, rcode = rcode)
        if (rcode != Rcode.NOERROR) {
            val outcome = if (rcode == Rcode.NXDOMAIN) HttpsRrOutcome.NXDOMAIN else HttpsRrOutcome.DNS_ERROR
            return HttpsRrResponse(outcome, rcode = rcode)
        }
        val chain = DnsMessages.answerChain(message)
        val answers =
            message
                .getSection(Section.ANSWER)
                .filterIsInstance<HTTPSRecord>()
                .filter { it.dClass == DClass.IN }
        val records =
            answers
                .filter { it.hasCompatibleServiceParams() }
                .map { it.toHttpsRrRecord() ?: throw IOException("Invalid HTTPS record") }
        val authority = message.getSection(Section.AUTHORITY).filter { it.dClass == DClass.IN }
        val outcome =
            when {
                answers.isNotEmpty() -> {
                    HttpsRrOutcome.ANSWER
                }

                (chain.size > 1 || authority.any { it.type == Type.NS }) &&
                    authority.none { it.type == Type.SOA && chain.last().subdomain(it.name) } -> {
                    HttpsRrOutcome.NOT_OBSERVED
                }

                else -> {
                    HttpsRrOutcome.NODATA
                }
            }
        return HttpsRrResponse(outcome, records, rcode)
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
