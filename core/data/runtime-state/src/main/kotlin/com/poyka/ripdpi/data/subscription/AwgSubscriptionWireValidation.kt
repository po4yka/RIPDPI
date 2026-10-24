package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val AwgWireKeys =
    setOf(
        "wire_profile",
        "tag",
        "address",
        "dns",
        "mtu",
        "jc",
        "jmin",
        "jmax",
        "s1",
        "s2",
        "s3",
        "s4",
        "private_key",
        "h1",
        "h2",
        "h3",
        "h4",
        "i1",
        "i2",
        "i3",
        "i4",
        "i5",
        "cohort_fingerprint",
        "private_key_placeholder",
        "peer",
    )

internal fun invalidAwgWireField(obj: JsonObject): String? =
    obj.keys.firstOrNull { it !in AwgWireKeys } ?: when {
        "private_key" in obj && (obj["private_key"] as? JsonPrimitive)?.isString != true -> "private_key"
        obj["wire_profile"] != JsonPrimitive(AmneziaWgWireProfile.ID) -> "wire_profile"
        else -> invalidAwgScalarField(obj) ?: invalidAwgStringField(obj)
    }

private fun invalidAwgScalarField(obj: JsonObject): String? =
    (listOf("jc", "jmin", "jmax", "s1", "s2") + listOf("s3", "s4").filter { it in obj }).firstOrNull { key ->
        val value = obj[key] as? JsonPrimitive
        value == null || value.isString || value.intOrNull == null
    }

private fun invalidAwgStringField(obj: JsonObject): String? {
    val signatures = listOf("i1", "i2", "i3", "i4", "i5").filter { it in obj }
    return signatures.firstOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty() }
        ?: (listOf("h1", "h2", "h3", "h4") + signatures)
            .firstOrNull { key -> (obj[key] as? JsonPrimitive)?.isString != true }
}
