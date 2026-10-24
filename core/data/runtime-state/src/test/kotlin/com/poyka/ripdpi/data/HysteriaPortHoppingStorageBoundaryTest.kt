package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaPortHoppingStorageBoundaryTest {
    @Test
    fun `outer current schema persists upgraded bytes before exposing hopping and restart is stable`() {
        var stored = LegacyGroup
        var writes = 0
        val accepted =
            ProxyGroupSchema.read(stored, 2) {
                stored = it
                writes++
            }
        assertEquals(stored, accepted)
        assertEquals(1, writes)
        val group = RipDpiJson.decodeFromString(ListSerializer(ProxyGroup.serializer()), accepted).single()
        val profile = group.members.single() as ProxyProfile.Hysteria2
        assertEquals(Hysteria2PortHopping("20000-21000", 45_000), profile.portHopping)
        assertEquals("captured-owner", group.ownershipGeneration)
        assertTrue(stored.contains("futureGroup"))
        assertFalse(stored.contains("portHopPorts"))
        assertEquals(accepted, ProxyGroupSchema.read(stored, 2) { writes++ })
        assertEquals(1, writes)
    }

    @Test
    fun `failed storage commit exposes no upgraded state and retains original bytes`() {
        var exposed: String? = null
        assertTrue(
            runCatching {
                exposed = ProxyGroupSchema.read(LegacyGroup, 2) { error("Storage commit failed") }
            }.isFailure,
        )
        assertEquals(null, exposed)
        assertTrue(LegacyGroup.contains("portHopPorts"))
    }

    @Test
    fun `malformed old hopping is rejected before storage mutation`() {
        var writes = 0
        assertTrue(
            runCatching {
                ProxyGroupSchema.read(LegacyGroup.replace("45s", "4s"), 2) { writes++ }
            }.isFailure,
        )
        assertEquals(0, writes)
    }
}

private val LegacyGroup =
    """
    [{"id":"group","name":"Group","type":"SUBSCRIPTION","order":0,"isSelector":true,
    "ownershipGeneration":"captured-owner","futureGroup":true,
    "members":[{"type":"hysteria2","id":"hy","displayName":"Hysteria","groupId":"group",
    "server":"hopping.example","serverPort":443,"password":"fixture",
    "portHopPorts":"20000:21000","portHopInterval":"45s"}]}]
    """.trimIndent()
