package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reject settings that cannot survive conversion to a native relay profile. */
internal fun unsupportedNativeVlessOptions(
    outbound: JsonObject,
    settings: JsonObject?,
    stream: JsonObject?,
): String? {
    val peers = settings?.get("vnext") as? JsonArray
    val peer = peers?.firstOrNull() as? JsonObject
    val users = peer?.get("users") as? JsonArray
    val user = users?.firstOrNull() as? JsonObject
    return when {
        !outbound.hasOnly("tag", "protocol", "settings", "streamSettings") -> "VLESS outbound options"
        !settings.hasOnly("vnext") || peers?.size != 1 -> "VLESS settings"
        !peer.hasOnly("address", "port", "users") || users?.size != 1 -> "VLESS peers"
        !user.hasOnly("id", "flow", "encryption", "level", "email") -> "VLESS user options"
        !user.hasStringFields("id", "flow", "encryption", "email") -> "VLESS user options"
        user?.get("encryption")?.let { it != JsonPrimitive("none") } == true -> "VLESS Encryption"
        user?.get("level")?.let { it != JsonPrimitive(0) } == true -> "VLESS user policy"
        else -> unsupportedStreamOptions(stream)
    }
}

private fun unsupportedStreamOptions(stream: JsonObject?): String? {
    val reality = stream?.get("realitySettings") as? JsonObject
    val tls = stream?.get("tlsSettings") as? JsonObject
    val xhttp = stream?.get("xhttpSettings") as? JsonObject
    return when {
        !stream.hasOnly("network", "method", "security", "realitySettings", "tlsSettings", "xhttpSettings") -> {
            "VLESS stream options"
        }

        !stream.hasStringFields("network", "method", "security") -> {
            "VLESS stream options"
        }

        listOf("realitySettings", "tlsSettings", "xhttpSettings").any {
            stream?.containsKey(it) == true && stream[it] !is JsonObject
        } -> {
            "VLESS stream options"
        }

        reality != null && stream?.get("security")?.let { it != JsonPrimitive("reality") } == true -> {
            "REALITY security options"
        }

        tls != null && reality != null -> {
            "VLESS security options"
        }

        !supportsRealityOptions(reality) -> {
            "REALITY options"
        }

        !supportsTlsOptions(tls) -> {
            "TLS options"
        }

        !xhttp.hasOnly("path", "host", "mode") || !xhttp.hasStringFields("path", "host", "mode") -> {
            "XHTTP options"
        }

        xhttp != null && stream?.get("network") != JsonPrimitive("xhttp") -> {
            "XHTTP transport options"
        }

        else -> {
            null
        }
    }
}

private fun supportsRealityOptions(reality: JsonObject?): Boolean =
    reality.hasOnly("publicKey", "serverName", "shortId", "fingerprint", "show") &&
        reality.hasStringFields("publicKey", "serverName", "shortId", "fingerprint") &&
        reality?.get("show") in setOf(null, JsonPrimitive(false))

private fun supportsTlsOptions(tls: JsonObject?): Boolean =
    tls.hasOnly("serverName", "fingerprint", "allowInsecure") &&
        tls.hasStringFields("serverName", "fingerprint") &&
        tls?.get("allowInsecure") in setOf(null, JsonPrimitive(false))

private fun JsonObject?.hasOnly(vararg fields: String): Boolean = this?.keys?.all { it in fields } != false

private fun JsonObject?.hasStringFields(vararg fields: String): Boolean =
    fields.all { field -> this?.get(field)?.let { it is JsonPrimitive && it.isString } != false }
