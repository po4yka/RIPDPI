package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (A) The additive [ProxyGroup.members] / [ProxyGroup.failover] fields must be
 * backward- and forward-compatible: an older persisted payload that predates them
 * deserializes with the empty/`null` defaults, and a group carrying members round
 * trips. `RipDpiJson` has no `encodeDefaults`, so an empty member set is omitted
 * from the on-disk JSON, keeping existing serialization (and backup output) byte
 * stable.
 */
class ProxyGroupMembersSerializationTest {
    private val json = RipDpiJson
    private val serializer = ProxyGroup.serializer()

    @Test
    fun `a legacy payload without members deserializes with empty defaults`() {
        // The exact shape persisted before the members/failover fields existed.
        val legacy =
            """{"id":"g1","name":"Group","type":"SUBSCRIPTION","order":0,"isSelector":true}"""

        val group = json.decodeFromString(serializer, legacy)

        assertTrue(group.members.isEmpty())
        assertEquals(null, group.failover)
    }

    @Test
    fun `an empty-member group does not serialize the members field`() {
        val group =
            ProxyGroup(
                id = "g1",
                name = "Group",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = true,
            )

        val encoded = json.encodeToString(serializer, group)

        // No encodeDefaults => empty members / null failover are omitted, so the
        // bytes match what older builds wrote.
        assertTrue("members" !in encoded)
        assertTrue("failover" !in encoded)
    }

    @Test
    fun `a group with members and failover round-trips`() {
        val group =
            ProxyGroup(
                id = "g1",
                name = "Group",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = true,
                members =
                    listOf(
                        ProxyProfile.Trojan(
                            id = "a",
                            displayName = "a",
                            groupId = "g1",
                            server = "a.example.com",
                            serverPort = 443,
                            password = "pw",
                        ),
                    ),
                failover = SelectorFailover(probeUrl = "https://probe", intervalSeconds = 60, toleranceMs = 50),
            )

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, group))

        assertEquals(group, decoded)
    }
}
