package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import okhttp3.Response
import java.nio.charset.CharacterCodingException

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
    // Inspect the decoded stream as Content-Length may be absent or describe the encoded body.
    if (body.contentLength() > maxBytes || source.request(maxBytes.toLong() + 1)) {
        throw MalformedDnsHttpResponse("DNS response body exceeds $maxBytes bytes")
    }
    return source.readByteArray()
}
