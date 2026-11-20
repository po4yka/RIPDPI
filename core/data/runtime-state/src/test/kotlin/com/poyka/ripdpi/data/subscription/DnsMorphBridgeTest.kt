package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class DnsMorphBridgeTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })

    private fun block(dnsMorph: String): JsonObject = Json.parseToJsonElement("""{"dns_morph":$dnsMorph}""").jsonObject

    @Test
    fun `parses a declared bridge and lowercases its zone`() {
        val bridge =
            parseDnsMorphBridge(
                block(
                    """{"address":"2001:db8::53","port":5353,"zone":"Morph.Example",
                    "public_key":"$key","token":"$key"}""",
                ),
            )

        assertEquals(DnsMorphBridge("2001:db8::53", 5353, "morph.example", key, key), bridge)
    }

    @Test
    fun `absent block declares no channel`() {
        assertNull(parseDnsMorphBridge(JsonObject(emptyMap())))
    }

    @Test
    fun `rejects every invalid declaration`() {
        val valid =
            mapOf(
                "address" to "\"192.0.2.1\"",
                "port" to "53",
                "zone" to "\"m.example\"",
                "public_key" to "\"$key\"",
                "token" to "\"$key\"",
            )
        val invalid =
            listOf(
                "address" to "\"bridge.example\"",
                "address" to "\"0.0.0.0/8\"",
                "port" to "0",
                "port" to "\"53\"",
                "zone" to "\"bad_zone.example\"",
                "zone" to "\"-m.example\"",
                "public_key" to "\"${Base64.getEncoder().encodeToString(ByteArray(31))}\"",
                "public_key" to "\"not base64\"",
                "token" to "\"${Base64.getEncoder().encodeToString(ByteArray(16))}\"",
                "token" to "\"${key.trimEnd('=')}\"",
                "address" to "\"224.0.0.1\"",
                "address" to "\"0.0.0.0\"",
                "zone" to "\"${"a".repeat(27)}.${"b".repeat(27)}\"",
            )
        for ((field, value) in invalid) {
            val json = (valid + (field to value)).entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
            assertThrows(json, IllegalArgumentException::class.java) { parseDnsMorphBridge(block(json)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parseDnsMorphBridge(block("[]")) }
        assertThrows(IllegalArgumentException::class.java) {
            parseDnsMorphBridge(
                block(
                    """{"address":"192.0.2.1","port":53,"zone":"m.example",
                    "public_key":"$key","token":"$key","x":1}""",
                ),
            )
        }
    }
}
