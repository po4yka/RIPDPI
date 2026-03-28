package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveFakeTtlSettingsTest {
    @Test
    fun `normalizeAdaptiveFakeTtlDelta clamps to byte range`() {
        assertEquals(-128, normalizeAdaptiveFakeTtlDelta(-200))
        assertEquals(127, normalizeAdaptiveFakeTtlDelta(200))
        assertEquals(-1, normalizeAdaptiveFakeTtlDelta(-1))
        assertEquals(0, normalizeAdaptiveFakeTtlDelta(0))
    }

    @Test
    fun `normalizeAdaptiveFakeTtlMin clamps to 1 to 255`() {
        assertEquals(1, normalizeAdaptiveFakeTtlMin(0))
        assertEquals(1, normalizeAdaptiveFakeTtlMin(-5))
        assertEquals(255, normalizeAdaptiveFakeTtlMin(300))
        assertEquals(3, normalizeAdaptiveFakeTtlMin(3))
    }

    @Test
    fun `normalizeAdaptiveFakeTtlMax respects min bound`() {
        assertEquals(12, normalizeAdaptiveFakeTtlMax(12))
        assertEquals(255, normalizeAdaptiveFakeTtlMax(300))
        assertEquals(5, normalizeAdaptiveFakeTtlMax(5, min = 3))
        assertEquals(3, normalizeAdaptiveFakeTtlMax(1, min = 3))
    }

    @Test
    fun `normalizeAdaptiveFakeTtlMax uses default min when omitted`() {
        assertEquals(DefaultAdaptiveFakeTtlMin, normalizeAdaptiveFakeTtlMax(1))
    }

    @Test
    fun `normalizeAdaptiveFakeTtlFallback uses value when in range`() {
        assertEquals(8, normalizeAdaptiveFakeTtlFallback(8))
        assertEquals(1, normalizeAdaptiveFakeTtlFallback(1))
        assertEquals(255, normalizeAdaptiveFakeTtlFallback(255))
    }

    @Test
    fun `normalizeAdaptiveFakeTtlFallback uses default when out of range`() {
        assertEquals(DefaultAdaptiveFakeTtlFallback, normalizeAdaptiveFakeTtlFallback(0))
        assertEquals(DefaultAdaptiveFakeTtlFallback, normalizeAdaptiveFakeTtlFallback(-1))
        assertEquals(DefaultAdaptiveFakeTtlFallback, normalizeAdaptiveFakeTtlFallback(256))
    }

    @Test
    fun `normalizeAdaptiveFakeTtlFallback uses custom default when value out of range`() {
        assertEquals(10, normalizeAdaptiveFakeTtlFallback(0, defaultValue = 10))
        assertEquals(1, normalizeAdaptiveFakeTtlFallback(0, defaultValue = 1))
    }
}
