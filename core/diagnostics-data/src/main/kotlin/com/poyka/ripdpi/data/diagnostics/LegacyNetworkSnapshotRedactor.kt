package com.poyka.ripdpi.data.diagnostics

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Mirrors the capture-time storage projection without depending on :core:diagnostics. */
internal fun redactLegacyNetworkSnapshot(payloadJson: String): String? =
    try {
        val source = Json.parseToJsonElement(payloadJson) as? JsonObject ?: return null
        requireLegacyNetworkSnapshotShape(source)
        val values = source.toMutableMap()
        values.redactAddressList("dnsServers")
        values.redactAddressList("localAddresses")
        values["privateDnsMode"]?.let { mode ->
            val raw = mode.stringValue()
            val normalized = raw.lowercase()
            values["privateDnsMode"] =
                JsonPrimitive(
                    if (normalized in coarsePrivateDnsModes) normalized else "strict",
                )
        }
        values.redactDetails(
            "wifiDetails",
            identityFields = listOf("ssid", "bssid"),
            nullableAddressFields = listOf("gateway", "dhcpServer", "ipAddress", "subnetMask"),
            removedFields = listOf("networkId"),
        )
        values.redactDetails(
            "cellularDetails",
            identityFields =
                listOf("carrierName", "simOperatorName", "networkOperatorName", "operatorCode", "simOperatorCode"),
            removedFields = listOf("carrierId", "simCarrierId"),
        )
        JsonObject(values).toString()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private val coarsePrivateDnsModes =
    setOf("system", "off", "none", "opportunistic", "strict", "unknown", "unavailable")

private fun JsonElement.stringValue(): String {
    val primitive = this as? JsonPrimitive
    require(primitive != null && primitive.isString)
    return primitive.content
}

private fun MutableMap<String, JsonElement>.redactAddressList(key: String) {
    val value = this[key] ?: return
    require(value is JsonArray)
    value.forEach { element -> require(element is JsonPrimitive && element.isString) }
    this[key] = JsonArray(List(value.size) { JsonPrimitive("redacted") })
}

private fun MutableMap<String, JsonElement>.redactDetails(
    key: String,
    identityFields: List<String>,
    nullableAddressFields: List<String> = emptyList(),
    removedFields: List<String>,
) {
    val value = this[key] ?: return
    if (value == JsonNull) return
    require(value is JsonObject)
    val details = value.toMutableMap()
    identityFields.forEach { field ->
        details[field]?.let { raw ->
            val safe = if (raw.stringValue().equals("unknown", ignoreCase = true)) "unknown" else "redacted"
            details[field] = JsonPrimitive(safe)
        }
    }
    nullableAddressFields.forEach { field ->
        details[field]?.let { raw ->
            if (raw != JsonNull) {
                raw.stringValue()
                details[field] = JsonPrimitive("redacted")
            }
        }
    }
    removedFields.forEach { field -> if (field in details) details[field] = JsonNull }
    this[key] = JsonObject(details)
}
