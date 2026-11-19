package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

/** Typed causes for rejecting a RIPDPI bundle contract. */
enum class SingBoxBundleErrorCode {
    /** `ripdpi.schema_version` is missing or is not [SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION]. */
    UNSUPPORTED_SCHEMA_VERSION,

    /** `ripdpi.field_report` is present but violates the contract; the message names the field. */
    INVALID_FIELD_REPORT,
}

enum class EndpointHealthStatus(
    val wireValue: String,
) {
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    DOWN("down"),
    UNKNOWN("unknown"),
}

/** One `ripdpi.endpoints.<server_ref>.health` observation. */
data class EndpointHealth(
    val status: EndpointHealthStatus,
    val observedAtEpochMillis: Long,
    val ttlSeconds: Int,
) {
    /** The status at [nowEpochMillis]; an observation whose `observed_at + ttl_seconds` has passed is unknown. */
    fun statusAt(nowEpochMillis: Long): EndpointHealthStatus =
        if (nowEpochMillis >= observedAtEpochMillis + ttlSeconds * MillisPerSecond) {
            EndpointHealthStatus.UNKNOWN
        } else {
            status
        }
}

/** `ripdpi.field_report`: where and how often the opt-in field-report client may upload. */
data class RipdpiFieldReport(
    val endpoint: String,
    val cohortToken: String,
    val maxReportsPerDay: Int,
) {
    override fun toString() = "RipdpiFieldReport([REDACTED])"
}

private const val MillisPerSecond = 1_000L
private const val MinHealthTtlSeconds = 60
private const val MaxHealthTtlSeconds = 86_400
private const val MinFieldReportEndpointLength = 9
private const val MaxFieldReportEndpointLength = 2_048
private const val MaxReportsPerDay = 24
private val ServerRefPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val FieldReportEndpointPattern = Regex("https://[^\\s/?#@]+(/[^\\s#]*)?")
private val CohortTokenPattern = Regex("[A-Za-z0-9_-]{22,64}")

/**
 * Health entries that violate the contract (a non-server_ref key, an unknown status, a malformed instant, or a
 * ttl outside 60..86400) are dropped, so that endpoint stays neutral; the rest of the bundle still imports.
 */
internal fun parseEndpointHealth(endpoints: JsonObject?): Map<String, EndpointHealth> =
    endpoints
        ?.mapNotNull { (serverRef, value) ->
            val health = (value as? JsonObject)?.get("health") as? JsonObject ?: return@mapNotNull null
            val status = EndpointHealthStatus.entries.firstOrNull { it.wireValue == health.text("status") }
            val observedAt =
                health.text("observed_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val ttlSeconds = health.integer("ttl_seconds")?.takeIf { it in MinHealthTtlSeconds..MaxHealthTtlSeconds }
            if (!ServerRefPattern.matches(serverRef) || status == null || observedAt == null || ttlSeconds == null) {
                return@mapNotNull null
            }
            serverRef to EndpointHealth(status, observedAt, ttlSeconds)
        }?.toMap()
        .orEmpty()

/** The typed refusal for a `ripdpi` block whose version or `field_report` violates the contract, or `null`. */
internal fun ripdpiContractError(extension: JsonObject?): SingBoxParseResult.Error? {
    if (extension?.get("schema_version") != JsonPrimitive(SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION)) {
        return SingBoxParseResult.Error(
            "Unsupported RIPDPI bundle schema version",
            code = SingBoxBundleErrorCode.UNSUPPORTED_SCHEMA_VERSION,
        )
    }
    return invalidFieldReportField(extension["field_report"])?.let { field ->
        SingBoxParseResult.Error("Invalid ripdpi.$field", code = SingBoxBundleErrorCode.INVALID_FIELD_REPORT)
    }
}

/** Returns the first `field_report` field that violates the contract, or `null` when the block is absent or valid. */
private fun invalidFieldReportField(value: JsonElement?): String? {
    if (value == null) return null
    val block = value as? JsonObject ?: return "field_report"
    val endpoint = block.text("endpoint")
    val maxReports = block.integer("max_reports_per_day")
    return when {
        endpoint == null ||
            endpoint.length !in MinFieldReportEndpointLength..MaxFieldReportEndpointLength ||
            !FieldReportEndpointPattern.matches(endpoint) -> "field_report.endpoint"

        block.text("cohort_token")?.let(CohortTokenPattern::matches) != true -> "field_report.cohort_token"

        maxReports == null || maxReports !in 1..MaxReportsPerDay -> "field_report.max_reports_per_day"

        else -> null
    }
}

/** Maps a `field_report` block that [invalidFieldReportField] has already accepted. */
internal fun parseFieldReport(block: JsonObject?): RipdpiFieldReport? =
    block?.let {
        RipdpiFieldReport(
            endpoint = requireNotNull(it.text("endpoint")),
            cohortToken = requireNotNull(it.text("cohort_token")),
            maxReportsPerDay = requireNotNull(it.integer("max_reports_per_day")),
        )
    }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
