package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object DnsJsonResponseParser {
    fun parse(
        payload: String,
        domain: String,
        json: Json,
    ): DnsProbeAnswer {
        val root = json.parseToJsonElement(payload).jsonObject
        val question = requireNotNull(root["Question"]?.jsonArray?.singleOrNull()?.jsonObject)
        require(
            question["name"]
                ?.jsonPrimitive
                ?.content
                ?.trimEnd('.')
                ?.lowercase() == domain.trimEnd('.').lowercase(),
        )
        require(question["type"]?.jsonPrimitive?.intOrNull == AType)
        val rcode = root["Status"]?.jsonPrimitive?.intOrNull
        require(rcode != null && rcode in 0..MaxRcode)
        val initial =
            DnsResponseSemantics(
                outcome = dnsOutcome(rcode, false),
                rcode = rcode,
                truncated = root["TC"]?.jsonPrimitive?.booleanOrNull,
                authoritative = root["AA"]?.jsonPrimitive?.booleanOrNull,
                recursionAvailable = root["RA"]?.jsonPrimitive?.booleanOrNull,
                authenticatedData = root["AD"]?.jsonPrimitive?.booleanOrNull,
            )
        if (initial.truncated ==
            true
        ) {
            return DnsProbeAnswer(response = initial.copy(outcome = DnsResponseOutcome.TRUNCATED))
        }
        val records = root["Answer"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val chain = aliasChain(domain, records)
        val addressRecords = records.filter { it["type"]?.jsonPrimitive?.intOrNull == AType }
        require(addressRecords.all { it.name() == chain.last() })
        val addresses =
            addressRecords.map {
                it["data"]?.jsonPrimitive?.content.orEmpty().also { ip ->
                    require(isDnsIpv4(ip))
                }
            }
        val ttls =
            addressRecords.mapNotNull {
                it["TTL"]?.jsonPrimitive?.longOrNull?.takeIf { ttl ->
                    ttl in 0..MaxTtl
                }
            }
        val authorities = root["Authority"]?.jsonArray?.map { it.jsonObject }
        val soa =
            authorities
                ?.filter { it["type"]?.jsonPrimitive?.intOrNull == SoaType }
                ?.filter {
                    it.name().isEmpty() || chain.last() == it.name() || chain.last().endsWith(".${it.name()}")
                }
        val incompleteAnswer =
            addresses.isEmpty() && soa.isNullOrEmpty() && unresolvedAliasOrReferral(chain, authorities.orEmpty())
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
                hasSoa = soa?.isNotEmpty(),
                negativeTtlSeconds =
                    soa
                        ?.takeIf { outcome.isNegativeDnsAnswer() }
                        ?.mapNotNull(::negativeTtl)
                        ?.minOrNull(),
            )
        val usableAddresses = addresses.takeIf { outcome == DnsResponseOutcome.ANSWER }.orEmpty().distinct()
        return DnsProbeAnswer(usableAddresses, semantics)
    }

    private fun aliasChain(
        domain: String,
        records: List<JsonObject>,
    ): List<String> {
        val aliases = records.filter { it["type"]?.jsonPrimitive?.intOrNull == CnameType }
        val chain = mutableListOf(domain.trimEnd('.').lowercase())
        while (true) {
            val targets =
                aliases
                    .filter { it.name() == chain.last() }
                    .map {
                        it["data"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                            .trimEnd('.')
                            .lowercase()
                    }.distinct()
            if (targets.isEmpty()) break
            require(targets.size == 1 && targets.single() !in chain && chain.size <= MaxAliases)
            require(targets.single().isNotEmpty() && targets.single().length <= MaxNameLength)
            require(records.none { it.name() == chain.last() && it["type"]?.jsonPrimitive?.intOrNull == AType })
            chain += targets.single()
        }
        require(aliases.all { it.name() in chain.dropLast(1) })
        return chain
    }

    private fun JsonObject.name(): String =
        get("name")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
            .trimEnd('.')
            .lowercase()

    private fun unresolvedAliasOrReferral(
        chain: List<String>,
        authorities: List<JsonObject>,
    ): Boolean = chain.size > 1 || authorities.any { it["type"]?.jsonPrimitive?.intOrNull == 2 }

    private fun negativeTtl(record: JsonObject): Long? {
        val ttl = record["TTL"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0..MaxTtl }
        val parts =
            record["data"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.split(Regex("\\s+"))
        val minimum =
            parts
                ?.takeIf { it.size == SoaFields }
                ?.last()
                ?.toLongOrNull()
                ?.takeIf { it in 0..MaxTtl }
        return if (ttl != null && minimum != null) minOf(ttl, minimum) else null
    }

    private const val AType = 1
    private const val CnameType = 5
    private const val SoaType = 6
    private const val SoaFields = 7
    private const val MaxRcode = 4095
    private const val MaxTtl = 4294967295L
    private const val MaxAliases = 16
    private const val MaxNameLength = 253
}

internal fun isDnsIpv4(value: String): Boolean =
    value.split('.').let { parts ->
        parts.size == Ipv4Parts &&
            parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..Ipv4MaxOctet }
    }

private const val Ipv4Parts = 4
private const val Ipv4MaxOctet = 255
