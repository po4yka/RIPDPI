package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadFailoverStateTest {
    private fun state() = PayloadFailoverState(policy())

    private fun results(
        active: Long?,
        reserve: Long?,
    ) = listOf(MemberLatency("active", active), MemberLatency("reserve", reserve))

    @Test
    fun `two failed active and recovered reserve observations are required`() {
        val state = state()
        assertNull(state.observe(results(null, 20), "active", 0))
        assertEquals("reserve", state.observe(results(null, 20), "active", 60_000))
    }

    @Test
    fun `healthy active path never changes for lower latency`() {
        val state = state()
        repeat(5) { assertNull(state.observe(results(200, 1), "active", it * 60_000L)) }
    }

    @Test
    fun `intermittent failures and recovery reset consecutive thresholds`() {
        val state = state()
        assertNull(state.observe(results(null, 20), "active", 0))
        assertNull(state.observe(results(100, null), "active", 60_000))
        assertNull(state.observe(results(null, 20), "active", 120_000))
        assertEquals("reserve", state.observe(results(null, 20), "active", 180_000))
    }

    @Test
    fun `all failed retains selection and exponentially backs off with a cap`() {
        val state = state()
        assertNull(state.observe(results(null, null), "active", 0))
        assertEquals(120_000L, state.nextProbeAt)
        assertNull(state.observe(results(null, null), "active", 120_000))
        assertEquals(360_000L, state.nextProbeAt)
        repeat(10) { assertNull(state.observe(results(null, null), "active", 0)) }
        assertEquals(900_000L, state.nextProbeAt)
        assertNull(state.observe(results(1, null), "active", 900_000))
        assertEquals(960_000L, state.nextProbeAt)
    }

    @Test
    fun `successful switch enforces cooldown even when former exit recovers`() {
        val state = state()
        state.observe(results(null, 20), "active", 0)
        assertEquals("reserve", state.observe(results(null, 20), "active", 60_000))
        state.switched(60_000)
        assertNull(state.observe(results(20, null), "reserve", 90_000))
        assertNull(state.observe(results(20, null), "reserve", 120_000))
        assertEquals("active", state.observe(results(20, null), "reserve", 180_000))
    }
}

internal fun policy() =
    PayloadFailoverPolicy(
        1,
        "select",
        listOf(
            PayloadProbeControl("https://a.example.test/control", 32_768, "a".repeat(64)),
            PayloadProbeControl("https://b.example.test/control", 32_768, "a".repeat(64)),
        ),
        60,
        10,
        2,
        2,
        120,
        900,
    )
