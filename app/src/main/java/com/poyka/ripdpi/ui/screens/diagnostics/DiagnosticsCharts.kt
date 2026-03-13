package com.poyka.ripdpi.ui.screens.diagnostics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal fun interpolatedSeries(
    from: List<Float>,
    to: List<Float>,
    progress: Float,
): List<Float> {
    if (to.isEmpty()) {
        return from
    }
    if (from.isEmpty()) {
        return to
    }

    val pointCount = max(from.size, to.size)
    return List(pointCount) { index ->
        val samplePosition =
            if (pointCount == 1) {
                0f
            } else {
                index.toFloat() / (pointCount - 1).toFloat()
            }
        lerpFloat(
            start = sampleSeries(from, samplePosition),
            stop = sampleSeries(to, samplePosition),
            fraction = progress,
        )
    }
}

internal fun sampleSeries(
    values: List<Float>,
    position: Float,
): Float {
    if (values.isEmpty()) {
        return 0f
    }
    if (values.size == 1) {
        return values.first()
    }

    val scaledIndex = position.coerceIn(0f, 1f) * values.lastIndex
    val lowerIndex = floor(scaledIndex).toInt()
    val upperIndex = ceil(scaledIndex).toInt().coerceAtMost(values.lastIndex)
    val localFraction = scaledIndex - lowerIndex
    return lerpFloat(values[lowerIndex], values[upperIndex], localFraction)
}

internal fun lerpFloat(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction
