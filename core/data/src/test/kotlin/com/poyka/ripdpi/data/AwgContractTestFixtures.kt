package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Client-owned transport input; the producer's vendored IPv4-only artifacts stay unchanged. */
internal fun JsonObject.withClientDualStackTransport(): JsonObject {
    val ripdpi = this["ripdpi"]?.jsonObject ?: this
    val addresses = JsonArray(listOf(JsonPrimitive("10.66.66.2/32"), JsonPrimitive("fd42:42:42::2/128")))
    val entries =
        ripdpi.getValue("amneziawg").jsonArray.map { value ->
            JsonObject(value.jsonObject + ("address" to addresses))
        }
    val changed = JsonObject(ripdpi + ("amneziawg" to JsonArray(entries)))
    return if ("ripdpi" in this) JsonObject(this + ("ripdpi" to changed)) else changed
}
