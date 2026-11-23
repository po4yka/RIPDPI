package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiNativeProxyJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal object PacketBackendWire {
    private const val Requested = "rootMode"
    private const val SocketPath = "rootHelperSocketPath"
    const val preserveUnknownProxyFields = true

    fun md5SignatureEnabled(settings: AppSettings): Boolean = settings.fakeMd5Sig && settings.rootModeEnabled

    fun proxyFields(
        requested: Boolean,
        socketPath: String?,
        includeNullSocketPath: Boolean = false,
    ): Map<String, JsonElement> =
        buildMap {
            put(Requested, JsonPrimitive(requested))
            if (socketPath != null || includeNullSocketPath) {
                put(SocketPath, socketPath?.let(::JsonPrimitive) ?: JsonNull)
            }
        }

    fun readProxyFields(document: JsonObject): PacketBackendOptions =
        PacketBackendOptions(
            requested = document[Requested]?.let { RipDpiNativeProxyJson.decodeFromJsonElement<Boolean>(it) } ?: false,
            socketPath = document[SocketPath]?.let { RipDpiNativeProxyJson.decodeFromJsonElement<String?>(it) },
        )

    fun tunnelFields(socketPath: String?): Map<String, JsonElement> =
        mapOf(SocketPath to (socketPath?.let(::JsonPrimitive) ?: JsonNull))
}
