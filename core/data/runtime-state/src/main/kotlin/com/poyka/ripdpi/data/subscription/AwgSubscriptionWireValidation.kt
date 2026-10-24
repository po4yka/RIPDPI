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

internal fun invalidAwgWireField(obj: JsonObject): String? {
    obj.keys.firstOrNull { it !in AwgWireKeys }?.let { return it }
    if ("private_key" in obj && (obj["private_key"] as? JsonPrimitive)?.isString != true) return "private_key"
    if (obj["wire_profile"] !=
        JsonPrimitive(AmneziaWgWireProfile.ID)
    ) {
        return "wire_profile"
    }
    (listOf("jc", "jmin", "jmax", "s1", "s2") + listOf("s3", "s4").filter { it in obj }).forEach { key ->
        val value = obj[key] as? JsonPrimitive ?: return key
        if (value.isString || value.intOrNull == null) return key
    }
    listOf("i1", "i2", "i3", "i4", "i5").filter { it in obj }.forEach { key ->
        if ((obj[key] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) return key
    }
    return (listOf("h1", "h2", "h3", "h4") + listOf("i1", "i2", "i3", "i4", "i5").filter { it in obj })
        .firstOrNull { key -> (obj[key] as? JsonPrimitive)?.isString != true }
}
