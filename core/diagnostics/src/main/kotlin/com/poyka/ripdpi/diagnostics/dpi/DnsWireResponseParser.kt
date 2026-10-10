package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import java.net.InetAddress

internal object DnsWireResponseParser {
    fun parse(
        bytes: ByteArray,
        query: ByteArray,
    ): DnsProbeAnswer =
        parseSafely {
            val expected = DnsPacketReader(query)
            require(expected.u16(QuestionCountOffset) == 1)
            parsePacket(bytes, expected.u16(0), expected.question())
        }

    fun parseLegacy(
        bytes: ByteArray,
        transactionId: Int,
    ): DnsProbeAnswer = parseSafely { parsePacket(bytes, transactionId, DnsPacketReader(bytes).question()) }

    private fun parseSafely(block: () -> DnsProbeAnswer): DnsProbeAnswer =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            dnsFailure(DnsResponseOutcome.MALFORMED)
        } catch (_: IndexOutOfBoundsException) {
            dnsFailure(DnsResponseOutcome.MALFORMED)
        }

    private fun parsePacket(
        bytes: ByteArray,
        transactionId: Int,
        expected: DnsQuestion,
    ): DnsProbeAnswer {
        val reader = DnsPacketReader(bytes)
        val flags = reader.u16(2)
        require(reader.u16(0) == transactionId && flags and ResponseFlag != 0 && flags and OpcodeMask == 0)
        require(
            reader.u16(QuestionCountOffset) == 1 && reader.question() == expected && expected.recordClass == InClass,
        )
        val semantics =
            DnsResponseSemantics(
                queryType = if (expected.type == AaaaType) "AAAA" else "A",
                outcome = DnsResponseOutcome.NODATA,
                rcode = flags and RcodeMask,
                truncated = flags and TruncatedFlag != 0,
                authoritative = flags and AuthoritativeFlag != 0,
                recursionAvailable = flags and RecursionAvailableFlag != 0,
                authenticatedData = flags and AuthenticatedDataFlag != 0,
            )
        require(expected.type == AType || expected.type == AaaaType)
        if (semantics.truncated ==
            true
        ) {
            return DnsProbeAnswer(response = semantics.copy(outcome = DnsResponseOutcome.TRUNCATED))
        }
        val records =
            buildList {
                repeat(
                    SectionCount,
                ) { section -> repeat(reader.u16(AnswerCountOffset + section * 2)) { add(reader.record(section)) } }
            }
        require(reader.offset == bytes.size)
        return responseFacts(reader, expected, records, semantics)
    }

    private fun responseFacts(
        reader: DnsPacketReader,
        question: DnsQuestion,
        records: List<DnsResourceRecord>,
        initial: DnsResponseSemantics,
    ): DnsProbeAnswer {
        val answerRecords = records.filter { it.section == 0 && it.recordClass == InClass }
        val chain = cnameChain(reader, question.name, answerRecords)
        val addresses = answerRecords.filter { it.type == question.type }
        require(addresses.all { it.owner == chain.last() })
        val ips = addresses.map { address(reader, it) }.distinct()
        val soa =
            records
                .filter { it.type == SoaType && it.section == 1 && it.recordClass == InClass }
                .filter { it.owner.isEmpty() || chain.last() == it.owner || chain.last().endsWith(".${it.owner}") }
        val opt = records.filter { it.type == OptType }
        require(opt.size <= 1 && opt.all { it.section == 2 && it.owner.isEmpty() })
        val rcode =
            (initial.rcode ?: 0) or (((opt.firstOrNull()?.ttl ?: 0) shr ExtendedRcodeShift).toInt() shl RcodeBits)
        val incompleteAnswer = ips.isEmpty() && soa.isEmpty() && unresolvedAliasOrReferral(chain, records)
        val outcome =
            if (rcode == 0 && incompleteAnswer) {
                DnsResponseOutcome.NOT_OBSERVED
            } else {
                dnsOutcome(rcode, ips.isNotEmpty())
            }
        val semantics =
            initial.copy(
                outcome = outcome,
                rcode = rcode,
                cnameTargets = chain.drop(1),
                ttlMinSeconds = addresses.minOfOrNull { it.ttl },
                ttlMaxSeconds = addresses.maxOfOrNull { it.ttl },
                hasSoa = soa.isNotEmpty(),
                negativeTtlSeconds =
                    soa
                        .takeIf { outcome.isNegativeDnsAnswer() }
                        ?.minOfOrNull { negativeTtl(reader, it) },
                extendedDnsErrorCodes =
                    opt
                        .flatMap {
                            extendedErrors(
                                reader,
                                it,
                            )
                        }.distinct()
                        .take(MaxMetadataItems),
            )
        return DnsProbeAnswer(if (semantics.outcome == DnsResponseOutcome.ANSWER) ips else emptyList(), semantics)
    }

    private fun cnameChain(
        reader: DnsPacketReader,
        name: String,
        records: List<DnsResourceRecord>,
    ): List<String> {
        val aliases = records.filter { it.type == CnameType }
        val chain = mutableListOf(name)
        while (true) {
            val links = aliases.filter { it.owner == chain.last() }
            if (links.isEmpty()) break
            val targets =
                links
                    .map {
                        reader.offset = it.start
                        reader.name().also { _ -> require(reader.offset == it.start + it.length) }
                    }.distinct()
            require(targets.size == 1 && targets.single() !in chain && chain.size <= MaxMetadataItems)
            require(records.none { it.owner == chain.last() && (it.type == AType || it.type == AaaaType) })
            chain += targets.single()
        }
        require(aliases.all { it.owner in chain.dropLast(1) })
        return chain
    }

    private fun address(
        reader: DnsPacketReader,
        record: DnsResourceRecord,
    ): String {
        val size = if (record.type == AType) Ipv4Bytes else Ipv6Bytes
        require(record.length == size)
        return InetAddress
            .getByAddress(
                reader.bytes.copyOfRange(record.start, record.start + size),
            ).hostAddress
            .orEmpty()
    }

    private fun negativeTtl(
        reader: DnsPacketReader,
        record: DnsResourceRecord,
    ): Long {
        reader.offset = record.start
        reader.name()
        reader.name()
        require(reader.offset + SoaNumbersLength == record.start + record.length)
        return minOf(record.ttl, reader.u32(reader.offset + SoaMinimumOffset))
    }

    private fun extendedErrors(
        reader: DnsPacketReader,
        record: DnsResourceRecord,
    ): List<Int> {
        var offset = record.start
        val codes = mutableListOf<Int>()
        while (offset < record.start + record.length) {
            require(offset + OptionHeaderLength <= record.start + record.length)
            val code = reader.u16(offset)
            val length = reader.u16(offset + 2)
            offset += OptionHeaderLength
            require(offset + length <= record.start + record.length)
            if (code == ExtendedDnsErrorOption) {
                require(length >= 2)
                if (codes.size < MaxMetadataItems) codes += reader.u16(offset)
            }
            offset += length
        }
        return codes
    }

    private fun unresolvedAliasOrReferral(
        chain: List<String>,
        records: List<DnsResourceRecord>,
    ): Boolean = chain.size > 1 || records.any { it.section == 1 && it.type == 2 }

    private const val QuestionCountOffset = 4
    private const val AnswerCountOffset = 6
    private const val SectionCount = 3
    private const val RcodeBits = 4
    private const val OptionHeaderLength = 4
    private const val ResponseFlag = 0x8000
    private const val OpcodeMask = 0x7800
    private const val RcodeMask = 15
    private const val TruncatedFlag = 0x0200
    private const val AuthoritativeFlag = 0x0400
    private const val RecursionAvailableFlag = 0x0080
    private const val AuthenticatedDataFlag = 0x0020
    private const val InClass = 1
    private const val AType = 1
    private const val AaaaType = 28
    private const val CnameType = 5
    private const val SoaType = 6
    private const val OptType = 41
    private const val ExtendedRcodeShift = 24
    private const val ExtendedDnsErrorOption = 15
    private const val MaxMetadataItems = 16
    private const val Ipv4Bytes = 4
    private const val Ipv6Bytes = 16
    private const val SoaNumbersLength = 20
    private const val SoaMinimumOffset = 16
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
