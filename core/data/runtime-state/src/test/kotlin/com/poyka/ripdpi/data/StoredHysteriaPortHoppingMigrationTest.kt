package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class StoredHysteriaPortHoppingMigrationTest {
    @Test
    fun `local upgrade retains raw unknown data and owner while replacing dormant fields once`() {
        val stored =
            """
            [{"id":"group","ownershipGeneration":"owner","future":{"value":7},
            "members":[{"type":"hysteria2","portHopPorts":"5000:5002",
            "portHopInterval":"30s","future-profile":true}]}]
            """.trimIndent()
        val upgraded = migrateStoredHysteriaPortHopping(stored)
        val group =
            RipDpiJson
                .parseToJsonElement(upgraded)
                .jsonArray
                .single()
                .jsonObject
        val member =
            group
                .getValue("members")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("owner", group.getValue("ownershipGeneration").jsonPrimitive.content)
        assertEquals("{\"value\":7}", group.getValue("future").toString())
        assertEquals("true", member.getValue("future-profile").toString())
        assertFalse("portHopPorts" in member)
        assertFalse("portHopInterval" in member)
        assertEquals(
            Hysteria2PortHopping("5000-5002"),
            RipDpiJson.decodeFromJsonElement(Hysteria2PortHopping.serializer(), member.getValue("portHopping")),
        )
        assertEquals(upgraded, migrateStoredHysteriaPortHopping(upgraded))
    }

    @Test
    fun `conflicting or malformed dormant state is rejected without a scalar fallback`() {
        listOf(
            """"portHopPorts":"5000:5002","portHopInterval":"4s"""",
            """"portHopPorts":null,"portHopInterval":"30s"""",
            """"portHopPorts":"5002:5000","portHopInterval":"30s"""",
            """"portHopPorts":"5000:5002","portHopInterval":30""",
            """"portHopPorts":"5000:5002","portHopping":null""",
            """"portHopPorts":"5000:5002","portHopping":{"ports":"6000-6002"}""",
        ).forEach { fields ->
            val stored = """[{"members":[{"type":"hysteria2",$fields}]}]"""
            assertThrows(IllegalArgumentException::class.java) { migrateStoredHysteriaPortHopping(stored) }
        }
    }

    @Test
    fun `equal current and dormant models collapse to the single current model`() {
        val stored =
            """
            {"type":"hysteria2","portHopPorts":"5000:5002","portHopInterval":"30s",
            "portHopping":{"ports":"5000-5002"}}
            """.trimIndent()
        val profile = RipDpiJson.parseToJsonElement(stored).jsonObject
        val migrated = migrateStoredHysteriaProfile(profile)
        assertEquals(profile - setOf("portHopPorts", "portHopInterval"), migrated)
    }
}
