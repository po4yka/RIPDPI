package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.core.NativeEchTlsHandshakeBridge
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface NativeEchTlsHandshakeBindings {
    fun createRequest(): Long

    fun connect(
        requestHandle: Long,
        requestJson: String,
    ): String?

    fun cancelRequest(requestHandle: Long)

    fun releaseRequest(requestHandle: Long)
}

class NativeEchTlsHandshakeNativeBindings : NativeEchTlsHandshakeBindings {
    override fun createRequest(): Long = NativeEchTlsHandshakeBridge.createRequest()

    override fun connect(
        requestHandle: Long,
        requestJson: String,
    ): String? = NativeEchTlsHandshakeBridge.connect(requestHandle, requestJson)

    override fun cancelRequest(requestHandle: Long) = NativeEchTlsHandshakeBridge.cancelRequest(requestHandle)

    override fun releaseRequest(requestHandle: Long) = NativeEchTlsHandshakeBridge.releaseRequest(requestHandle)
}

class NativeEchTlsHandshake(
    private val bindings: NativeEchTlsHandshakeBindings = NativeEchTlsHandshakeNativeBindings(),
) : EchTlsHandshake {
    private val json =
        RipDpiEncodeDefaultsJson

    override suspend fun connect(
        endpoint: EchTlsEndpoint,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult =
        withContext(Dispatchers.IO) {
            val requestJson =
                json.encodeToString(
                    NativeEchTlsHandshakeRequest(
                        origin = endpoint.origin,
                        connectHost = endpoint.host,
                        connectPort = endpoint.port,
                        echConfigBytesB64 = Base64.getEncoder().encodeToString(echConfig),
                    ),
                )
            val requestHandle = bindings.createRequest()
            if (requestHandle == 0L) {
                throw EchTlsSetupException(
                    EchTlsSetupFailure.UNAVAILABLE,
                    "native ECH TLS request could not be created",
                )
            }
            val payload =
                try {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { bindings.cancelRequest(requestHandle) }
                        if (continuation.isActive) {
                            runCatching {
                                val result =
                                    bindings.connect(requestHandle, requestJson)
                                        ?: throw EchTlsSetupException(
                                            EchTlsSetupFailure.UNAVAILABLE,
                                            "native ECH TLS bridge returned no response",
                                        )
                                continuation.resume(result)
                            }.exceptionOrNull()?.let { error ->
                                if (error !is Exception) throw error
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                } finally {
                    bindings.releaseRequest(requestHandle)
                }
            val response =
                try {
                    json.decodeFromString(NativeEchTlsHandshakeResponse.serializer(), payload)
                } catch (error: SerializationException) {
                    throw EchTlsSetupException(
                        EchTlsSetupFailure.UNAVAILABLE,
                        "native ECH TLS bridge returned malformed response",
                    ).apply { initCause(error) }
                }
            if (response.cancelled) throw CancellationException("native ECH TLS handshake cancelled")
            response.transportSetup?.let { failure ->
                throw EchTlsSetupException(failure, response.error)
            }
            response.error?.takeIf(String::isNotBlank)?.let { message ->
                throw IOException(message)
            }
            if (response.negotiatedEch != true || response.latencyMs == null || response.latencyMs < 0) {
                throw EchTlsSetupException(
                    EchTlsSetupFailure.UNAVAILABLE,
                    "native ECH TLS bridge returned an incomplete result",
                )
            }
            EchTlsHandshakeResult(
                negotiatedEch = true,
                latencyMs = response.latencyMs,
            )
        }
}

@Serializable
private data class NativeEchTlsHandshakeRequest(
    val origin: String,
    val connectHost: String,
    val connectPort: Int,
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
    val cancelled: Boolean = false,
    val transportSetup: EchTlsSetupFailure? = null,
)

private const val DefaultNativeEchConnectTimeoutMs = 10_000L
