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
    fun parseHttpsRdata(rdata: ByteArray): HttpsRrRecord? = DnsMessages.httpsRecord(rdata)?.toHttpsRrRecord()
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
        return when {
            message.header.getFlag(Flags.TC.toInt()) -> HttpsRrResponse(HttpsRrOutcome.TRUNCATED, rcode = rcode)
            rcode == Rcode.NXDOMAIN -> HttpsRrResponse(HttpsRrOutcome.NXDOMAIN, rcode = rcode)
            rcode != Rcode.NOERROR -> HttpsRrResponse(HttpsRrOutcome.DNS_ERROR, rcode = rcode)
            else -> answerRecords(message)
        }
    }

    private fun answerRecords(message: Message): HttpsRrResponse {
        val chain = DnsMessages.answerChain(message)
        val answers =
            message
                .getSection(Section.ANSWER)
                .filterIsInstance<HTTPSRecord>()
                .filter { it.dClass == DClass.IN }
        val records = answers.map { it.toHttpsRrRecord() ?: throw IOException("Invalid HTTPS record") }
        val authority = message.getSection(Section.AUTHORITY).filter { it.dClass == DClass.IN }
        val aliasOrReferral = chain.size > 1 || authority.any { it.type == Type.NS }
        val terminalSoa = authority.any { it.type == Type.SOA && chain.last().subdomain(it.name) }
        val outcome =
            when {
                answers.isNotEmpty() -> {
                    HttpsRrOutcome.ANSWER
                }

                aliasOrReferral && !terminalSoa -> {
                    HttpsRrOutcome.NOT_OBSERVED
                }

                else -> {
                    HttpsRrOutcome.NODATA
                }
            }
        return HttpsRrResponse(outcome, records, rcode = Rcode.NOERROR)
    }
}

private val TcpServiceKeys = setOf(SVCBBase.ALPN, SVCBBase.NO_DEFAULT_ALPN, SVCBBase.PORT, SVCBBase.ECH)
private val TcpServiceAlpns = setOf("h2", "http/1.1")

private fun HTTPSRecord.hasCompatibleServiceParams(): Boolean {
    val mandatory = getSvcParamValue(SVCBBase.MANDATORY) as? SVCBBase.ParameterMandatory
    if (mandatory != null &&
        (
            mandatory.values.isEmpty() || mandatory.values.distinct().size != mandatory.values.size ||
                mandatory.values.any { it !in TcpServiceKeys || getSvcParamValue(it) == null }
        )
    ) {
        return false
    }
    // RFC 9460 sections 7.1.2 and 9.2 select a transport by ALPN overlap; TLS then offers its full TCP ALPN list.
    val useDefaultAlpn = getSvcParamValue(SVCBBase.NO_DEFAULT_ALPN) == null
    val alpn = getSvcParamValue(SVCBBase.ALPN) as? SVCBBase.ParameterAlpn
    return useDefaultAlpn || alpn?.values?.any { it in TcpServiceAlpns } == true
}

private fun HTTPSRecord.toHttpsRrRecord(): HttpsRrRecord? {
    val target = if (targetName == Name.root) "." else targetName.toString(true)
    return if (svcPriority == 0) {
        HttpsRrRecord(priority = 0, targetName = target, echConfig = null)
    } else {
        serviceModeRecord(target)
    }
}

private fun HTTPSRecord.serviceModeRecord(target: String): HttpsRrRecord? {
    val ech = (getSvcParamValue(SVCBBase.ECH) as? SVCBBase.ParameterEch)?.data
    if (ech != null && !ech.isEchConfigList()) return null
    val owner = name.toString(true)
    val port = (getSvcParamValue(SVCBBase.PORT) as? SVCBBase.ParameterPort)?.port ?: HttpsDefaultPort
    val dialName = if (target == ".") owner else target
    return HttpsRrRecord(
        priority = svcPriority,
        targetName = target,
        echConfig = ech,
        ownerName = owner,
        port = port,
        supportsTcp = port > 0 && dialName.isDnsBindingHost() && hasCompatibleServiceParams(),
    )
}

private fun String.isDnsBindingHost(): Boolean = dpiTargetHostOrNull(this) == lowercase(java.util.Locale.ROOT)

private fun ByteArray.isEchConfigList(): Boolean {
    // RFC 9849 section 4. Validate the list and every entry envelope before handing it to the TLS backend.
    if (size < Short.SIZE_BYTES + EchConfigHeaderBytes) return false
    val input = DNSInput(this)
    return input.readU16() == input.remaining() && input.hasCompleteEchConfigEntries()
}

private fun DNSInput.hasCompleteEchConfigEntries(): Boolean {
    while (remaining() >= EchConfigHeaderBytes) {
        readU16() // Version-specific contents are validated by the TLS backend.
        val length = readU16()
        if (length > remaining()) return false
        readByteArray(length)
    }
    return remaining() == 0
}

private const val EchConfigHeaderBytes = 2 * Short.SIZE_BYTES
