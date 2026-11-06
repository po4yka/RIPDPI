package com.poyka.ripdpi.data

import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Keeps the flat backup shape while generated serializers own each section's fields. */
internal object AppSettingsSnapshotSerializer : JsonTransformingSerializer<AppSettingsSnapshot>(
    AppSettingsSnapshot.serializer(),
) {
    private val sectionNames =
        (0 until descriptor.elementsCount)
            .filter { descriptor.getElementDescriptor(it).kind == StructureKind.CLASS }
            .map(descriptor::getElementName)
            .toSet()

    override fun transformSerialize(element: JsonElement): JsonElement =
        buildJsonObject {
            for ((name, value) in element.jsonObject) {
                val fields = if (name in sectionNames) value.jsonObject else mapOf(name to value)
                for ((key, fieldValue) in fields) {
                    check(put(key, fieldValue) == null) { "Duplicate settings JSON key: $key" }
                }
            }
        }

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val fields = element.jsonObject
        return JsonObject(
            fields.toMutableMap().apply {
                for (name in sectionNames) {
                    put(name, fields)
                }
            },
        )
    }
}
