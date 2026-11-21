package com.poyka.ripdpi.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the chain-relay part of the [ResolvedRipDpiRelayConfig] wire contract and its [RelayChainSection] fold. */
class RelayChainNativeConfigTest {
    // Mirrors `relayJson` in RipDpiRelay: unknown keys ignored, defaults omitted.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `chain hop fingerprint is required`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(
                ResolvedChainRelayHopConfig.serializer(),
                """{"kind":"vless_reality","profileId":"hop"}""",
            )
        }
    }

    @Test
    fun `chain section folds the flat two-hop wire fields into an ordered hop list`() {
        val section = relayNativeChainFixture().toSections().chain

        assertEquals("entry + exit fold to a 2-element list", 2, section.hops.size)

        val entry = section.entry
        assertEquals("chain-entry-server", entry?.server)
        assertEquals(1111, entry?.serverPort)
        assertEquals("chain-entry-profile-id", entry?.profileId)
        assertEquals("chain-entry-uuid", entry?.uuid)
        assertEquals("vless_reality", entry?.config?.kind)

        val exit = section.exit
        assertEquals("chain-exit-server", exit?.server)
        assertEquals(2222, exit?.serverPort)
        assertEquals("chain-exit-profile-id", exit?.profileId)
        assertEquals("chain-exit-uuid", exit?.uuid)
        assertEquals("masque", exit?.config?.kind)
    }

    @Test
    fun `current chain config carries required schema and round trips`() {
        val config = relayNativeChainFixture()
        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
        val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)

        assertTrue(encoded.contains("\"schemaVersion\":10"))
        assertEquals(config, decoded.toSections().toResolvedConfig())
    }

    @Test
    fun `chain section rejects an out-of-range hop count with a typed error`() {
        val hop =
            ResolvedChainRelayHopRef(
                config = null,
                server = "hop.example",
                serverPort = 443,
                serverName = "hop-sni.example",
                publicKey = "",
                shortId = "",
                flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
                xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
                profileId = "hop-profile",
                uuid = null,
            )

        val tooFew =
            try {
                RelayChainSection(hops = listOf(hop))
                null
            } catch (error: RelayChainHopCountException) {
                error
            }
        assertEquals("1 hop is below the minimum", 1, tooFew?.hopCount)

        val tooMany =
            try {
                RelayChainSection(hops = List(RelayChainMaxHops + 1) { hop })
                null
            } catch (error: RelayChainHopCountException) {
                error
            }
        assertEquals("5 hops exceeds the maximum", RelayChainMaxHops + 1, tooMany?.hopCount)

        // The in-range boundaries construct without raising.
        assertEquals(RelayChainMinHops, RelayChainSection(hops = List(RelayChainMinHops) { hop }).hops.size)
        assertEquals(RelayChainMaxHops, RelayChainSection(hops = List(RelayChainMaxHops) { hop }).hops.size)
    }

    @Test
    fun `three-hop chain crosses the flat wire as an ordered chainHops list`() {
        // Author a genuine 3-hop chain via the ordered list and confirm the
        // middle hop is reachable end-to-end: it serializes to a `chainHops`
        // array, deserializes back, folds into a 3-element section, and the wire
        // shape is idempotent across a full fold/unfold/re-encode cycle.
        val config = relayNativeThreeHopChainFixture()

        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
        val wire = json.parseToJsonElement(encoded) as JsonObject
        val wireHops = wire["chainHops"] as JsonArray
        assertEquals("chainHops carries all 3 ordered hops", 3, wireHops.size)
        assertEquals(
            "chain-entry-profile-id",
            (wireHops[0] as JsonObject).getValue("profileId").jsonPrimitive.content,
        )
        assertEquals(
            "chain-middle-profile-id",
            (wireHops[1] as JsonObject).getValue("profileId").jsonPrimitive.content,
        )
        assertEquals(
            "chain-exit-profile-id",
            (wireHops[2] as JsonObject).getValue("profileId").jsonPrimitive.content,
        )
        // The derived entry/exit scalars still mirror hop[0] / hop[last].
        assertEquals("entry.example", wire.getValue("chainEntryServer").jsonPrimitive.content)
        assertEquals("exit.example", wire.getValue("chainExitServer").jsonPrimitive.content)

        val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)
        val section = decoded.toSections().chain
        assertEquals("the ordered list survives the wire", 3, section.hops.size)
        assertEquals("chain-entry-profile-id", section.hops[0].profileId)
        assertEquals("chain-middle-profile-id", section.hops[1].profileId)
        assertEquals("chain-exit-profile-id", section.hops[2].profileId)
        assertEquals("middle.example", section.hops[1].server)
        assertEquals("shadowsocks", section.hops[1].config?.kind)

        // The fold/unfold/re-encode cycle is idempotent: the N-hop wire object is
        // stable, so no hop (including the middle one) is lost on the round trip.
        val reEncoded =
            json.encodeToString(
                ResolvedRipDpiRelayConfig.serializer(),
                decoded.toSections().toResolvedConfig(),
            )
        assertEquals("3-hop wire object is stable across fold/unfold/re-encode", encoded, reEncoded)
    }
}
