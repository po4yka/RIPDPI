package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthCheckUiExplanationTest {
    @Test
    fun `DegradedCloudflareLike surfaces handshake_ok_but_payload_stalled flag`() {
        val flag = uiHealthFlag(ProfileHealthState.DegradedCloudflareLike)
        assertEquals("handshake_ok_but_payload_stalled", flag)
    }

    @Test
    fun `Healthy state returns null UI flag`() {
        assertNull(uiHealthFlag(ProfileHealthState.Healthy))
    }

    @Test
    fun `Unknown state returns null UI flag`() {
        assertNull(uiHealthFlag(ProfileHealthState.Unknown))
    }
}
