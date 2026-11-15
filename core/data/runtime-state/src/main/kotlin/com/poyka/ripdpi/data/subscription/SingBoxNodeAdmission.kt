package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Rejects connection framing and chaining that the native profile cannot retain. */
internal fun unsupportedSingBoxConnectionOptions(
    obj: JsonObject,
    index: Int,
    label: String,
): SingBoxSkippedNode? {
    val multiplex = obj["multiplex"] as? JsonObject
    val multiplexEnabled = (multiplex?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
    val option =
        when {
            obj["detour"]?.let { it != JsonPrimitive("") } == true -> "detour"
            multiplexEnabled -> "multiplex"
            else -> return null
        }
    return SingBoxSkippedNode(index, label, SingBoxSkipReason.UNSUPPORTED_TRANSPORT, option)
}
