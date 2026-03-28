package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HostAutolearnSettingsTest {
    @Test
    fun `normalizeHostAutolearnPenaltyTtlHours returns positive value unchanged`() {
        assertEquals(12, normalizeHostAutolearnPenaltyTtlHours(12))
        assertEquals(1, normalizeHostAutolearnPenaltyTtlHours(1))
    }

    @Test
    fun `normalizeHostAutolearnPenaltyTtlHours uses default for zero and negative`() {
        assertEquals(DefaultHostAutolearnPenaltyTtlHours, normalizeHostAutolearnPenaltyTtlHours(0))
        assertEquals(DefaultHostAutolearnPenaltyTtlHours, normalizeHostAutolearnPenaltyTtlHours(-1))
    }

    @Test
    fun `normalizeHostAutolearnMaxHosts returns positive value unchanged`() {
        assertEquals(2048, normalizeHostAutolearnMaxHosts(2048))
        assertEquals(1, normalizeHostAutolearnMaxHosts(1))
    }

    @Test
    fun `normalizeHostAutolearnMaxHosts uses default for zero and negative`() {
        assertEquals(DefaultHostAutolearnMaxHosts, normalizeHostAutolearnMaxHosts(0))
        assertEquals(DefaultHostAutolearnMaxHosts, normalizeHostAutolearnMaxHosts(-1))
    }
}
