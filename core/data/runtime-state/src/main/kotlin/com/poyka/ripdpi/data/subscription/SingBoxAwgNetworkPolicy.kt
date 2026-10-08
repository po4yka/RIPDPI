package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Present policy must be an explicit string list; malformed data never clears saved policy. */
internal fun invalidAwgPolicyField(
    obj: JsonObject,
    peer: JsonObject,
): String? =
    when {
        "dns" in obj && !obj["dns"].isNonblankStringList() -> "dns"
        "allowed_ips" in peer && !peer["allowed_ips"].isNonblankStringList() -> "allowed_ips"
        else -> null
    }

private fun JsonElement?.isNonblankStringList(): Boolean =
    (this as? JsonArray)?.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() } == true
