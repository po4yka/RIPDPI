package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import javax.inject.Inject

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
                val wireQuery = query.unframeDoqMessage()
                val request =
                    NativeDoqExchangeRequest(
                        endpoint = endpoint,
                        port = port,
                        tlsServerName = tlsServerName,
                        queryBase64 = Base64.getEncoder().encodeToString(wireQuery),
                        timeoutMs = timeoutMs,
                    )
                exchangeNative(json.encodeToString(request)).decodeBody()
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
)

@Serializable
private data class NativeDoqExchangeResponse(
    @SerialName("responseBase64")
    val responseBase64: String? = null,
    @SerialName("errorKind")
    val errorKind: String? = null,
    val error: String? = null,
)

private fun NativeDoqExchangeResponse.checkForFailure() {
    if (errorKind == null && error == null) return
    if (responseBase64 != null || error.isNullOrBlank()) {
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
    val encoded =
        responseBase64 ?: throw DoqExchangeException(DoqFailureKind.JNI, "native DoQ bridge missing response body")
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
}

private fun ByteArray.unframeDoqMessage(): ByteArray {
    if (size < DoqLengthPrefixBytes) {
        throw DoqExchangeException(DoqFailureKind.INVALID_REQUEST, "DoQ query missing length prefix")
    }
    val length = ((this[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (this[1].toInt() and ByteMask)
    if (length <= 0 || size < DoqLengthPrefixBytes + length) {
        throw DoqExchangeException(DoqFailureKind.INVALID_REQUEST, "DoQ query length prefix is invalid")
    }
    return copyOfRange(DoqLengthPrefixBytes, DoqLengthPrefixBytes + length)
}

private fun ByteArray.frameDoqMessage(): ByteArray =
    ByteArrayOutputStream(size + DoqLengthPrefixBytes)
        .apply {
            write((size shr Byte.SIZE_BITS) and ByteMask)
            write(size and ByteMask)
            write(this@frameDoqMessage)
        }.toByteArray()

private const val DoqLengthPrefixBytes = 2
private const val ByteMask = 0xFF
