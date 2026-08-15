package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.serialization.RipDpiJson
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
                val payload =
                    bindings.exchange(json.encodeToString(request))
                        ?: throw DoqQuicUnavailableException("native DoQ bridge returned no response")
                val response =
                    try {
                        json.decodeFromString(NativeDoqExchangeResponse.serializer(), payload)
                    } catch (error: SerializationException) {
                        throw DoqQuicUnavailableException(
                            "native DoQ bridge returned malformed response: ${error.message}",
                            error,
                        )
                    }
                response.error?.takeIf(String::isNotBlank)?.let { message ->
                    throw response.toException(message)
                }
                val body =
                    response.responseBase64?.let { encoded ->
                        try {
                            Base64.getDecoder().decode(encoded)
                        } catch (error: IllegalArgumentException) {
                            throw DoqQuicUnavailableException(
                                "native DoQ bridge returned invalid response encoding",
                                error,
                            )
                        }
                    } ?: throw DoqQuicUnavailableException("native DoQ bridge missing response body")
                body.frameDoqMessage()
            }
    }

class DoqTimeoutException(
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

private fun NativeDoqExchangeResponse.toException(message: String): IOException =
    when (errorKind) {
        "tls" -> DoqTlsRejectException(message)
        "timeout" -> DoqTimeoutException(message)
        else -> DoqQuicUnavailableException(message)
    }

private fun ByteArray.unframeDoqMessage(): ByteArray {
    if (size < DoqLengthPrefixBytes) {
        throw DoqQuicUnavailableException("DoQ query missing length prefix")
    }
    val length = ((this[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (this[1].toInt() and ByteMask)
    if (length <= 0 || size < DoqLengthPrefixBytes + length) {
        throw DoqQuicUnavailableException("DoQ query length prefix is invalid")
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
