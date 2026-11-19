package com.poyka.ripdpi.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointHealthOrderTest {
    private val now = 1_000_000_000L

    private fun health(
        status: EndpointHealthStatus,
        observedSecondsAgo: Long = 10,
        ttlSeconds: Int = 600,
    ) = EndpointHealth(status, now - observedSecondsAgo * 1_000L, ttlSeconds)

    @Test
    fun `unexpired down endpoint is excluded`() {
        val order =
            orderByEndpointHealth(
                listOf("a", "b"),
                mapOf("a" to health(EndpointHealthStatus.HEALTHY), "b" to health(EndpointHealthStatus.DOWN)),
                now,
            )
        assertEquals(EndpointHealthOrder.Candidates(listOf("a")), order)
    }

    @Test
    fun `expired down endpoint keeps its neutral position`() {
        val order =
            orderByEndpointHealth(
                listOf("b", "a"),
                mapOf(
                    "a" to health(EndpointHealthStatus.HEALTHY),
                    "b" to health(EndpointHealthStatus.DOWN, observedSecondsAgo = 600, ttlSeconds = 600),
                ),
                now,
            )
        assertEquals(EndpointHealthOrder.Candidates(listOf("b", "a")), order)
    }

    @Test
    fun `degraded endpoint is ordered after unknown and missing health`() {
        val order =
            orderByEndpointHealth(
                listOf("a", "b", "c"),
                mapOf("a" to health(EndpointHealthStatus.DEGRADED), "b" to health(EndpointHealthStatus.UNKNOWN)),
                now,
            )
        assertEquals(EndpointHealthOrder.Candidates(listOf("b", "c", "a")), order)
    }

    @Test
    fun `healthy is neutral and not promoted above unknown`() {
        val order =
            orderByEndpointHealth(
                listOf("a", "b"),
                mapOf("a" to health(EndpointHealthStatus.UNKNOWN), "b" to health(EndpointHealthStatus.HEALTHY)),
                now,
            )
        assertEquals(EndpointHealthOrder.Candidates(listOf("a", "b")), order)
    }

    @Test
    fun `every endpoint down reports no usable endpoint`() {
        val order =
            orderByEndpointHealth(
                listOf("a", "b"),
                mapOf("a" to health(EndpointHealthStatus.DOWN), "b" to health(EndpointHealthStatus.DOWN)),
                now,
            )
        assertEquals(EndpointHealthOrder.NoUsableEndpoint, order)
    }
}
