package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Accept only probe settings that survive the native selector policy. */
internal fun importUrltestPolicy(entry: JsonObject): FailoverPolicy.Urltest {
    val url = requireNotNull(entry.stringField("url")?.takeIf { it.isNotEmpty() }) { "URL-test needs an explicit URL" }
    val parsed = url.toHttpUrlOrNull()
    require(
        parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.fragment == null && url.length <= MaxProbeUrlLength,
    ) { "unsupported URL-test URL" }
    val tolerance =
        entry["tolerance"]?.let { value ->
            val primitive = value as? JsonPrimitive
            require(primitive != null && !primitive.isString) { "invalid URL-test tolerance" }
            requireNotNull(primitive.intOrNull) { "invalid URL-test tolerance" }
        } ?: 0
    require(tolerance in 0..MaxUrltestTolerance) { "invalid URL-test tolerance" }
    return FailoverPolicy.Urltest(
        probeUrl = url,
        intervalSeconds = urltestInterval(entry.stringField("interval")),
        toleranceMs = tolerance.takeUnless { it == 0 } ?: DefaultUrltestTolerance,
    )
}

private fun JsonObject.stringField(key: String): String? =
    this[key]?.let { value ->
        require(value is JsonPrimitive && value.isString) { "invalid URL-test $key" }
        value.content
    }

private fun urltestInterval(raw: String?): Int {
    if (raw == null || raw.isEmpty()) return DefaultUrltestIntervalSeconds
    val match = requireNotNull(Regex("([0-9]+)([smhSMH]?)").matchEntire(raw)) { "invalid URL-test interval" }
    val magnitude = requireNotNull(match.groupValues[1].toLongOrNull()) { "invalid URL-test interval" }
    val multiplier =
        when (match.groupValues[2].lowercase()) {
            "m" -> SecondsPerMinute
            "h" -> SecondsPerHour
            else -> 1
        }
    require(magnitude <= Int.MAX_VALUE / multiplier) { "URL-test interval exceeds limit" }
    return (magnitude * multiplier).toInt().takeUnless { it == 0 } ?: DefaultUrltestIntervalSeconds
}

private const val DefaultUrltestIntervalSeconds = 180
private const val DefaultUrltestTolerance = 50
private const val MaxUrltestTolerance = 65_535
private const val MaxProbeUrlLength = 2_048
private const val SecondsPerMinute = 60
private const val SecondsPerHour = 3_600
