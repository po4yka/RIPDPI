package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.serialization.Serializable
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

@Serializable
enum class EndpointHealthStatus(
    val wireValue: String,
) {
    HEALTHY("healthy"),
    DEGRADED("degraded"),
    DOWN("down"),
    UNKNOWN("unknown"),
}

/** One `ripdpi.endpoints.<server_ref>.health` observation. */
@Serializable
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

/** Selector members ordered by their `ripdpi.endpoints` health at one instant. */
sealed interface EndpointHealthOrder {
    val members: List<ProxyProfile>

    /** Unexpired down members are excluded; neutral (healthy, unknown, expired, or no health) first, degraded last. */
    data class Ranked(
        override val members: List<ProxyProfile>,
    ) : EndpointHealthOrder

    /** Every member is down with unexpired health: all stay candidates in declared order, as with no health. */
    data class AllDownFailOpen(
        override val members: List<ProxyProfile>,
    ) : EndpointHealthOrder
}

/**
 * Orders [members] by the health of their server_ref (the outbound tag, [ProxyProfile.displayName]) at
 * [nowEpochMillis]. Declared order is kept inside each rank. Health is advisory, so an all-down selector
 * fails open instead of leaving no candidate.
 */
fun orderByEndpointHealth(
    members: List<ProxyProfile>,
    health: Map<String, EndpointHealth>,
    nowEpochMillis: Long,
): EndpointHealthOrder {
    val statusByMember = members.associateWith { health[it.displayName]?.statusAt(nowEpochMillis) }
    val usable = members.filterNot { statusByMember[it] == EndpointHealthStatus.DOWN }
    return if (usable.isEmpty() && members.isNotEmpty()) {
        EndpointHealthOrder.AllDownFailOpen(members)
    } else {
        EndpointHealthOrder.Ranked(usable.sortedBy { statusByMember[it] == EndpointHealthStatus.DEGRADED })
    }
}

/** The group's failover candidates at [nowEpochMillis]; stored health is re-evaluated on every call. */
fun ProxyGroup.membersByHealth(nowEpochMillis: Long): EndpointHealthOrder =
    orderByEndpointHealth(members, memberHealth, nowEpochMillis)

/**
 * `ripdpi.field_report`: where and how often the opt-in field-report client may upload, and the optional
 * `network_hint_endpoint` the client asks for its network's ASN when the default network is not cellular.
 */
@Serializable
data class RipdpiFieldReport(
    val endpoint: String,
    val cohortToken: String,
    val maxReportsPerDay: Int,
    val networkHintEndpoint: String? = null,
) {
    override fun toString() = "RipdpiFieldReport([REDACTED])"
}

/** A bundle `server_ref` (outbound tag), as the bundle and field-report contracts define it. */
val ServerRefPattern = Regex("[A-Za-z0-9_-]{1,64}")

private const val MillisPerSecond = 1_000L
private const val MinHealthTtlSeconds = 60
private const val MaxHealthTtlSeconds = 86_400
private const val MinFieldReportEndpointLength = 9
private const val MaxFieldReportEndpointLength = 2_048
private const val MaxReportsPerDay = 24
private val FieldReportEndpointPattern = Regex("https://[^\\s/?#@]+(/[^\\s#]*)?")
private val CohortTokenPattern = Regex("[A-Za-z0-9_-]{22,64}")

/**
 * Health entries that violate the contract (a non-server_ref key, an unknown status, a malformed instant, or a
 * ttl outside 60..86400) are dropped, so that endpoint stays neutral; the rest of the bundle still imports.
 */
internal fun parseEndpointHealth(endpoints: JsonObject?): Map<String, EndpointHealth> =
    endpoints
        ?.filterKeys(ServerRefPattern::matches)
        ?.mapNotNull { (serverRef, value) ->
            val health = (value as? JsonObject)?.get("health") as? JsonObject ?: return@mapNotNull null
            val status = EndpointHealthStatus.entries.firstOrNull { it.wireValue == health.text("status") }
            val observedAt =
                health.text("observed_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val ttlSeconds = health.integer("ttl_seconds")?.takeIf { it in MinHealthTtlSeconds..MaxHealthTtlSeconds }
            if (status == null || observedAt == null || ttlSeconds == null) {
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
            foundSchemaVersion = (extension?.get("schema_version") as? JsonPrimitive)?.intOrNull,
        )
    }
    return invalidFieldReportField(extension["field_report"])?.let { field ->
        SingBoxParseResult.Error("Invalid ripdpi.$field", code = SingBoxBundleErrorCode.INVALID_FIELD_REPORT)
    }
}

/** Returns the first `field_report` field that violates the contract, or `null` when the block is absent or valid. */
private fun invalidFieldReportField(value: JsonElement?): String? {
    val block = value as? JsonObject
    val maxReports = block?.integer("max_reports_per_day")
    return when {
        value == null -> {
            null
        }

        block == null -> {
            "field_report"
        }

        !isHttpsEndpoint(block.text("endpoint")) -> {
            "field_report.endpoint"
        }

        "network_hint_endpoint" in block && !isHttpsEndpoint(block.text("network_hint_endpoint")) -> {
            "field_report.network_hint_endpoint"
        }

        block.text("cohort_token")?.let(CohortTokenPattern::matches) != true -> {
            "field_report.cohort_token"
        }

        maxReports == null || maxReports !in 1..MaxReportsPerDay -> {
            "field_report.max_reports_per_day"
        }

        else -> {
            null
        }
    }
}

private fun isHttpsEndpoint(value: String?): Boolean =
    value != null &&
        value.length in MinFieldReportEndpointLength..MaxFieldReportEndpointLength &&
        FieldReportEndpointPattern.matches(value)

/** Maps a `field_report` block that [invalidFieldReportField] has already accepted. */
internal fun parseFieldReport(block: JsonObject?): RipdpiFieldReport? =
    block?.let {
        RipdpiFieldReport(
            endpoint = requireNotNull(it.text("endpoint")),
            cohortToken = requireNotNull(it.text("cohort_token")),
            maxReportsPerDay = requireNotNull(it.integer("max_reports_per_day")),
            networkHintEndpoint = it.text("network_hint_endpoint"),
        )
    }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
