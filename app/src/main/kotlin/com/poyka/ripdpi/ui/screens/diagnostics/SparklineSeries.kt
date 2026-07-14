package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
internal data class SparklineSeries(
    val values: ImmutableList<Float>,
    val minValue: Float,
    val maxValue: Float,
    val range: Float,
) {
    companion object {
        fun from(values: ImmutableList<Float>): SparklineSeries {
            val minValue = values.minOrNull() ?: 0f
            val maxValue = values.maxOrNull() ?: 0f
            return SparklineSeries(
                values = values,
                minValue = minValue,
                maxValue = maxValue,
                range = (maxValue - minValue).takeIf { it > 0f } ?: 1f,
            )
        }
    }
}
