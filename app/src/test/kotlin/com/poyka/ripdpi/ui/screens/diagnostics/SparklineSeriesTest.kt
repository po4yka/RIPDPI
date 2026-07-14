package com.poyka.ripdpi.ui.screens.diagnostics

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SparklineSeriesTest {
    @Test
    fun `prepared sparkline series preserves samples and caches bounds`() {
        val values = persistentListOf(30f, 10f, 20f)

        val series = SparklineSeries.from(values)

        assertSame(values, series.values)
        assertEquals(10f, series.minValue, 0.001f)
        assertEquals(30f, series.maxValue, 0.001f)
        assertEquals(20f, series.range, 0.001f)
    }

    @Test
    fun `prepared flat sparkline series uses non-zero geometry range`() {
        val series = SparklineSeries.from(persistentListOf(7f, 7f, 7f))

        assertEquals(7f, series.minValue, 0.001f)
        assertEquals(7f, series.maxValue, 0.001f)
        assertEquals(1f, series.range, 0.001f)
    }

    @Test
    fun `prepared empty sparkline series has zero bounds`() {
        val series = SparklineSeries.from(persistentListOf())

        assertEquals(0f, series.minValue, 0.001f)
        assertEquals(0f, series.maxValue, 0.001f)
        assertEquals(1f, series.range, 0.001f)
    }
}
