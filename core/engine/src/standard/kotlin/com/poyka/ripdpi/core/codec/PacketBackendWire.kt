package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Suppress("UnusedParameter")
internal object PacketBackendWire {
    const val preserveUnknownProxyFields = false

    fun md5SignatureEnabled(settings: AppSettings): Boolean = false

    fun proxyFields(
        requested: Boolean,
        socketPath: String?,
        includeNullSocketPath: Boolean = false,
    ): Map<String, JsonElement> = emptyMap()

    fun readProxyFields(document: JsonObject): PacketBackendOptions = PacketBackendOptions()

    fun tunnelFields(socketPath: String?): Map<String, JsonElement> = emptyMap()
}
