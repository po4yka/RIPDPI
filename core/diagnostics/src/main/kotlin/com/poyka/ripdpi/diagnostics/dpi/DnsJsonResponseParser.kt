package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.xbill.DNS.Name
import org.xbill.DNS.TextParseException

internal object DnsJsonResponseParser {
    fun parse(
        payload: String,
        domain: String,
        json: Json,
    ): DnsProbeAnswer {
        require(payload.length <= MaxDnsJsonBodyBytes)
        checkDepth(payload)
        val root = json.parseToJsonElement(payload).jsonObject
        val question = requireNotNull(root["Question"]?.jsonArray?.singleOrNull()?.jsonObject)
        val queryName = dnsName(domain)
        require(question.name() == queryName && question.integer("type") == AType)
        val rcode = root.integer("Status").also { require(it in 0..MaxRcode) }
        val initial =
            DnsResponseSemantics(
                outcome = dnsOutcome(rcode, false),
                rcode = rcode,
                truncated = root.flag("TC"),
                authoritative = root.flag("AA"),
                recursionAvailable = root.flag("RA"),
                authenticatedData = root.flag("AD"),
            )
        if (initial.truncated == true) {
            return DnsProbeAnswer(response = initial.copy(outcome = DnsResponseOutcome.TRUNCATED))
        }
        return projectAnswer(root, queryName, rcode, initial)
    }

    private fun projectAnswer(
        root: JsonObject,
        queryName: String,
        rcode: Int,
        initial: DnsResponseSemantics,
    ): DnsProbeAnswer {
        val records = root.records("Answer").orEmpty()
        val addressRecords = records.filter { it.recordType() == AType }
        val addressOwners = addressRecords.mapTo(mutableSetOf()) { it.name() }
        val chain = aliasChain(queryName, records, addressOwners)
        val terminal = chain.last()
        require(addressOwners.all { it == terminal })
        val addresses = addressRecords.map { it.text("data").also { address -> require(isDnsIpv4(address)) } }
        val ttls = addressRecords.map { it.ttl() }
        val authorities = root.records("Authority")?.groupBy { it.recordType() }
        val negativeTtls =
            authorities
                ?.get(SoaType)
                .orEmpty()
                .map { record -> record.name() to record.negativeTtl() }
                .filter { (owner, _) -> terminal.isWithin(owner) }
                .map { (_, ttl) -> ttl }
        val incompleteAnswer =
            addresses.isEmpty() && negativeTtls.isEmpty() &&
                (chain.size > 1 || authorities?.get(NsType).orEmpty().any { terminal.isWithin(it.name()) })
        val outcome =
            if (rcode == 0 && incompleteAnswer) {
                DnsResponseOutcome.NOT_OBSERVED
            } else {
                dnsOutcome(rcode, addresses.isNotEmpty())
            }
        val semantics =
            initial.copy(
                outcome = outcome,
                cnameTargets = chain.drop(1),
                ttlMinSeconds = ttls.minOrNull(),
                ttlMaxSeconds = ttls.maxOrNull(),
                hasSoa = authorities?.let { negativeTtls.isNotEmpty() },
                negativeTtlSeconds = negativeTtls.minOrNull().takeIf { outcome.isNegativeDnsAnswer() },
            )
        val usableAddresses = addresses.distinct().takeIf { outcome == DnsResponseOutcome.ANSWER }.orEmpty()
        return DnsProbeAnswer(usableAddresses, semantics)
    }

    private fun aliasChain(
        domain: String,
        records: List<JsonObject>,
        addressOwners: Set<String>,
    ): List<String> {
        val aliases =
            records
                .filter { it.recordType() == CnameType }
                .groupBy(
                    { it.name() },
                    {
                        it.ttl()
                        dnsName(it.text("data"))
                    },
                )
        val chain = mutableListOf(domain)
        while (true) {
            val owner = chain.last()
            val targets = aliases[owner]?.distinct() ?: break
            val target = requireNotNull(targets.singleOrNull())
            require(target !in chain && chain.size < MaxAliasNames && owner !in addressOwners)
            chain += target
        }
        require(aliases.keys.all { it in chain.dropLast(1) })
        return chain
    }

    private fun JsonObject.negativeTtl(): Long {
        val fields = text("data").trim().split(SoaSeparator)
        require(fields.size == SoaFields)
        dnsName(fields[0])
        // SOA RNAME can escape a dot in the mailbox label.
        require(fields[1] != "@" && fields[1].all { it in VisibleAscii })
        try {
            Name.fromString(fields[1], Name.root)
        } catch (error: TextParseException) {
            throw IllegalArgumentException("Malformed DNS JSON SOA name", error)
        }
        val numbers =
            fields.drop(2).map { field ->
                require(field.all { it in '0'..'9' })
                requireNotNull(field.toLongOrNull()).also { require(it in 0..MaxUnsigned32) }
            }
        return minOf(ttl(), numbers.last())
    }

    private fun JsonObject.records(key: String): List<JsonObject>? =
        get(key)?.jsonArray?.let { records ->
            require(records.size <= MaxRecordsPerSection)
            records.map { it.jsonObject }
        }

    private fun JsonObject.name(): String = dnsName(text("name"))

    private fun JsonObject.text(key: String): String = primitive(key).also { require(it.isString) }.content

    private fun JsonObject.integer(key: String): Int = requireNotNull(number(key).intOrNull)

    private fun JsonObject.recordType(): Int = integer("type").also { require(it in 1..MaxRecordType) }

    // RFC 2181 treats incoming RR TTLs with the high bit set as zero.
    private fun JsonObject.ttl(): Long =
        requireNotNull(number("TTL").longOrNull)
            .also { require(it in 0..MaxUnsigned32) }
            .takeIf { it <= Int.MAX_VALUE } ?: 0L

    private fun JsonObject.number(key: String): JsonPrimitive = primitive(key).also { require(!it.isString) }

    private fun JsonObject.primitive(key: String): JsonPrimitive = requireNotNull(get(key)).jsonPrimitive

    private fun JsonObject.flag(key: String): Boolean? =
        get(key)?.jsonPrimitive?.let {
            require(!it.isString)
            requireNotNull(it.booleanOrNull)
        }

    private fun dnsName(value: String): String {
        if (value == ".") return value
        val name = value.removeSuffix(".")
        require(name.length in 1..MaxNameLength && name.split('.').all(HostLabel::matches))
        return name.lowercase()
    }

    private fun String.isWithin(zone: String): Boolean = zone == "." || this == zone || endsWith(".$zone")

    private fun checkDepth(payload: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in payload) {
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                !inString && (character == '{' || character == '[') -> require(++depth <= MaxDepth)
                !inString && (character == '}' || character == ']') -> depth--
            }
        }
    }

    private const val MaxDepth = 32
    private const val MaxRecordsPerSection = 512
    private const val AType = 1
    private const val NsType = 2
    private const val CnameType = 5
    private const val SoaType = 6
    private const val SoaFields = 7
    private const val MaxRcode = 4095
    private const val MaxRecordType = 65535
    private const val MaxUnsigned32 = 4294967295L
    private const val MaxAliasNames = 16
    private const val MaxNameLength = 253
    private val VisibleAscii = '!'..'~'
    private val HostLabel = Regex("[A-Za-z0-9_-]{1,63}")
    private val SoaSeparator = Regex("\\s+")
}

internal fun isDnsIpv4(value: String): Boolean = NumericIp.parseAddress(value)?.isIPv4 == true
