package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * (A) Member persistence — the refresh worker must fold parsed members back onto
 * the group rather than discarding them (audit finding P1-3). These exercise the
 * pure mapping the worker delegates to.
 */
class SubscriptionMemberPersistenceTest {
    private fun group(
        members: List<ProxyProfile> = emptyList(),
        failover: SelectorFailover? = null,
    ) = ProxyGroup(
        id = "g1",
        name = "Group",
        type = ProxyGroupType.SUBSCRIPTION,
        order = 0,
        isSelector = true,
        subscription = Subscription(link = "https://example.com/g1"),
        members = members,
        failover = failover,
    )

    private fun trojan(id: String) =
        ProxyProfile.Trojan(
            id = id,
            displayName = id,
            groupId = "g1",
            server = "$id.example.com",
            serverPort = 443,
            password = "pw-$id",
        )

    private fun sharedEndpointTrojan(
        id: String,
        displayName: String,
        password: String,
    ) = ProxyProfile.Trojan(
        id = id,
        displayName = displayName,
        groupId = "g1",
        server = "shared.example.com",
        serverPort = 443,
        password = password,
    )

    private fun rawConfig(id: String) = ProxyProfile.RawConfig(id = id, displayName = id, groupId = "g1", config = "{}")

    @Test
    fun `parsed members are persisted onto the group`() {
        val members = listOf(trojan("a"), trojan("b"))

        val updated = SubscriptionMemberPersistence.apply(group(), members)

        assertEquals(members, updated.members)
    }

    @Test
    fun `a urltest failover policy is carried onto the group`() {
        val failover = SelectorFailover(probeUrl = "https://probe", intervalSeconds = 60, toleranceMs = 50)

        val updated = SubscriptionMemberPersistence.apply(group(), listOf(trojan("a")), failover)

        assertEquals(failover, updated.failover)
    }

    @Test
    fun `a refresh with no failover preserves the existing group policy`() {
        val existing = SelectorFailover(probeUrl = "https://probe", intervalSeconds = 30, toleranceMs = 10)

        val updated =
            SubscriptionMemberPersistence.apply(
                group(failover = existing),
                listOf(trojan("a")),
                failover = null,
            )

        // A plain base64 refresh must not erase a previously-imported urltest policy.
        assertEquals(existing, updated.failover)
    }

    @Test
    fun `an empty refresh leaves the group untouched`() {
        val original = group(members = listOf(trojan("a")))

        val updated = SubscriptionMemberPersistence.apply(original, members = emptyList())

        // An empty parse is not a successful update; the good members are kept.
        assertSame(original, updated)
    }

    @Test
    fun `members replace the previous set wholesale`() {
        val original = group(members = listOf(trojan("old1"), trojan("old2")))
        val refreshed = listOf(trojan("new1"))

        val updated = SubscriptionMemberPersistence.apply(original, refreshed)

        assertEquals(refreshed, updated.members)
        assertNull(updated.members.firstOrNull { it.id.startsWith("old") })
    }

    @Test
    fun `non-activatable RawConfig members are dropped from the candidate set (P1-3)`() {
        val updated =
            SubscriptionMemberPersistence.apply(
                group(),
                listOf(trojan("a"), rawConfig("tuic"), trojan("b"), rawConfig("vmess")),
            )

        assertEquals(listOf("a", "b"), updated.members.map { it.id })
    }

    @Test
    fun `a refresh of only non-activatable nodes leaves the existing members untouched`() {
        val original = group(members = listOf(trojan("good")))

        val updated = SubscriptionMemberPersistence.apply(original, listOf(rawConfig("tuic"), rawConfig("vmess")))

        assertSame(original, updated)
    }

    @Test
    fun `a refreshed member keeps its prior id so the selector selection survives (P1-14)`() {
        val original = group(members = listOf(trojan("kept-id")))
        // Same endpoint, fresh (random) id — what a re-parse of the same node produces.
        val refreshed = listOf(trojan("kept-id").copy(id = "fresh-random-id"))

        val updated = SubscriptionMemberPersistence.apply(original, refreshed)

        assertEquals("kept-id", updated.members.single().id)
    }

    @Test
    fun `duplicate endpoints consume distinct prior ids and preserve refreshed fields`() {
        val original =
            group(
                members =
                    listOf(
                        sharedEndpointTrojan("prior-first", "Old first", "old-first-password"),
                        sharedEndpointTrojan("prior-second", "Old second", "old-second-password"),
                    ),
            )
        val refreshed =
            listOf(
                sharedEndpointTrojan("fresh-first", "Refreshed first", "refreshed-first-password"),
                sharedEndpointTrojan("fresh-second", "Refreshed second", "refreshed-second-password"),
                sharedEndpointTrojan("fresh-excess", "Refreshed excess", "refreshed-excess-password"),
            )

        val updated = SubscriptionMemberPersistence.apply(original, refreshed)
        val updatedIds = updated.members.map { it.id }

        assertEquals(listOf("prior-first", "prior-second", "fresh-excess"), updatedIds)
        assertEquals(3, updatedIds.distinct().size)
        assertEquals(refreshed.map { it.displayName }, updated.members.map { it.displayName })
        assertEquals(
            refreshed.map { it.password },
            updated.members.map { (it as ProxyProfile.Trojan).password },
        )
    }

    @Test
    fun `a member at a new endpoint keeps its own fresh id`() {
        val original = group(members = listOf(trojan("old")))
        val refreshed = listOf(trojan("brand-new"))

        val updated = SubscriptionMemberPersistence.apply(original, refreshed)

        assertEquals("brand-new", updated.members.single().id)
    }
}
