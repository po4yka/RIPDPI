package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.ActivationFilter
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.NumericRange
import kotlinx.serialization.Serializable

@Serializable
data class NumericRangeModel(
    val start: Long? = null,
    val end: Long? = null,
) {
    val isEmpty: Boolean
        get() = start == null && end == null
}

@Serializable
data class ActivationFilterModel(
    val round: NumericRangeModel = NumericRangeModel(),
    val payloadSize: NumericRangeModel = NumericRangeModel(),
    val streamBytes: NumericRangeModel = NumericRangeModel(),
    val tcpHasTimestamp: Boolean? = null,
    val tcpHasEch: Boolean? = null,
    val tcpWindowBelow: Int? = null,
    val tcpMssBelow: Int? = null,
) {
    val isEmpty: Boolean
        get() =
            round.isEmpty &&
                payloadSize.isEmpty &&
                streamBytes.isEmpty &&
                tcpHasTimestamp == null &&
                tcpHasEch == null &&
                tcpWindowBelow == null &&
                tcpMssBelow == null

    val hasTcpStatePredicates: Boolean
        get() = tcpHasTimestamp != null || tcpHasEch != null || tcpWindowBelow != null || tcpMssBelow != null
}

private const val ActivationFilterUnset = -1L
private const val ActivationFilterTcpThresholdMin = 1
private const val ActivationFilterTcpThresholdMax = 65_535

fun normalizeRoundRange(
    start: Long?,
    end: Long?,
): NumericRangeModel = normalizeNumericRange(start, end, 1L)

fun normalizeRoundRange(range: NumericRangeModel): NumericRangeModel = normalizeRoundRange(range.start, range.end)

fun normalizePayloadSizeRange(
    start: Long?,
    end: Long?,
): NumericRangeModel = normalizeNumericRange(start, end, 0L)

fun normalizePayloadSizeRange(range: NumericRangeModel): NumericRangeModel =
    normalizePayloadSizeRange(range.start, range.end)

fun normalizeStreamBytesRange(
    start: Long?,
    end: Long?,
): NumericRangeModel = normalizeNumericRange(start, end, 0L)

fun normalizeStreamBytesRange(range: NumericRangeModel): NumericRangeModel =
    normalizeStreamBytesRange(range.start, range.end)

fun normalizeActivationFilter(filter: ActivationFilterModel): ActivationFilterModel =
    ActivationFilterModel(
        round = normalizeRoundRange(filter.round.start, filter.round.end),
        payloadSize = normalizePayloadSizeRange(filter.payloadSize.start, filter.payloadSize.end),
        streamBytes = normalizeStreamBytesRange(filter.streamBytes.start, filter.streamBytes.end),
        tcpHasTimestamp = filter.tcpHasTimestamp,
        tcpHasEch = filter.tcpHasEch,
        tcpWindowBelow = normalizeTcpThreshold(filter.tcpWindowBelow),
        tcpMssBelow = normalizeTcpThreshold(filter.tcpMssBelow),
    )

@Suppress("ReturnCount")
fun formatNumericRange(range: NumericRangeModel): String? {
    val normalized = normalizeNumericRange(range.start, range.end, Long.MIN_VALUE)
    val start = normalized.start ?: return null
    val end = normalized.end ?: return null
    return if (start == end) start.toString() else "$start-$end"
}

fun formatActivationFilterSummary(filter: ActivationFilterModel): String =
    listOfNotNull(
        formatNumericRange(filter.round)?.let { "round=$it" },
        formatNumericRange(filter.payloadSize)?.let { "size=$it" },
        formatNumericRange(filter.streamBytes)?.let { "stream=$it" },
        filter.tcpHasTimestamp?.let { "ts=${booleanFilterLabel(it)}" },
        filter.tcpHasEch?.let { "ech=${booleanFilterLabel(it)}" },
        filter.tcpWindowBelow?.let { "win<$it" },
        filter.tcpMssBelow?.let { "mss<$it" },
    ).joinToString(" ")

fun parseRoundRange(spec: String): NumericRangeModel = parseNumericRange(spec, 1L)

fun parsePayloadSizeRange(spec: String): NumericRangeModel = parseNumericRange(spec, 0L)

fun parseStreamBytesRange(spec: String): NumericRangeModel = parseNumericRange(spec, 0L)

fun AppSettings.effectiveGroupActivationFilter(): ActivationFilterModel =
    if (hasGroupActivationFilter()) {
        groupActivationFilter.toModel().let(::normalizeActivationFilter)
    } else {
        ActivationFilterModel()
    }

fun AppSettings.Builder.setGroupActivationFilterCompat(filter: ActivationFilterModel): AppSettings.Builder =
    apply {
        val normalized = normalizeActivationFilter(filter)
        validateNoTcpStatePredicates(normalized, "groupActivationFilter")
        if (normalized.isEmpty) {
            clearGroupActivationFilter()
        } else {
            setGroupActivationFilter(normalized.toProto())
        }
    }

private fun NumericRange.toModel(): NumericRangeModel =
    NumericRangeModel(
        start = start.takeUnless { it == ActivationFilterUnset },
        end = end.takeUnless { it == ActivationFilterUnset },
    )

internal fun NumericRangeModel.toProto(): NumericRange =
    NumericRange
        .newBuilder()
        .setStart(start ?: ActivationFilterUnset)
        .setEnd(end ?: ActivationFilterUnset)
        .build()

internal fun ActivationFilter.toModel(): ActivationFilterModel =
    ActivationFilterModel(
        round = if (hasRound()) round.toModel() else NumericRangeModel(),
        payloadSize = if (hasPayloadSize()) payloadSize.toModel() else NumericRangeModel(),
        streamBytes = if (hasStreamBytes()) streamBytes.toModel() else NumericRangeModel(),
        tcpHasTimestamp = if (hasTcpHasTimestamp()) tcpHasTimestamp else null,
        tcpHasEch = if (hasTcpHasEch()) tcpHasEch else null,
        tcpWindowBelow = tcpWindowBelow.takeIf { hasTcpWindowBelow() },
        tcpMssBelow = tcpMssBelow.takeIf { hasTcpMssBelow() },
    )

internal fun ActivationFilterModel.toProto(): ActivationFilter =
    ActivationFilter
        .newBuilder()
        .apply {
            if (!this@toProto.round.isEmpty) {
                setRound(this@toProto.round.toProto())
            }
            if (!this@toProto.payloadSize.isEmpty) {
                setPayloadSize(this@toProto.payloadSize.toProto())
            }
            if (!this@toProto.streamBytes.isEmpty) {
                setStreamBytes(this@toProto.streamBytes.toProto())
            }
            this@toProto.tcpHasTimestamp?.let(::setTcpHasTimestamp)
            this@toProto.tcpHasEch?.let(::setTcpHasEch)
            this@toProto.tcpWindowBelow?.let(::setTcpWindowBelow)
            this@toProto.tcpMssBelow?.let(::setTcpMssBelow)
        }.build()

fun validateNoTcpStatePredicates(
    filter: ActivationFilterModel,
    fieldName: String,
) {
    require(!normalizeActivationFilter(filter).hasTcpStatePredicates) {
        "$fieldName must not declare TCP-state predicates"
    }
}

@Suppress("ReturnCount")
private fun normalizeNumericRange(
    start: Long?,
    end: Long?,
    minValue: Long,
): NumericRangeModel {
    val normalizedStart = start?.takeIf { it >= minValue }
    val normalizedEnd = end?.takeIf { it >= minValue }
    if (normalizedStart == null && normalizedEnd == null) {
        return NumericRangeModel()
    }
    val resolvedStart = normalizedStart ?: normalizedEnd
    val resolvedEnd = normalizedEnd ?: normalizedStart
    if (resolvedStart == null || resolvedEnd == null) {
        return NumericRangeModel()
    }
    return if (resolvedStart <= resolvedEnd) {
        NumericRangeModel(start = resolvedStart, end = resolvedEnd)
    } else {
        NumericRangeModel(start = resolvedEnd, end = resolvedStart)
    }
}

private fun parseNumericRange(
    spec: String,
    minValue: Long,
): NumericRangeModel {
    val trimmed = spec.trim()
    require(trimmed.isNotEmpty()) { "Range must not be empty" }
    val parts = trimmed.split('-', limit = 2)
    val start = parts[0].trim().toLongOrNull() ?: error("Invalid range start")
    val end =
        if (parts.size == 1) {
            start
        } else {
            parts[1].trim().toLongOrNull() ?: error("Invalid range end")
        }
    require(start >= minValue && end >= minValue) { "Range values must be >= $minValue" }
    require(start <= end) { "Range start must be <= end" }
    return NumericRangeModel(start = start, end = end)
}

private fun booleanFilterLabel(value: Boolean): String = if (value) "yes" else "no"

private fun normalizeTcpThreshold(value: Int?): Int? =
    value?.takeIf { it in ActivationFilterTcpThresholdMin..ActivationFilterTcpThresholdMax }
