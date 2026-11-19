package com.poyka.ripdpi.services.fieldreport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File

internal const val ContractDir = "core/data/src/test/resources/contract"

internal fun contractFile(name: String): File {
    var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    while (directory != null) {
        val candidate = File(directory, "$ContractDir/$name")
        if (candidate.exists()) return candidate
        directory = directory.parentFile
    }
    error("Contract fixture not found: $name")
}

internal fun contractJson(name: String): JsonObject = Json.parseToJsonElement(contractFile(name).readText()).jsonObject

/**
 * Validates against the vendored `client-field-report.schema.json` with the keywords that schema uses, plus the two
 * server checks the schema cannot express (`scripts/validate-field-report.py` in the deploy repository): no string
 * holds a newline, and each latency histogram sums to its bucket's attempts. The validator proves itself against
 * the vendored golden and every vendored negative report fixture.
 */
internal class ClientFieldReportSchema(
    private val root: JsonObject = contractJson("client-field-report.schema.json"),
) {
    fun errors(report: JsonElement): List<String> =
        buildList {
            validate(root, report, "$", this)
            if (containsNewline(report)) add("a string holds a newline")
            (report as? JsonObject)?.get("buckets")?.let { buckets ->
                (buckets as? JsonArray)?.forEachIndexed { index, bucket ->
                    val fields = bucket as? JsonObject ?: return@forEachIndexed
                    val histogram = fields["latency_ms_histogram"] as? JsonArray
                    val sum = histogram?.sumOf { (it as? JsonPrimitive)?.longOrNull ?: 0L }
                    if (sum !=
                        (fields["attempts"] as? JsonPrimitive)?.longOrNull
                    ) {
                        add("$.buckets[$index] histogram sum")
                    }
                }
            }
        }

    private fun containsNewline(value: JsonElement): Boolean =
        when (value) {
            is JsonObject -> value.any { (key, child) -> '\n' in key || containsNewline(child) }
            is JsonArray -> value.any(::containsNewline)
            is JsonPrimitive -> value.isString && '\n' in value.content
        }

    private fun resolve(schema: JsonObject): JsonObject =
        schema["\$ref"]?.jsonPrimitive?.content?.let { ref ->
            ref.removePrefix("#/").split("/").fold(root) { node, key -> node.getValue(key).jsonObject }
        } ?: schema

    private fun matches(
        schema: JsonObject,
        value: JsonElement,
    ): Boolean = mutableListOf<String>().also { validate(schema, value, "$", it) }.isEmpty()

    private fun validate(
        unresolved: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ) {
        val schema = resolve(unresolved)
        schema["const"]?.let { if (it != value) errors += "$path const" }
        schema["enum"]?.let { if (value !in it.jsonArray) errors += "$path enum" }
        val type = schema["type"]?.jsonPrimitive?.content
        if (type != null && !hasType(value, type)) {
            errors += "$path type $type"
            return
        }
        when (value) {
            is JsonObject -> validateObject(schema, value, path, errors)
            is JsonArray -> validateArray(schema, value, path, errors)
            is JsonPrimitive -> validatePrimitive(schema, value, path, errors)
        }
        schema["if"]?.jsonObject?.let { condition ->
            val branch = if (matches(condition, value)) schema["then"] else schema["else"]
            branch?.jsonObject?.let { validate(it, value, path, errors) }
        }
        schema["not"]?.jsonObject?.let { if (matches(it, value)) errors += "$path not" }
    }

    private fun hasType(
        value: JsonElement,
        type: String,
    ): Boolean =
        when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
            else -> error("unsupported schema type $type")
        }

    private fun validateObject(
        schema: JsonObject,
        value: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        val properties = schema["properties"]?.jsonObject.orEmpty()
        schema["required"]?.jsonArray?.forEach { key ->
            if (key.jsonPrimitive.content !in value) errors += "$path.${key.jsonPrimitive.content} required"
        }
        if (schema["additionalProperties"] == JsonPrimitive(false)) {
            (value.keys - properties.keys).forEach { errors += "$path.$it additional" }
        }
        value.forEach { (key, child) -> properties[key]?.let { validate(it.jsonObject, child, "$path.$key", errors) } }
    }

    private fun validateArray(
        schema: JsonObject,
        value: JsonArray,
        path: String,
        errors: MutableList<String>,
    ) {
        schema["minItems"]?.jsonPrimitive?.long?.let { if (value.size < it) errors += "$path minItems" }
        schema["maxItems"]?.jsonPrimitive?.long?.let { if (value.size > it) errors += "$path maxItems" }
        schema["items"]?.jsonObject?.let { items ->
            value.forEachIndexed { index, child -> validate(items, child, "$path[$index]", errors) }
        }
    }

    private fun validatePrimitive(
        schema: JsonObject,
        value: JsonPrimitive,
        path: String,
        errors: MutableList<String>,
    ) {
        if (value.isString) {
            schema["pattern"]?.jsonPrimitive?.content?.let {
                if (!Regex(it).containsMatchIn(value.content)) errors += "$path pattern"
            }
        } else {
            val number = value.content.toBigDecimalOrNull() ?: return
            schema["minimum"]?.jsonPrimitive?.content?.let { if (number < it.toBigDecimal()) errors += "$path minimum" }
            schema["maximum"]?.jsonPrimitive?.content?.let { if (number > it.toBigDecimal()) errors += "$path maximum" }
        }
    }
}
