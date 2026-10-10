package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.Serializable

@Serializable
enum class DnsResponseOutcome {
    ANSWER,
    NODATA,
    NXDOMAIN,
    SERVFAIL,
    REFUSED,
    OTHER_RCODE,
    TRUNCATED,
    TIMEOUT,
    TRANSPORT_ERROR,
    MALFORMED,
    NOT_OBSERVED,
}

/** Response facts supplied by a resolver; AD and EDE are not locally verified claims. */
@Serializable
data class DnsResponseSemantics(
    val queryType: String = "A",
    val outcome: DnsResponseOutcome = DnsResponseOutcome.NOT_OBSERVED,
    val rcode: Int? = null,
    val ttlMinSeconds: Long? = null,
    val ttlMaxSeconds: Long? = null,
    val negativeTtlSeconds: Long? = null,
    val truncated: Boolean? = null,
    val authoritative: Boolean? = null,
    val recursionAvailable: Boolean? = null,
    val authenticatedData: Boolean? = null,
    val hasSoa: Boolean? = null,
    val cnameTargets: List<String> = emptyList(),
    val extendedDnsErrorCodes: List<Int> = emptyList(),
)

/** Duplicate detail keys and malformed metadata are not usable observations. */
fun parseDnsResponseSemantics(
    details: List<ProbeDetail>,
    key: String,
): DnsResponseSemantics? =
    if (key in DnsResponseDetailKeys) {
        parseDnsResponseSemantics(details.singleOrNull { it.key == key }?.value)
    } else {
        null
    }

fun parseDnsResponseSemantics(value: String?): DnsResponseSemantics? =
    value?.takeIf { it.length <= DnsResponseMaxCharacters }?.let {
        runCatching { DnsResponseJson.decodeFromString(DnsResponseSemantics.serializer(), it) }
            .getOrNull()
            ?.validatedDnsResponseSemantics()
    }

fun DnsResponseSemantics.validatedDnsResponseSemantics(): DnsResponseSemantics? =
    takeIf {
        queryType in DnsQueryTypes && (rcode == null || rcode in 0..DnsMaxRcode) &&
            listOf(ttlMinSeconds, ttlMaxSeconds, negativeTtlSeconds).all { it == null || it in 0..DnsMaxTtl } &&
            (ttlMinSeconds == null || ttlMaxSeconds == null || ttlMinSeconds <= ttlMaxSeconds) &&
            cnameTargets.size <= DnsMetadataListLimit && cnameTargets.all(::isDnsEvidenceName) &&
            extendedDnsErrorCodes.size <= DnsMetadataListLimit && extendedDnsErrorCodes.all { it in 0..DnsMaxEdeCode }
    }

internal fun canonicalDnsResponseSemantics(value: String?): String? =
    parseDnsResponseSemantics(value)?.let { evidence ->
        DnsResponseJson.encodeToString(
            DnsResponseSemantics.serializer(),
            evidence.copy(cnameTargets = evidence.cnameTargets.map { "redacted" }),
        )
    }

private fun isDnsEvidenceName(value: String): Boolean =
    value.toByteArray(Charsets.UTF_8).size in 1..DnsMaxNameBytes && value.none(Char::isISOControl)

private const val DnsResponseMaxCharacters = 8_192
private const val DnsMetadataListLimit = 16
private const val DnsMaxNameBytes = 253
private const val DnsMaxRcode = 4095
private const val DnsMaxEdeCode = 65535
private const val DnsMaxTtl = 4_294_967_295L
private val DnsQueryTypes = setOf("A", "AAAA")
private val DnsResponseDetailKeys = setOf("udpDnsResponse", "encryptedDnsResponse")
private val DnsResponseJson = RipDpiJson
