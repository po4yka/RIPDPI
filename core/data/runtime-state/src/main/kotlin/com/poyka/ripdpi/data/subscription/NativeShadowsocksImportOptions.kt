package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The native Shadowsocks profile has no stream wrapper, dialer, or multiplex options. */
internal fun unsupportedNativeShadowsocksOptions(outbound: JsonObject): String? {
    val rawStream = outbound["streamSettings"]
    val stream = rawStream as? JsonObject
    val network = stream?.get("method") ?: stream?.get("network")
    val security = stream?.get("security")
    return when {
        outbound.keys.any { it !in setOf("tag", "protocol", "settings", "streamSettings") } -> {
            "Shadowsocks outbound options"
        }

        rawStream != null && stream == null -> {
            "Shadowsocks stream options"
        }

        stream?.keys?.any { it !in setOf("network", "method", "security") } == true -> {
            "Shadowsocks stream options"
        }

        network != null &&
            (network !is JsonPrimitive || !network.isString || network.content.lowercase() !in setOf("raw", "tcp")) -> {
            (network as? JsonPrimitive)
                ?.content
                ?.lowercase()
                ?.takeIf { it in setOf("grpc", "websocket", "ws", "xhttp", "quic", "kcp") }
                ?: "Shadowsocks transport"
        }

        security != null &&
            (security !is JsonPrimitive || !security.isString || security.content.lowercase() != "none") -> {
            "Shadowsocks security"
        }

        else -> {
            null
        }
    }
}
