package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.serialization.RipDpiJson
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
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface NativeDoqQuicClientBindings {
    fun exchange(requestJson: String): String?
}

class NativeDoqQuicClientNativeBindings
    @Inject
    constructor() : NativeDoqQuicClientBindings {
        override fun exchange(requestJson: String): String? {
            System.loadLibrary("ripdpi")
            return jniExchange(requestJson)
        }

        private external fun jniExchange(requestJson: String): String?
    }

class NativeDoqQuicClient
    @Inject
    constructor(
        private val bindings: NativeDoqQuicClientBindings,
    ) : DoqQuicClient {
        constructor() : this(NativeDoqQuicClientNativeBindings())

        private val json = RipDpiJson

        override suspend fun exchange(
            endpoint: String,
            port: Int,
            tlsServerName: String,
            query: ByteArray,
            timeoutMs: Long,
        ): ByteArray =
            withContext(Dispatchers.IO) {
                val wireQuery =
                    query.unframeDoqMessage()
                        ?: throw DoqExchangeException(DoqFailureKind.INVALID_REQUEST, "invalid DoQ query frame")
                val requestHandle = reserveRequest()
                AutoCloseable { controlRequest("release", requestHandle) }.use {
                    val request =
                        NativeDoqExchangeRequest(
                            endpoint = endpoint,
                            port = port,
                            tlsServerName = tlsServerName,
                            queryBase64 = Base64.getEncoder().encodeToString(wireQuery),
                            timeoutMs = timeoutMs,
                            requestHandle = requestHandle,
                        )
                    exchangeCancellable(request)
                }
            }

        private suspend fun exchangeCancellable(request: NativeDoqExchangeRequest): ByteArray =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cause ->
                    try {
                        controlRequest("cancel", request.requestHandle)
                    } catch (error: Exception) {
                        cause?.addSuppressed(error)
                    }
                }
                if (continuation.isActive) {
                    try {
                        continuation.resume(exchangeNative(json.encodeToString(request)).decodeBody())
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            }

        private fun reserveRequest(): Long {
            val response = exchangeNative(json.encodeToString(NativeDoqControlRequest("reserve")))
            if (response.responseBase64 != null || response.controlOk != null) {
                throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned an invalid reservation")
            }
            return response.requestHandle?.takeIf { it > 0 }
                ?: throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge missing request handle")
        }

        private fun controlRequest(
            operation: String,
            requestHandle: Long,
        ) {
            val response = exchangeNative(json.encodeToString(NativeDoqControlRequest(operation, requestHandle)))
            if (response.controlOk != true || response.responseBase64 != null || response.requestHandle != null) {
                throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge did not confirm $operation")
            }
        }

        private fun exchangeNative(requestJson: String): NativeDoqExchangeResponse {
            val payload =
                try {
                    bindings.exchange(requestJson)
                } catch (error: UnsatisfiedLinkError) {
                    throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge is unavailable", error)
                } ?: throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned no response")
            val response =
                try {
                    json.decodeFromString(NativeDoqExchangeResponse.serializer(), payload)
                } catch (error: SerializationException) {
                    throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned malformed JSON", error)
                }
            response.checkForFailure()
            return response
        }
    }

enum class DoqFailureKind {
    INVALID_REQUEST,
    SETUP,
    BOOTSTRAP,
    TLS,
    ALPN,
    QUIC,
    WIRE,
    TIMEOUT,
    JNI,
}

class DoqExchangeException(
    val kind: DoqFailureKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

@Serializable
private data class NativeDoqExchangeRequest(
    val endpoint: String,
    val port: Int,
    @SerialName("tlsServerName")
    val tlsServerName: String,
    @SerialName("queryBase64")
    val queryBase64: String,
    @SerialName("timeoutMs")
    val timeoutMs: Long,
    val requestHandle: Long,
)

@Serializable
private data class NativeDoqControlRequest(
    val operation: String,
    val requestHandle: Long? = null,
)

@Serializable
private data class NativeDoqExchangeResponse(
    @SerialName("responseBase64")
    val responseBase64: String? = null,
    @SerialName("errorKind")
    val errorKind: String? = null,
    val error: String? = null,
    val requestHandle: Long? = null,
    val controlOk: Boolean? = null,
)

private fun NativeDoqExchangeResponse.checkForFailure() {
    if (errorKind == null && error == null) return
    if (responseBase64 != null || requestHandle != null || controlOk != null || error.isNullOrBlank()) {
        throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned an invalid failure")
    }
    if (errorKind == "cancelled") throw CancellationException(error)
    val kind =
        when (errorKind) {
            "invalid_request" -> DoqFailureKind.INVALID_REQUEST
            "setup" -> DoqFailureKind.SETUP
            "bootstrap" -> DoqFailureKind.BOOTSTRAP
            "tls" -> DoqFailureKind.TLS
            "alpn" -> DoqFailureKind.ALPN
            "quic" -> DoqFailureKind.QUIC
            "wire" -> DoqFailureKind.WIRE
            "timeout" -> DoqFailureKind.TIMEOUT
            else -> throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned an unknown failure")
        }
    throw DoqExchangeException(kind, error)
}

private fun NativeDoqExchangeResponse.decodeBody(): ByteArray {
    if (requestHandle != null || controlOk != null) {
        throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge expected a DNS response")
    }
    val encoded =
        responseBase64 ?: throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge missing response body")
    if (encoded.length > MaxDoqBase64Length) {
        throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned an oversized response")
    }
    val body =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw DoqExchangeException(
                DoqFailureKind.JNI,
                "native DoQ bridge returned invalid response encoding",
                error,
            )
        }
    return body.frameDoqMessage()
        ?: throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge returned an invalid DNS message length")
}
