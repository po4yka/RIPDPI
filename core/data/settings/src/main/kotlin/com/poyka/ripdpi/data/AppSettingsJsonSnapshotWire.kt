package com.poyka.ripdpi.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun AppSettingsSnapshot.toJsonObject(): JsonObject =
    buildJsonObject {
        put("formatVersion", formatVersion)
        writeRootUiRuntimeSnapshot(rootUiRuntime)
        writeDnsSnapshot(dns)
        writeProxyDesyncSnapshot(proxyDesync)
        writeChainSnapshot(chains)
        writeQuicAdaptiveSnapshot(quicAdaptive)
        writeWarpSnapshot(warp)
        writeRelaySnapshot(relay)
        writeRoutingSnapshot(routing)
    }

internal fun JsonObject.toAppSettingsSnapshot(): AppSettingsSnapshot {
    val defaults = AppSettingsSnapshot()
    return AppSettingsSnapshot(
        formatVersion = intValue("formatVersion", defaults.formatVersion),
        rootUiRuntime = readRootUiRuntimeSnapshot(defaults.rootUiRuntime),
        dns = readDnsSnapshot(defaults.dns),
        proxyDesync = readProxyDesyncSnapshot(defaults.proxyDesync),
        chains = readChainSnapshot(defaults.chains),
        quicAdaptive = readQuicAdaptiveSnapshot(defaults.quicAdaptive),
        warp = readWarpSnapshot(defaults.warp),
        relay = readRelaySnapshot(defaults.relay),
        routing = readRoutingSnapshot(defaults.routing),
    )
}

internal fun JsonObject.stringValue(
    name: String,
    default: String,
): String = get(name)?.jsonPrimitive?.contentOrNull ?: default

internal fun JsonObject.intValue(
    name: String,
    default: Int,
): Int = get(name)?.jsonPrimitive?.intOrNull ?: default

internal fun JsonObject.longValue(
    name: String,
    default: Long,
): Long = get(name)?.jsonPrimitive?.longOrNull ?: default

internal fun JsonObject.doubleValue(
    name: String,
    default: Double,
): Double = get(name)?.jsonPrimitive?.doubleOrNull ?: default

internal fun JsonObject.booleanValue(
    name: String,
    default: Boolean,
): Boolean = get(name)?.jsonPrimitive?.booleanOrNull ?: default

internal fun JsonObject.stringListValue(
    name: String,
    default: List<String>,
): List<String> =
    get(name)
        ?.jsonArray
        ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
        ?: default

internal fun JsonObjectBuilder.putStringList(
    name: String,
    values: List<String>,
) {
    put(name, JsonArray(values.map(::JsonPrimitive)))
}

internal inline fun <reified T> JsonObjectBuilder.putSerializable(
    name: String,
    value: T,
) {
    put(name, appSettingsJson.encodeToJsonElement(value))
}

internal fun <T> JsonObjectBuilder.putSerializable(
    name: String,
    value: T,
    serializer: SerializationStrategy<T>,
) {
    put(name, appSettingsJson.encodeToJsonElement(serializer, value))
}

internal inline fun <reified T> JsonObject.serializableValue(
    name: String,
    default: T,
): T = decodeSerializableValue(name, default)

internal fun <T> JsonObject.serializableValue(
    name: String,
    default: T,
    serializer: KSerializer<T>,
): T = decodeSerializableValue(name, default, serializer)

private inline fun <reified T> JsonObject.decodeSerializableValue(
    name: String,
    default: T,
): T {
    val element = get(name) ?: return default
    return appSettingsJson.decodeFromJsonElement<T>(element)
}

private fun <T> JsonObject.decodeSerializableValue(
    name: String,
    default: T,
    serializer: KSerializer<T>,
): T {
    val element: JsonElement = get(name) ?: return default
    return appSettingsJson.decodeFromJsonElement(serializer, element)
}
