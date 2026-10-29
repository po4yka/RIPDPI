package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reject settings that cannot survive conversion to a native relay profile. */
internal fun unsupportedNativeVlessOptions(
    outbound: JsonObject,
    settings: JsonObject?,
    stream: JsonObject?,
    transport: String?,
): String? =
    when {
        !outbound.hasOnly("tag", "protocol", "settings", "streamSettings") -> "VLESS outbound options"
        else -> unsupportedVlessSettings(settings) ?: unsupportedStreamOptions(stream, transport)
    }

private fun unsupportedVlessSettings(settings: JsonObject?): String? {
    val peers = settings?.get("vnext") as? JsonArray
    val legacy = settings?.containsKey("vnext") == true
    val peer = settings.nativeVlessPeer()
    val users = peer?.get("users") as? JsonArray
    val user = settings.nativeVlessUser()
    return when {
        legacy && (!settings.hasOnly("vnext") || peers?.size != 1) -> {
            "VLESS settings"
        }

        !legacy && !settings.hasOnly("address", "port", "id", "flow", "encryption", "level", "email") -> {
            "VLESS settings"
        }

        legacy && (!peer.hasOnly("address", "port", "users") || users?.size != 1) -> {
            "VLESS peers"
        }

        !peer.hasStringFields("address") || !peer.hasIntegerPort() -> {
            "VLESS endpoint"
        }

        legacy && !user.hasOnly("id", "flow", "encryption", "level", "email") -> {
            "VLESS user options"
        }

        !user.hasStringFields("id", "flow", "encryption", "email") -> {
            "VLESS user options"
        }

        user?.get("encryption")?.let { it != JsonPrimitive("none") } == true -> {
            "VLESS Encryption"
        }

        user?.get("level")?.let { it != JsonPrimitive(0) } == true -> {
            "VLESS user policy"
        }

        else -> {
            null
        }
    }
}

private fun unsupportedStreamOptions(
    stream: JsonObject?,
    transport: String?,
): String? {
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

        xhttp != null && transport != "xhttp" -> {
            "XHTTP transport options"
        }

        else -> {
            null
        }
    }
}

internal fun JsonObject?.nativeVlessPeer(): JsonObject? =
    if (this?.containsKey("vnext") == true) {
        (this["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
    } else {
        this
    }

internal fun JsonObject?.nativeVlessUser(): JsonObject? =
    if (this?.containsKey("vnext") == true) {
        (nativeVlessPeer()?.get("users") as? JsonArray)?.firstOrNull() as? JsonObject
    } else {
        this
    }

private fun supportsRealityOptions(reality: JsonObject?): Boolean =
    reality.hasOnly("password", "publicKey", "serverName", "shortId", "fingerprint", "show") &&
        reality.hasStringFields("password", "publicKey", "serverName", "shortId", "fingerprint") &&
        listOf("password", "publicKey").all { field ->
            reality?.get(field)?.let { (it as? JsonPrimitive)?.content?.isNotBlank() == true } != false
        } &&
        (
            reality?.containsKey("password") != true || reality.containsKey("publicKey") != true ||
                reality["password"] == reality["publicKey"]
        ) &&
        reality?.get("show") in setOf(null, JsonPrimitive(false))

private fun JsonObject?.hasIntegerPort(): Boolean =
    (this?.get("port") as? JsonPrimitive)?.let { !it.isString && it.intOrNull != null } == true

private fun supportsTlsOptions(tls: JsonObject?): Boolean =
    tls.hasOnly("serverName", "fingerprint", "allowInsecure") &&
        tls.hasStringFields("serverName", "fingerprint") &&
        tls?.get("allowInsecure") in setOf(null, JsonPrimitive(false))

private fun JsonObject?.hasOnly(vararg fields: String): Boolean = this?.keys?.all { it in fields } != false

private fun JsonObject?.hasStringFields(vararg fields: String): Boolean =
    fields.all { field -> this?.get(field)?.let { it is JsonPrimitive && it.isString } != false }
