package com.poyka.ripdpi.core.codec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal data class PacketBackendOptions(
    val requested: Boolean = false,
    val socketPath: String? = null,
)

internal fun insertWireFields(
    original: JsonObject,
    fields: Map<String, JsonElement>,
    before: (String) -> Boolean,
): JsonObject {
    if (fields.isEmpty()) return original
    return buildJsonObject {
        var inserted = false
        for ((key, value) in original) {
            if (!inserted && before(key)) {
                fields.forEach { (field, content) -> put(field, content) }
                inserted = true
            }
            put(key, value)
        }
        check(inserted) { "Native config did not include the packet backend field anchor" }
    }
}
