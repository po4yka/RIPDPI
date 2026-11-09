package com.poyka.ripdpi.data.diagnostics

import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.GenericEDNSOption
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Header
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.OPTRecord
import org.xbill.DNS.Opcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.IOException
import java.security.SecureRandom

/** DNS wire parsing belongs to dnsjava; network selection belongs to the caller. */
object DnsMessages {
    const val MAX_MESSAGE_BYTES = 65_535

    private val random = SecureRandom()

    init {
        Type.register(Type.HTTPS, "HTTPS", ::StrictHttpsRecord)
        Type.register(Type.A, "A", ::EvidenceARecord)
        Type.register(Type.AAAA, "AAAA", ::EvidenceAaaaRecord)
        Type.register(Type.SOA, "SOA", ::EvidenceSoaRecord)
        Type.register(Type.OPT, "OPT", ::EvidenceOptRecord)
    }

    fun httpsRecord(rdata: ByteArray): HTTPSRecord? =
        Record.newRecord(Name.root, Type.HTTPS, DClass.IN, 0, rdata) as? HTTPSRecord

    fun randomTransactionId(): Int = random.nextInt(MAX_MESSAGE_BYTES + 1)

    fun query(
        hostname: String,
        type: Int = Type.A,
        transactionId: Int = randomTransactionId(),
        payloadSize: Int? = null,
    ): ByteArray {
        val name = hostname.removeSuffix(".")
        require(name.length in 1..MaxNameLength && name.all { it.code < AsciiLimit })
        require(
            name.split('.').all { label ->
                label.length in 1..MaxLabelLength &&
                    label.all { it.isLetterOrDigit() || it in "-_" }
            },
        )
        require(transactionId in 0..MAX_MESSAGE_BYTES)
        val question = Record.newRecord(Name.fromString(name, Name.root), type, DClass.IN)
        val message = Message.newQuery(question).apply { header.id = transactionId }
        val base = message.toWire()
        if (payloadSize != null) {
            require(payloadSize in 0..MAX_MESSAGE_BYTES)
            if (payloadSize >= base.size + SizingOverhead) {
                val filler = GenericEDNSOption(SizingOption, ByteArray(payloadSize - base.size - SizingOverhead))
                message.addRecord(OPTRecord(AdvertisedUdpBytes, 0, 0, 0, filler), Section.ADDITIONAL)
            }
        }
        return message.toWire()
    }

    @Throws(IOException::class)
    fun parseQuery(bytes: ByteArray): Message =
        parse(bytes).also { message ->
            checkWire(
                !message.header.getFlag(Flags.QR.toInt()) && !message.header.getFlag(Flags.TC.toInt()),
                "Invalid DNS query flags",
            )
            checkWire(message.rcode == 0, "Invalid DNS query code")
        }

    @Throws(IOException::class)
    fun response(
        bytes: ByteArray,
        query: ByteArray,
    ): Message {
        val expected = parseQuery(query)
        return parseResponse(bytes).also { message ->
            checkWire(message.header.id == expected.header.id, "Unexpected DNS response id")
            checkWire(message.question == expected.question, "Unexpected DNS response question")
        }
    }

    /** Android rawQuery owns its transaction ID; the caller still owns the expected question. */
    @Throws(IOException::class)
    fun systemResponse(
        bytes: ByteArray,
        hostname: String,
        type: Int,
    ): Message {
        val expected = parseQuery(query(hostname, type, transactionId = 0)).question
        return parseResponse(bytes).also { checkWire(it.question == expected, "Unexpected DNS response question") }
    }

    fun matchesResponse(
        query: ByteArray,
        bytes: ByteArray,
    ): Boolean =
        try {
            !response(bytes, query).header.getFlag(Flags.TC.toInt())
        } catch (_: IOException) {
            false
        }

    /** Follow only the answer aliases rooted at the question, without accepting unrelated records. */
    @Throws(IOException::class)
    fun answerChain(message: Message): List<Name> {
        checkWire(!message.header.getFlag(Flags.TC.toInt()), "Truncated DNS answer")
        val question = message.question
        val records = message.getSection(Section.ANSWER).filter { it.dClass == DClass.IN }
        val aliases = records.filterIsInstance<CNAMERecord>()
        val chain = mutableListOf(question.name)
        while (true) {
            val links = aliases.filter { it.name == chain.last() }
            if (links.isEmpty()) break
            val targets = links.map { it.target }.distinct()
            checkWire(
                targets.size == 1 && targets.single() !in chain && chain.size < MaxAliasNames,
                "Invalid DNS alias chain",
            )
            checkWire(
                records.none {
                    it.name == chain.last() && it.type in setOf(Type.A, Type.AAAA, Type.HTTPS)
                },
                "Conflicting DNS alias",
            )
            chain += targets.single()
        }
        checkWire(aliases.all { it.name in chain.dropLast(1) }, "Unrelated DNS alias")
        checkWire(records.filter { it.type == question.type }.all { it.name == chain.last() }, "Unrelated DNS answer")
        return chain
    }

    private fun parseResponse(bytes: ByteArray): Message =
        parse(bytes).also { checkWire(it.header.getFlag(Flags.QR.toInt()), "Expected a DNS response") }

    private fun parse(bytes: ByteArray): Message {
        checkWire(bytes.size in Header.LENGTH..MAX_MESSAGE_BYTES, "Invalid DNS message size")
        val header = Header(bytes)
        checkWire(
            header.opcode == Opcode.QUERY && header.getCount(Section.QUESTION) == 1,
            "Invalid DNS question count or opcode",
        )
        val message = Message(bytes)
        checkWire(
            message.question != null && message.question.dClass == DClass.IN && message.question.type != 0,
            "Invalid DNS question",
        )
        if (!header.getFlag(Flags.TC.toInt())) {
            checkWire(message.numBytes() == bytes.size, "Trailing DNS data")
            for (section in Section.QUESTION..Section.ADDITIONAL) {
                checkWire(message.getSection(section).size == header.getCount(section), "Incomplete DNS section")
            }
            val options =
                (Section.ANSWER..Section.ADDITIONAL).flatMap { section ->
                    message.getSection(section).filterIsInstance<OPTRecord>().map { section to it }
                }
            checkWire(
                options.size <= 1 &&
                    options.all { (section, record) ->
                        section == Section.ADDITIONAL && record.name == Name.root
                    },
                "Invalid DNS OPT record",
            )
        }
        return message
    }

    private const val AsciiLimit = 128
    private const val MaxNameLength = 253
    private const val MaxLabelLength = 63

    // RFC 7830 6 forbids the Padding option (12) on cleartext DNS, so size probes use a local-use code (RFC 6891 9).
    private const val SizingOption = 65001
    private const val SizingOverhead = 15
    private const val AdvertisedUdpBytes = 1232
    private const val MaxAliasNames = 16
}

private fun checkWire(
    valid: Boolean,
    message: String,
) {
    if (!valid) throw IOException(message)
}
