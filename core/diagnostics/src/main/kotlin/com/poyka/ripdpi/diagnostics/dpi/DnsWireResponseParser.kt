package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.ExtendedErrorCodeOption
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.IOException

internal object DnsWireResponseParser {
    fun parse(
        bytes: ByteArray,
        query: ByteArray,
    ): DnsProbeAnswer =
        try {
            responseFacts(DnsMessages.response(bytes, query))
        } catch (_: IOException) {
            dnsFailure(DnsResponseOutcome.MALFORMED)
        } catch (_: IllegalArgumentException) {
            dnsFailure(DnsResponseOutcome.MALFORMED)
        }

    private fun responseFacts(message: Message): DnsProbeAnswer {
        val question = message.question
        require(question.type == Type.A || question.type == Type.AAAA)
        val header = message.header
        val initial =
            DnsResponseSemantics(
                queryType = Type.string(question.type),
                outcome = DnsResponseOutcome.NODATA,
                rcode = message.rcode,
                truncated = header.getFlag(Flags.TC.toInt()),
                authoritative = header.getFlag(Flags.AA.toInt()),
                recursionAvailable = header.getFlag(Flags.RA.toInt()),
                authenticatedData = header.getFlag(Flags.AD.toInt()),
            )
        if (initial.truncated == true) {
            return DnsProbeAnswer(response = initial.copy(outcome = DnsResponseOutcome.TRUNCATED))
        }
        val chain = DnsMessages.answerChain(message)
        val addresses = message.getSection(Section.ANSWER).filter { it.dClass == DClass.IN && it.type == question.type }
        val ips =
            addresses
                .map { record ->
                    when (record) {
                        is ARecord -> record.address.hostAddress.orEmpty()
                        is AAAARecord -> record.address.hostAddress.orEmpty()
                        else -> error("Unexpected address record")
                    }
                }.distinct()
        val authority = message.getSection(Section.AUTHORITY)
        val soa =
            authority.filterIsInstance<SOARecord>().filter {
                it.dClass == DClass.IN &&
                    chain.last().subdomain(it.name)
            }
        val incompleteAnswer =
            ips.isEmpty() && soa.isEmpty() && (chain.size > 1 || authority.any { it.type == Type.NS })
        val outcome =
            if (message.rcode == 0 && incompleteAnswer) {
                DnsResponseOutcome.NOT_OBSERVED
            } else {
                dnsOutcome(message.rcode, ips.isNotEmpty())
            }
        val semantics =
            initial.copy(
                outcome = outcome,
                cnameTargets = chain.drop(1).map { it.toString(true).lowercase() },
                ttlMinSeconds = addresses.minOfOrNull { it.ttl },
                ttlMaxSeconds = addresses.maxOfOrNull { it.ttl },
                hasSoa = soa.isNotEmpty(),
                negativeTtlSeconds =
                    soa.takeIf { outcome.isNegativeDnsAnswer() }?.minOfOrNull {
                        minOf(
                            it.ttl,
                            it.minimum,
                        )
                    },
                extendedDnsErrorCodes =
                    message.opt
                        ?.options
                        .orEmpty()
                        .filterIsInstance<ExtendedErrorCodeOption>()
                        .map { it.errorCode }
                        .distinct()
                        .take(MaxMetadataItems),
            )
        return DnsProbeAnswer(if (outcome == DnsResponseOutcome.ANSWER) ips else emptyList(), semantics)
    }

    private const val MaxMetadataItems = 16
}

internal fun dnsFailure(outcome: DnsResponseOutcome): DnsProbeAnswer =
    DnsProbeAnswer(response = DnsResponseSemantics(outcome = outcome))

internal fun dnsOutcome(
    rcode: Int,
    hasAddresses: Boolean,
): DnsResponseOutcome =
    when (rcode) {
        0 -> if (hasAddresses) DnsResponseOutcome.ANSWER else DnsResponseOutcome.NODATA
        2 -> DnsResponseOutcome.SERVFAIL
        NxdomainRcode -> DnsResponseOutcome.NXDOMAIN
        RefusedRcode -> DnsResponseOutcome.REFUSED
        else -> DnsResponseOutcome.OTHER_RCODE
    }

private const val NxdomainRcode = 3
private const val RefusedRcode = 5

internal fun DnsResponseOutcome.isNegativeDnsAnswer(): Boolean =
    this == DnsResponseOutcome.NODATA || this == DnsResponseOutcome.NXDOMAIN
