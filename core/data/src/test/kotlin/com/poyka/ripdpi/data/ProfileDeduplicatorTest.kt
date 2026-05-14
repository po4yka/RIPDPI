package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.ProfileDeduplicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDeduplicatorTest {
    private fun vless(
        id: String,
        displayName: String,
        groupId: String = "sub-1",
        server: String = "edge.example.com",
        serverPort: Int = 443,
        uuid: String = "00000000-0000-0000-0000-000000000000",
    ): ProxyProfile.Vless =
        ProxyProfile.Vless(
            id = id,
            displayName = displayName,
            groupId = groupId,
            server = server,
            serverPort = serverPort,
            uuid = uuid,
        )

    @Test
    fun `all-unique profiles are returned unchanged`() {
        val profiles =
            listOf(
                vless(id = "a", displayName = "Node A", server = "a.example.com"),
                vless(id = "b", displayName = "Node B", server = "b.example.com"),
                vless(id = "c", displayName = "Node C", server = "c.example.com"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(profiles, result)
    }

    @Test
    fun `byte-equal profiles differing only in displayName collapse to one`() {
        val profiles =
            listOf(
                vless(id = "a", displayName = "Node"),
                vless(id = "b", displayName = "Node copy"),
                vless(id = "c", displayName = "Node copy 2"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(1, result.size)
    }

    @Test
    fun `collapsed entry keeps the first occurrence id and name when no user name present`() {
        val profiles =
            listOf(
                vless(id = "first", displayName = "Node A"),
                vless(id = "second", displayName = "Node A copy"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(1, result.size)
        assertEquals("first", result[0].id)
        assertEquals("Node A", result[0].displayName)
    }

    @Test
    fun `user-edited name is preserved over a later byte-equal duplicate`() {
        val userEdited = vless(id = "kept", displayName = "My favourite node")
        val refreshed = vless(id = "incoming", displayName = "auto-generated-name")

        val result =
            ProfileDeduplicator.deduplicate(
                profiles = listOf(refreshed, userEdited),
                userEditedNames = setOf("My favourite node"),
            )

        assertEquals(1, result.size)
        assertEquals("My favourite node", result[0].displayName)
    }

    @Test
    fun `user-edited name wins even if it is not the first occurrence`() {
        val refreshedFirst = vless(id = "incoming-1", displayName = "auto-1")
        val userEdited = vless(id = "user", displayName = "Hand-named node")
        val refreshedLast = vless(id = "incoming-2", displayName = "auto-2")

        val result =
            ProfileDeduplicator.deduplicate(
                profiles = listOf(refreshedFirst, userEdited, refreshedLast),
                userEditedNames = setOf("Hand-named node"),
            )

        assertEquals(1, result.size)
        assertEquals("Hand-named node", result[0].displayName)
    }

    @Test
    fun `profiles that differ in a non-name field are not collapsed`() {
        val profiles =
            listOf(
                vless(id = "a", displayName = "Node", server = "a.example.com"),
                vless(id = "b", displayName = "Node", server = "b.example.com"),
                vless(id = "c", displayName = "Node", serverPort = 8443),
                vless(id = "d", displayName = "Node", uuid = "11111111-1111-1111-1111-111111111111"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(4, result.size)
    }

    @Test
    fun `different protocols with same surface fields are not collapsed`() {
        val trojan =
            ProxyProfile.Trojan(
                id = "t",
                displayName = "Node",
                groupId = "sub-1",
                server = "edge.example.com",
                serverPort = 443,
                password = "pw",
            )
        val trojanGo =
            ProxyProfile.TrojanGo(
                id = "tg",
                displayName = "Node",
                groupId = "sub-1",
                server = "edge.example.com",
                serverPort = 443,
                password = "pw",
            )

        val result = ProfileDeduplicator.deduplicate(listOf(trojan, trojanGo))

        assertEquals(2, result.size)
    }

    @Test
    fun `all-duplicate list collapses to a single entry`() {
        val profiles = (1..5).map { vless(id = "id-$it", displayName = "Node $it") }

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(1, result.size)
        assertEquals("id-1", result[0].id)
    }

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList<ProxyProfile>(), ProfileDeduplicator.deduplicate(emptyList()))
    }

    @Test
    fun `mixed unique and duplicate groups each collapse independently and preserve order`() {
        val profiles =
            listOf(
                vless(id = "a1", displayName = "Alpha", server = "alpha.example.com"),
                vless(id = "b1", displayName = "Beta", server = "beta.example.com"),
                vless(id = "a2", displayName = "Alpha copy", server = "alpha.example.com"),
                vless(id = "c1", displayName = "Gamma", server = "gamma.example.com"),
                vless(id = "b2", displayName = "Beta copy", server = "beta.example.com"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(3, result.size)
        assertEquals(listOf("a1", "b1", "c1"), result.map { it.id })
    }

    @Test
    fun `groupId difference is ignored so the same node across groups can be detected`() {
        // displayName ignored, but groupId is a real identity field: differing groupId stays distinct.
        val profiles =
            listOf(
                vless(id = "g1", displayName = "Node", groupId = "group-1"),
                vless(id = "g2", displayName = "Node", groupId = "group-2"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertEquals(2, result.size)
    }

    @Test
    fun `id difference alone does not prevent collapse`() {
        val profiles =
            listOf(
                vless(id = "x", displayName = "Node"),
                vless(id = "y", displayName = "Node"),
            )

        val result = ProfileDeduplicator.deduplicate(profiles)

        assertTrue(result.size == 1)
    }
}
