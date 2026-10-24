package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One-way local upgrade. The storage owner must persist these bytes before exposing typed groups. */
fun migrateStoredHysteriaPortHopping(stored: String): String {
    var changed = false
    val groups =
        RipDpiJson.parseToJsonElement(stored).jsonArray.map { element ->
            val group = element.jsonObject
            val members = group["members"] as? JsonArray ?: return@map element
            val upgraded =
                members.map { member ->
                    migrateStoredHysteriaProfile(member.jsonObject).also { if (it != member) changed = true }
                }
            if (upgraded == members.toList()) element else JsonObject(group + ("members" to JsonArray(upgraded)))
        }
    return if (changed) JsonArray(groups).toString() else stored
}

/** Also used for explicit backup restore; it never mutates or reissues migration receipt authority. */
fun migrateStoredHysteriaProfile(profile: JsonObject): JsonObject {
    if (profile["type"]?.jsonPrimitive?.content != "hysteria2" || LegacyHoppingFields.none(profile::containsKey)) {
        return profile
    }
    val ports = profile.legacyString("portHopPorts")
    val interval = profile.legacyString("portHopInterval")?.let(::parseHysteria2HopDuration)
    val legacy = hysteria2PortHopping(ports?.replace(':', '-'), interval)
    val current = profile["portHopping"]
    if (current != null && current != JsonNull) {
        val decoded = RipDpiStrictJson.decodeFromJsonElement(Hysteria2PortHopping.serializer(), current)
        require(decoded == legacy) { "Stored Hysteria hopping configurations conflict" }
    } else {
        require(current == null || legacy == null) { "Stored Hysteria hopping configurations conflict" }
    }
    val fields = profile - LegacyHoppingFields
    return if (legacy == null) {
        JsonObject(fields)
    } else {
        JsonObject(
            fields + ("portHopping" to RipDpiJson.encodeToJsonElement(Hysteria2PortHopping.serializer(), legacy)),
        )
    }
}

private fun JsonObject.legacyString(key: String): String? {
    val value = get(key)
    return if (value == null || value == JsonNull) {
        null
    } else {
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "Stored Hysteria hopping field must be a string"
        }
    }
}

private val LegacyHoppingFields = setOf("portHopPorts", "portHopInterval")
