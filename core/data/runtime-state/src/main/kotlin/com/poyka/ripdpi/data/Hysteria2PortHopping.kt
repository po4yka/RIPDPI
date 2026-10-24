package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** One validated destination set and cadence for every Hysteria import and activation path. */
@Serializable
data class Hysteria2PortHopping(
    val ports: String,
    val intervalMillis: Long = Hysteria2DefaultHopIntervalMillis,
    val intervalMaxMillis: Long? = null,
) {
    init {
        require(ports == normalizeHysteria2HopPorts(ports)) { "Hysteria hopping ports must be canonical" }
        require(intervalMillis in Hysteria2MinimumHopIntervalMillis..Hysteria2MaximumHopIntervalMillis) {
            "Hysteria hopping interval is outside the supported range"
        }
        require(
            intervalMaxMillis == null || intervalMaxMillis in (intervalMillis + 1)..Hysteria2MaximumHopIntervalMillis,
        ) {
            "Hysteria hopping maximum must exceed its minimum"
        }
    }

    val firstPort: Int get() = ports.substringBefore(',').substringBefore('-').toInt()
}

/** Absent configuration stays absent; interval-only input cannot silently become a scalar profile. */
fun hysteria2PortHopping(
    ports: String?,
    intervalMillis: Long? = null,
    intervalMaxMillis: Long? = null,
): Hysteria2PortHopping? {
    if (ports == null) {
        require(intervalMillis == null && intervalMaxMillis == null) { "Hysteria hopping intervals require ports" }
        return null
    }
    val interval = intervalMillis ?: Hysteria2DefaultHopIntervalMillis
    require(
        intervalMaxMillis == null || intervalMaxMillis >= interval,
    ) { "Hysteria hopping interval range is reversed" }
    return Hysteria2PortHopping(
        ports = normalizeHysteria2HopPorts(ports),
        intervalMillis = interval,
        intervalMaxMillis = intervalMaxMillis?.takeUnless { it == interval },
    )
}

/** Normalize the finite UDP port domain without expanding it into unbounded range lists. */
fun normalizeHysteria2HopPorts(raw: String): String {
    require(raw.length in 1..Hysteria2MaximumHopPortsLength) { "Hysteria hopping ports have invalid length" }
    val components = raw.split(',')
    require(components.size <= MaximumUdpPort) { "Hysteria hopping ports have too many components" }
    val ranges =
        components
            .map { component ->
                val endpoints = component.trim().split('-')
                require(endpoints.size in 1..2 && endpoints.all { it.matches(PortNumber) }) {
                    "Hysteria hopping ports have invalid syntax"
                }
                val first = endpoints.first().toIntOrNull()
                val last = endpoints.last().toIntOrNull()
                require(first != null && last != null && first in 1..MaximumUdpPort && last in first..MaximumUdpPort) {
                    "Hysteria hopping ports are outside the supported range"
                }
                first..last
            }.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    ranges.forEach { range ->
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            merged += range
        }
    }
    return merged.joinToString(",") { range ->
        if (range.first == range.last) range.first.toString() else "${range.first}-${range.last}"
    }
}

/** Parse Go duration units exactly; fractional milliseconds and overflow are never rounded. */
fun parseHysteria2HopDuration(raw: String): Long {
    require(raw.length in 1..MaximumHopDurationLength) { "Hysteria hopping duration has invalid length" }
    var offset = 0
    var milliseconds = BigDecimal.ZERO
    for (component in DurationComponent.findAll(raw)) {
        require(component.range.first == offset) { "Hysteria hopping duration has invalid syntax" }
        val multiplier =
            when (component.groupValues[2]) {
                "h" -> BigDecimal(3_600_000)
                "m" -> BigDecimal(60_000)
                "s" -> BigDecimal(1_000)
                "ms" -> BigDecimal.ONE
                "us", "µs", "μs" -> BigDecimal("0.001")
                "ns" -> BigDecimal("0.000001")
                else -> error("Unsupported duration unit")
            }
        milliseconds += component.groupValues[1].toBigDecimal() * multiplier
        offset = component.range.last + 1
    }
    require(offset == raw.length) { "Hysteria hopping duration has invalid syntax" }
    val value =
        try {
            milliseconds.longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Hysteria hopping duration must be exact whole milliseconds")
        }
    require(value in Hysteria2MinimumHopIntervalMillis..Hysteria2MaximumHopIntervalMillis) {
        "Hysteria hopping duration is outside the supported range"
    }
    return value
}

const val Hysteria2DefaultHopIntervalMillis = 30_000L
const val Hysteria2MinimumHopIntervalMillis = 5_000L
const val Hysteria2MaximumHopIntervalMillis = 9_223_372_036_854L
private const val Hysteria2MaximumHopPortsLength = 1_048_576
private const val MaximumHopDurationLength = 128
private const val MaximumUdpPort = 65_535
private val PortNumber = Regex("[0-9]{1,5}")
private val DurationComponent = Regex("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
