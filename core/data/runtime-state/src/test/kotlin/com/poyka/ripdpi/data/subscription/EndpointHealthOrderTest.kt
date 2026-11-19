package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointHealthOrderTest {
    private val now = 1_000_000_000L

    private fun health(
        status: EndpointHealthStatus,
        observedSecondsAgo: Long = 10,
        ttlSeconds: Int = 600,
    ) = EndpointHealth(status, now - observedSecondsAgo * 1_000L, ttlSeconds)

    private fun member(tag: String) =
        ProxyProfile.Trojan(
            id = "id-$tag",
            displayName = tag,
            groupId = "g",
            server = "$tag.example.com",
            serverPort = 443,
            password = "pw",
        )

    private fun order(
        tags: List<String>,
        health: Map<String, EndpointHealth>,
    ) = orderByEndpointHealth(tags.map(::member), health, now)

    private fun ranked(vararg tags: String) = EndpointHealthOrder.Ranked(tags.map(::member))

    @Test
    fun `unexpired down endpoint is excluded`() {
        val statuses = mapOf("a" to health(EndpointHealthStatus.HEALTHY), "b" to health(EndpointHealthStatus.DOWN))
        assertEquals(ranked("a"), order(listOf("a", "b"), statuses))
    }

    @Test
    fun `expired down endpoint keeps its neutral position`() {
        val statuses =
            mapOf(
                "a" to health(EndpointHealthStatus.HEALTHY),
                "b" to health(EndpointHealthStatus.DOWN, observedSecondsAgo = 600, ttlSeconds = 600),
            )
        assertEquals(ranked("b", "a"), order(listOf("b", "a"), statuses))
    }

    @Test
    fun `degraded endpoint is ordered after unknown and missing health`() {
        val statuses = mapOf("a" to health(EndpointHealthStatus.DEGRADED), "b" to health(EndpointHealthStatus.UNKNOWN))
        assertEquals(ranked("b", "c", "a"), order(listOf("a", "b", "c"), statuses))
    }

    @Test
    fun `healthy is neutral and not promoted above unknown`() {
        val statuses = mapOf("a" to health(EndpointHealthStatus.UNKNOWN), "b" to health(EndpointHealthStatus.HEALTHY))
        assertEquals(ranked("a", "b"), order(listOf("a", "b"), statuses))
    }

    @Test
    fun `every endpoint down fails open with all members in declared order`() {
        val statuses = mapOf("a" to health(EndpointHealthStatus.DOWN), "b" to health(EndpointHealthStatus.DOWN))
        assertEquals(
            EndpointHealthOrder.AllDownFailOpen(listOf(member("a"), member("b"))),
            order(listOf("a", "b"), statuses),
        )
    }

    @Test
    fun `stored group health is re-evaluated so a down member returns after its ttl`() {
        val group =
            ProxyGroup(
                id = "g",
                name = "g",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = true,
                members = listOf(member("a"), member("b")),
                memberHealth = mapOf("a" to health(EndpointHealthStatus.DOWN, observedSecondsAgo = 0, ttlSeconds = 60)),
            )
        assertEquals(ranked("b"), group.membersByHealth(now + 59_999))
        assertEquals(ranked("a", "b"), group.membersByHealth(now + 60_000))
    }
}
