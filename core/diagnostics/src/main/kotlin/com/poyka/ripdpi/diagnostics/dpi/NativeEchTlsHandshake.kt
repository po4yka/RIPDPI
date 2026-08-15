package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.core.NativeEchTlsHandshakeBridge
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.util.Base64

interface NativeEchTlsHandshakeBindings {
    fun connect(requestJson: String): String?
}

class NativeEchTlsHandshakeNativeBindings : NativeEchTlsHandshakeBindings {
    override fun connect(requestJson: String): String? = NativeEchTlsHandshakeBridge.connect(requestJson)
}

class NativeEchTlsHandshake(
    private val bindings: NativeEchTlsHandshakeBindings = NativeEchTlsHandshakeNativeBindings(),
) : EchTlsHandshake {
    private val json =
        RipDpiEncodeDefaultsJson

    override suspend fun connect(
        target: String,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult =
        withContext(Dispatchers.IO) {
            val payload =
                bindings.connect(
                    json.encodeToString(
                        NativeEchTlsHandshakeRequest(
                            target = target,
                            echConfigBytesB64 = Base64.getEncoder().encodeToString(echConfig),
                        ),
                    ),
                ) ?: throw IOException("native ECH TLS bridge returned no response")
            val response =
                try {
                    json.decodeFromString(NativeEchTlsHandshakeResponse.serializer(), payload)
                } catch (error: SerializationException) {
                    throw IOException("native ECH TLS bridge returned malformed response", error)
                }
            response.error?.takeIf(String::isNotBlank)?.let { message ->
                throw IOException(message)
            }
            EchTlsHandshakeResult(
                negotiatedEch = response.negotiatedEch ?: false,
                latencyMs = response.latencyMs,
            )
        }
}

@Serializable
private data class NativeEchTlsHandshakeRequest(
    val target: String,
    @SerialName("echConfigBytesB64")
    val echConfigBytesB64: String,
    @SerialName("connectTimeoutMs")
    val connectTimeoutMs: Long = DefaultNativeEchConnectTimeoutMs,
)

@Serializable
private data class NativeEchTlsHandshakeResponse(
    @SerialName("negotiatedEch")
    val negotiatedEch: Boolean? = null,
    @SerialName("latencyMs")
    val latencyMs: Long? = null,
    val error: String? = null,
)

private const val DefaultNativeEchConnectTimeoutMs = 10_000L
