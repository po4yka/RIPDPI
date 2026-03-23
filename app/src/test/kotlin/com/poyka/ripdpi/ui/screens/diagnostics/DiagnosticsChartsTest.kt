package com.poyka.ripdpi.ui.screens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsChartsTest {
    @Test
    fun `lerpFloat at zero returns start`() {
        assertEquals(10f, lerpFloat(10f, 20f, 0f), 0.001f)
    }

    @Test
    fun `lerpFloat at one returns stop`() {
        assertEquals(20f, lerpFloat(10f, 20f, 1f), 0.001f)
    }

    @Test
    fun `lerpFloat at midpoint returns average`() {
        assertEquals(15f, lerpFloat(10f, 20f, 0.5f), 0.001f)
    }

    @Test
    fun `sampleSeries empty list returns zero`() {
        assertEquals(0f, sampleSeries(emptyList(), 0.5f), 0.001f)
    }

    @Test
    fun `sampleSeries single element returns that element`() {
        assertEquals(42f, sampleSeries(listOf(42f), 0.5f), 0.001f)
    }

    @Test
    fun `sampleSeries at start returns first element`() {
        assertEquals(1f, sampleSeries(listOf(1f, 2f, 3f), 0f), 0.001f)
    }

    @Test
    fun `sampleSeries at end returns last element`() {
        assertEquals(3f, sampleSeries(listOf(1f, 2f, 3f), 1f), 0.001f)
    }

    @Test
    fun `sampleSeries at midpoint interpolates`() {
        assertEquals(2f, sampleSeries(listOf(1f, 2f, 3f), 0.5f), 0.001f)
    }

    @Test
    fun `sampleSeries clamps position above one`() {
        assertEquals(3f, sampleSeries(listOf(1f, 2f, 3f), 1.5f), 0.001f)
    }

    @Test
    fun `sampleSeries clamps position below zero`() {
        assertEquals(1f, sampleSeries(listOf(1f, 2f, 3f), -0.5f), 0.001f)
    }

    @Test
    fun `interpolatedSeries with empty target returns source`() {
        val from = listOf(1f, 2f, 3f)
        assertEquals(from, interpolatedSeries(from, emptyList(), 0.5f))
    }

    @Test
    fun `interpolatedSeries with empty source returns target`() {
        val to = listOf(4f, 5f, 6f)
        assertEquals(to, interpolatedSeries(emptyList(), to, 0.5f))
    }

    @Test
    fun `interpolatedSeries at zero returns source values`() {
        val from = listOf(1f, 2f, 3f)
        val to = listOf(4f, 5f, 6f)
        val result = interpolatedSeries(from, to, 0f)
        result.zip(from).forEach { (actual, expected) ->
            assertEquals(expected, actual, 0.001f)
        }
    }

    @Test
    fun `interpolatedSeries at one returns target values`() {
        val from = listOf(1f, 2f, 3f)
        val to = listOf(4f, 5f, 6f)
        val result = interpolatedSeries(from, to, 1f)
        result.zip(to).forEach { (actual, expected) ->
            assertEquals(expected, actual, 0.001f)
        }
    }

    @Test
    fun `interpolatedSeries at midpoint returns averages`() {
        val from = listOf(0f, 10f)
        val to = listOf(10f, 20f)
        val result = interpolatedSeries(from, to, 0.5f)
        assertEquals(5f, result[0], 0.001f)
        assertEquals(15f, result[1], 0.001f)
    }

    @Test
    fun `interpolatedSeries normalizes different length series`() {
        val from = listOf(0f, 10f)
        val to = listOf(10f, 20f, 30f)
        val result = interpolatedSeries(from, to, 0f)
        // Source has 2 points, target has 3 points. Result length = max(2, 3) = 3
        assertEquals(3, result.size)
        // At progress=0, result samples from source (2 elements) at normalized positions
        assertEquals(0f, result[0], 0.001f) // position 0.0 -> from[0] = 0
        assertEquals(5f, result[1], 0.001f) // position 0.5 -> lerp(0, 10, 0.5) = 5
        assertEquals(10f, result[2], 0.001f) // position 1.0 -> from[1] = 10
    }
}
