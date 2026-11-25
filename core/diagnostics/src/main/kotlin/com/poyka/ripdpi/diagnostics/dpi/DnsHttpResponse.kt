package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.nio.charset.CharacterCodingException
import kotlin.coroutines.resumeWithException

internal const val MaxDnsJsonBodyBytes = 128 * 1024

internal class MalformedDnsHttpResponse(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal fun Response.readDnsWireBody(): ByteArray =
    readDnsBody(DnsMessages.MAX_MESSAGE_BYTES, "application/dns-message")

internal fun Response.readDnsJsonBody(): String =
    try {
        readDnsBody(MaxDnsJsonBodyBytes, "application/dns-json", "application/json")
            .decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        throw MalformedDnsHttpResponse("DNS JSON response must be UTF-8", error)
    }

private fun Response.readDnsBody(
    maxBytes: Int,
    vararg acceptedMediaTypes: String,
): ByteArray {
    val mediaType = body.contentType()?.let { "${it.type}/${it.subtype}" }
    if (mediaType !in acceptedMediaTypes) throw MalformedDnsHttpResponse("Unexpected DNS response media type")
    val source = body.source()
    if (body.contentLength() > maxBytes || source.request(maxBytes.toLong() + 1)) {
        throw MalformedDnsHttpResponse("DNS response body exceeds $maxBytes bytes")
    }
    return source.readByteArray()
}

internal suspend fun <T> Call.readDnsResponse(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val result =
                        try {
                            response.use {
                                if (!continuation.isActive) return
                                Result.success(read(it))
                            }
                        } catch (error: IOException) {
                            Result.failure(error)
                        } catch (error: IllegalArgumentException) {
                            Result.failure(error)
                        } catch (error: IllegalStateException) {
                            Result.failure(error)
                        }
                    continuation.resumeWith(result)
                }
            },
        )
    }
