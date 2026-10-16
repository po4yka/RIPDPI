package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.toActivationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgDeployedTransportTest {
    private val bundle =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.getResourceAsStream("/contract/ripdpi-bundle.golden-full.json"))
                    .bufferedReader()
                    .use { it.readText() },
            ).jsonObject
    private val ripdpi = bundle.getValue("ripdpi").jsonObject
    private val entry =
        ripdpi
            .getValue("amneziawg")
            .jsonArray
            .single()
            .jsonObject

    @Test
    fun `canonical emitted bundle retains exact host addresses and MTU boundaries`() {
        listOf(1280, 1330, 1420).forEach { mtu ->
            val parsed = parse(JsonObject(entry + ("mtu" to JsonPrimitive(mtu))))
            val request = parsed.amneziaWgProfiles.single().toActivationRequest()
            val addresses = entry.getValue("address").jsonArray.map { (it as JsonPrimitive).content }
            assertEquals(addresses[0], request.interfaceAddressV4)
            assertEquals(addresses[1], request.interfaceAddressV6)
            assertEquals(mtu, request.mtu)
            assertTrue(parsed.skipped.isEmpty())
        }
    }

    @Test
    fun `invalid supplied MTU is rejected without default or sibling loss`() {
        listOf<JsonElement?>(
            null,
            JsonNull,
            JsonPrimitive(1279),
            JsonPrimitive(1421),
            JsonPrimitive(-1),
            JsonPrimitive("1280"),
            JsonPrimitive(1280.5),
            JsonPrimitive(true),
            JsonArray(emptyList()),
        ).forEach { value -> assertInvalid("mtu", value) }
    }

    @Test
    fun `invalid address shape order family prefix or literal is rejected`() {
        listOf<JsonElement?>(
            null,
            JsonNull,
            JsonPrimitive("10.77.0.2/32"),
            JsonArray(emptyList()),
            addresses("10.77.0.2/32"),
            addresses("fd77::2/128", "10.77.0.2/32"),
            addresses("10.77.0.2/32", "10.77.0.3/32"),
            addresses("10.77.0.2/24", "fd77::2/128"),
            addresses("10.77.0.2/32", "fd77::2/64"),
            addresses("999.77.0.2/32", "fd77::2/128"),
            addresses("host.invalid/32", "fd77::2/128"),
            addresses("10.77.0.2/32", "fd77:::2/128"),
            addresses("10.77.0.2/32", "fd77::2/128", "fd77::3/128"),
            JsonArray(listOf(JsonPrimitive("10.77.0.2/32"), JsonPrimitive(6))),
        ).forEach { value -> assertInvalid("address", value) }
    }

    private fun assertInvalid(
        field: String,
        value: JsonElement?,
    ) {
        val changed = if (value == null) JsonObject(entry - field) else JsonObject(entry + (field to value))
        val parsed = parse(changed, entry)
        assertEquals("$field=$value", 1, parsed.amneziaWgProfiles.size)
        assertEquals(SingBoxSkipReason.INVALID_NETWORK_POLICY, parsed.skipped.single().reason)
        assertEquals(field, parsed.skipped.single().detail)
        assertTrue(parsed.profiles.isNotEmpty())
    }

    private fun addresses(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    private fun parse(vararg entries: JsonObject): SingBoxParseResult.Success =
        SingBoxSubscriptionParser.parse(
            JsonObject(
                bundle + ("ripdpi" to JsonObject(ripdpi + ("amneziawg" to JsonArray(entries.toList())))),
            ).toString(),
            "deployed-transport",
        ) as SingBoxParseResult.Success
}
