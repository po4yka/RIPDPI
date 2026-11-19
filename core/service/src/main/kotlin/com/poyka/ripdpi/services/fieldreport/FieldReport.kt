package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.diagnostics.ClientFieldReportContractVersion
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import com.poyka.ripdpi.services.CandidateMeasurementStage
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/*
 * On-device aggregation for the opt-in `client-field-report` v1 contract (vendored under
 * core/data/src/test/resources/contract/). A report is a positive projection: it is built only from the
 * typed, pattern-checked values below, so no other field of the measurement input can reach it.
 */

internal const val MillisPerHour = 3_600_000L
internal const val MillisPerDay = 24 * MillisPerHour

/** Upper bounds of `x-latency-bounds-ms`; the eighth histogram slot is +Inf. */
private val LatencyBoundsMillis = longArrayOf(50, 100, 250, 500, 1_000, 2_500, 5_000)
private const val HistogramSize = 8

/** The contract's k-anonymity floor: the server rejects a whole report that holds a smaller bucket. */
internal const val MinBucketAttempts = 5L
private const val MaxBucketAttempts = 1_000_000L
private const val MaxBuckets = 256
private const val MaxPendingHours = 24
private const val MaxAsn = 4_294_967_295L

private val ServerRefPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val CountryPattern = Regex("[A-Z]{2}")
private val AppVersionPattern = Regex("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})")
private val VersionNamePrefix = Regex("((?:0|[1-9][0-9]{0,3})\\.(?:0|[1-9][0-9]{0,3}))(?:[^0-9].*)?")
private val Protocols = setOf("vless-reality", "xhttp", "hysteria2", "amneziawg", "naiveproxy", "local-desync")
private val NetworkTypes = setOf("wifi", "cellular", "other")
private val OperatorCodePattern = Regex("([0-9]{3})([0-9]{2,3})")
private val MccPattern = Regex("[0-9]{3}")
private val MncPattern = Regex("[0-9]{2,3}")

/** One remote connection attempt to the bundle endpoint [serverRef]; [failureStage] is null on success. */
internal data class FieldReportAttempt(
    val serverRef: String,
    val protocol: String,
    val failureStage: FieldFailureStage?,
    val latencyMillis: Long,
)

internal const val SourceSim = "sim"
internal const val SourceServerHint = "server_hint"
internal const val SourceNone = "none"

/**
 * The contract `network` object of the network an hour was measured on. [source] names where the identity came
 * from: [SourceSim] carries the operator MCC and MNC, [SourceServerHint] the hint ASN and country, [SourceNone]
 * neither. [isContractValid] enforces those rules.
 */
@Serializable
data class FieldReportNetwork(
    val source: String,
    val networkType: String,
    val mcc: String? = null,
    val mnc: String? = null,
    val asn: Long? = null,
    val country: String? = null,
)

/** The `sim` network of an MCC+MNC [operatorCode] (5 or 6 digits), or null when the code is not one. */
internal fun simNetwork(operatorCode: String?): FieldReportNetwork? =
    operatorCode?.let(OperatorCodePattern::matchEntire)?.let { match ->
        FieldReportNetwork(SourceSim, "cellular", mcc = match.groupValues[1], mnc = match.groupValues[2])
    }

internal fun FieldReportNetwork.isContractValid(): Boolean {
    val hasOperator = mcc != null || mnc != null
    val sourceRules =
        when (source) {
            SourceSim -> {
                networkType == "cellular" &&
                    mcc?.let(MccPattern::matches) == true &&
                    mnc?.let(MncPattern::matches) == true &&
                    asn == null
            }

            SourceServerHint -> {
                asn != null && asn in 1..MaxAsn && !hasOperator
            }

            SourceNone -> {
                asn == null && !hasOperator
            }

            else -> {
                false
            }
        }
    return sourceRules && networkType in NetworkTypes && (country == null || CountryPattern.matches(country))
}

@Serializable
internal data class FieldReportBucket(
    val serverRef: String,
    val protocol: String,
    val failureStage: String? = null,
    val histogram: List<Long>,
) {
    val attempts: Long get() = histogram.sum()
}

/** One hour on one [network]; an hour whose network changed holds one entry per network. */
@Serializable
internal data class FieldReportHour(
    val startEpochMillis: Long,
    val buckets: List<FieldReportBucket> = emptyList(),
    /** Null only in a file written before the network was recorded; such an hour is never sent. */
    val network: FieldReportNetwork? = null,
)

@Serializable
internal data class FieldReportState(
    val hours: List<FieldReportHour> = emptyList(),
    /** Request instants by endpoint, for the rolling 24-hour `max_reports_per_day` cap. */
    val uploads: Map<String, List<Long>> = emptyMap(),
)

internal fun hourStart(epochMillis: Long): Long = Math.floorDiv(epochMillis, MillisPerHour) * MillisPerHour

private fun latencySlot(latencyMillis: Long): Int =
    LatencyBoundsMillis.indexOfFirst { latencyMillis <= it }.takeIf { it >= 0 } ?: (HistogramSize - 1)

/** `major.minor` of [versionName], or null when it does not start with one. */
internal fun fieldReportAppVersion(versionName: String?): String? =
    versionName?.let(VersionNamePrefix::matchEntire)?.groupValues?.get(1)

/** The contract protocol of [profile], or null for a protocol the contract does not list. */
internal fun fieldReportProtocol(profile: ProxyProfile): String? =
    when (profile) {
        is ProxyProfile.VlessReality -> if (profile.xhttpPath == null) "vless-reality" else "xhttp"
        is ProxyProfile.Vless -> profile.xhttpPath?.let { "xhttp" }
        is ProxyProfile.Hysteria2 -> "hysteria2"
        is ProxyProfile.Naive -> "naiveproxy"
        else -> null
    }

/**
 * The attempt that one member probe of [profile] represents, or null when the probe never left the device. The
 * server_ref is the member tag. A configuration failure is local and is not counted; a failure while the candidate
 * runtime gets ready (it may already dial the server) or during the HTTP request through it is counted with the
 * typed stage the probe or the native runtime named, else [FieldFailureStage.Unknown]. A failure counts the time
 * until the probe gave up.
 */
internal fun fieldReportAttempt(
    profile: ProxyProfile,
    measured: CandidateRelayMeasurement?,
    elapsedMillis: Long,
): FieldReportAttempt? {
    val protocol = fieldReportProtocol(profile)?.takeIf { ServerRefPattern.matches(profile.displayName) }
    val outcome: Pair<FieldFailureStage?, Long>? =
        when (measured) {
            is CandidateRelayMeasurement.Succeeded -> null to measured.latencyMillis
            is CandidateRelayMeasurement.Failed -> remoteFailure(measured.stage, measured.failureStage, elapsedMillis)
            is CandidateRelayMeasurement.TimedOut -> remoteFailure(measured.stage, measured.failureStage, elapsedMillis)
            else -> null
        }
    return if (protocol == null || outcome == null) {
        null
    } else {
        FieldReportAttempt(profile.displayName, protocol, outcome.first, outcome.second.coerceAtLeast(0))
    }
}

private fun remoteFailure(
    stage: CandidateMeasurementStage,
    failureStage: FieldFailureStage?,
    elapsedMillis: Long,
): Pair<FieldFailureStage, Long>? =
    if (stage == CandidateMeasurementStage.Configuration) {
        null
    } else {
        (failureStage ?: FieldFailureStage.Unknown) to elapsedMillis
    }

/** Adds [attempt], measured on [network], to the bucket of the hour that holds [nowMillis]. */
internal fun FieldReportState.record(
    attempt: FieldReportAttempt,
    network: FieldReportNetwork,
    nowMillis: Long,
): FieldReportState {
    val start = hourStart(nowMillis)
    val hour =
        hours.firstOrNull { it.startEpochMillis == start && it.network == network }
            ?: FieldReportHour(start, network = network)
    val stage = attempt.failureStage?.wireValue
    val existing =
        hour.buckets.firstOrNull {
            it.serverRef == attempt.serverRef && it.protocol == attempt.protocol && it.failureStage == stage
        }
    val bucket = existing ?: FieldReportBucket(attempt.serverRef, attempt.protocol, stage, List(HistogramSize) { 0L })
    val counted =
        bucket.copy(
            histogram =
                bucket.histogram.toMutableList().also {
                    it[latencySlot(attempt.latencyMillis)]++
                },
        )
    val updatedHour = hour.copy(buckets = hour.buckets.filterNot { it == existing } + counted)
    return copy(hours = hours.filterNot { it === hour } + updatedHour).pruned(nowMillis)
}

/** Drops hours and upload instants older than 24 hours and keeps at most 24 pending hours. */
internal fun FieldReportState.pruned(nowMillis: Long): FieldReportState =
    FieldReportState(
        hours =
            hours
                .filter {
                    nowMillis - it.startEpochMillis < MillisPerDay
                }.sortedBy { it.startEpochMillis }
                .takeLast(MaxPendingHours),
        uploads =
            uploads
                .mapValues { (_, instants) ->
                    instants.filter { nowMillis - it < MillisPerDay }
                }.filterValues { it.isNotEmpty() },
    )

private fun FieldReportBucket.isReportable(serverRefs: Set<String>): Boolean =
    serverRef in serverRefs &&
        ServerRefPattern.matches(serverRef) &&
        protocol in Protocols &&
        (failureStage == null || FieldFailureStage.fromWireValue(failureStage) != null) &&
        histogram.size == HistogramSize &&
        histogram.all { it >= 0 } &&
        attempts in MinBucketAttempts..MaxBucketAttempts

/**
 * The `client-field-report` v1 report of [hour] for the endpoints [serverRefs], or null when nothing may be sent:
 * every bucket under [MinBucketAttempts] attempts is suppressed, and a report without buckets is never built.
 */
internal fun buildFieldReport(
    hour: FieldReportHour,
    appVersion: String,
    serverRefs: Set<String>,
): JsonObject? {
    val network = hour.network
    val validContext =
        hour.startEpochMillis >= 0 &&
            hour.startEpochMillis % MillisPerHour == 0L &&
            AppVersionPattern.matches(appVersion) &&
            network?.isContractValid() == true
    val buckets =
        hour.buckets
            .filter { it.isReportable(serverRefs) }
            .sortedByDescending { it.attempts }
            .take(MaxBuckets)
    if (network == null || !validContext || buckets.isEmpty()) return null
    return buildJsonObject {
        put("schema_version", ClientFieldReportContractVersion)
        putJsonObject("window") {
            put("start_utc", Instant.ofEpochMilli(hour.startEpochMillis).toString())
            put("duration_hours", 1)
        }
        putJsonObject("client") {
            put("app_version", appVersion)
            put("platform", "android")
        }
        putJsonObject("network") {
            put("source", network.source)
            if (network.mcc != null && network.mnc != null) {
                putJsonObject("operator") {
                    put("mcc", network.mcc)
                    put("mnc", network.mnc)
                }
            }
            network.asn?.let { put("asn", it) }
            network.country?.let { put("country", it) }
            put("network_type", network.networkType)
        }
        putJsonArray("buckets") {
            buckets.forEach { bucket ->
                addJsonObject {
                    put("server_ref", bucket.serverRef)
                    put("protocol", bucket.protocol)
                    put("outcome", if (bucket.failureStage == null) "success" else "failure")
                    bucket.failureStage?.let { put("failure_stage", it) }
                    put("attempts", bucket.attempts)
                    putJsonArray("latency_ms_histogram") { bucket.histogram.forEach { add(it) } }
                }
            }
        }
    }
}
