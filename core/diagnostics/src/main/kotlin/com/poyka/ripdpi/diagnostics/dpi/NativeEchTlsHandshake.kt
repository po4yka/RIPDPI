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
    private val json = RipDpiEncodeDefaultsJson

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
            val payload =
                try {
                    exchange(requestJson)
                } catch (error: LinkageError) {
                    throw EchTlsException(EchTlsOutcome.UNAVAILABLE, cause = error)
                }
            decodeResponse(payload)
        }

    private suspend fun exchange(requestJson: String): String {
        val handle = createRequestHandle()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { bindings.cancelRequest(handle) }
                if (continuation.isActive) {
                    runCatching {
                        val result =
                            bindings.connect(handle, requestJson)
                                ?: throw EchTlsException(EchTlsOutcome.UNAVAILABLE)
                        continuation.resume(result)
                    }.exceptionOrNull()?.let { error ->
                        if (error !is Exception) throw error
                        continuation.resumeWithException(error)
                    }
                }
            }
        } finally {
            bindings.releaseRequest(handle)
        }
    }

    private fun createRequestHandle(): Long =
        bindings.createRequest().takeUnless { it == 0L } ?: throw EchTlsException(EchTlsOutcome.UNAVAILABLE)

    private fun decodeResponse(payload: String): EchTlsHandshakeResult {
        val response =
            try {
                json.decodeFromString(NativeEchTlsHandshakeResponse.serializer(), payload)
            } catch (error: SerializationException) {
                throw EchTlsException(EchTlsOutcome.UNAVAILABLE, cause = error)
            }
        if (response.outcome == EchTlsOutcome.CANCELLED) {
            throw CancellationException("native ECH TLS handshake cancelled")
        }
        return response.toHandshakeResult()
    }

    private fun NativeEchTlsHandshakeResponse.toHandshakeResult(): EchTlsHandshakeResult {
        val measured = outcome == EchTlsOutcome.ACCEPTED || outcome == EchTlsOutcome.REJECTED
        val incompleteMeasurement = measured && (stage != EchTlsStage.TLS || peerAddress == null || latencyMs == null)
        if (latencyMs?.let { it < 0 } == true || peerAddress?.isBlank() == true || incompleteMeasurement) {
            throw EchTlsException(EchTlsOutcome.UNAVAILABLE)
        }
        if (!measured) {
            throw EchTlsException(
                outcome = outcome,
                stage = stage,
                peerAddress = peerAddress,
                latencyMs = latencyMs,
                retryAttempted = retryAttempted,
            )
        }
        return EchTlsHandshakeResult(
            negotiatedEch = outcome == EchTlsOutcome.ACCEPTED,
            latencyMs = latencyMs,
            peerAddress = peerAddress,
            retryAttempted = retryAttempted,
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
    val outcome: EchTlsOutcome,
    val stage: EchTlsStage,
    val peerAddress: String?,
    val latencyMs: Long?,
    val retryAttempted: Boolean,
)

@Serializable
internal enum class EchTlsOutcome {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("rejected")
    REJECTED,

    @SerialName("certificateError")
    CERTIFICATE_ERROR,

    @SerialName("tlsError")
    TLS_ERROR,

    @SerialName("transportError")
    TRANSPORT_ERROR,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("unusableConfig")
    UNUSABLE_CONFIG,

    @SerialName("endpointDenied")
    ENDPOINT_DENIED,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("cancelled")
    CANCELLED,
}

internal class EchTlsException(
    val outcome: EchTlsOutcome,
    val stage: EchTlsStage = EchTlsStage.SETUP,
    val peerAddress: String? = null,
    val latencyMs: Long? = null,
    val retryAttempted: Boolean? = null,
    cause: Throwable? = null,
) : IOException(outcome.name, cause)

private const val DefaultNativeEchConnectTimeoutMs = 5_000L
