package com.poyka.ripdpi.services

import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private const val OwnedStackBrowserUserAgent = "RIPDPI owned-stack browser"

internal fun normalizeOwnedStackBrowserUrl(rawUrl: String): String {
    val candidate = rawUrl.trim()
    require(candidate.isNotBlank()) { "Enter a URL to open in the RIPDPI browser." }
    val withScheme =
        if ("://" in candidate) {
            candidate
        } else {
            "https://$candidate"
        }
    val parsed = URI(withScheme)
    require(parsed.scheme.equals("https", ignoreCase = true)) {
        "Only HTTPS URLs are supported in the RIPDPI browser."
    }
    require(!parsed.host.isNullOrBlank()) { "Enter a valid HTTPS host." }
    return parsed.toString()
}

internal fun String.ownedStackAuthorityFromUrl(): String? = runCatching { URI(this).host }.getOrNull()

internal fun SecureHttpResponse.toOwnedStackBrowserPage(): OwnedStackBrowserPage =
    OwnedStackBrowserPage(
        requestedUrl = requestedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        bodyText = decodeOwnedStackBody(body, contentType),
        contentType = contentType,
        backend = backend,
        android17EchEligible = android17EchEligible,
        tlsProfileId = tlsProfileId,
        executionTrace = executionTrace,
    )

internal fun OwnedStackPlatformResponse.toSecureHttpResponse(
    requestedUrl: String,
    android17EchEligible: Boolean,
    executionTrace: OwnedStackExecutionTrace,
): SecureHttpResponse =
    SecureHttpResponse(
        requestedUrl = requestedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        body = body,
        contentType = contentType,
        backend = OwnedStackBrowserBackend.HTTP_ENGINE,
        android17EchEligible = android17EchEligible,
        executionTrace = executionTrace,
    )

internal fun Map<String, String>.withDefaultUserAgent(): Map<String, String> =
    if (keys.any { it.equals("User-Agent", ignoreCase = true) }) {
        this
    } else {
        this + ("User-Agent" to OwnedStackBrowserUserAgent)
    }

private fun decodeOwnedStackBody(
    body: ByteArray,
    contentType: String?,
): String {
    if (body.isEmpty()) return ""
    val normalizedContentType = contentType?.lowercase().orEmpty()
    val isTextual =
        normalizedContentType.isBlank() ||
            normalizedContentType.startsWith("text/") ||
            normalizedContentType.contains("json") ||
            normalizedContentType.contains("xml") ||
            normalizedContentType.contains("javascript")
    val charset = contentType.charsetFromContentType()
    return if (isTextual) body.toString(charset) else "Binary response (${body.size} bytes)."
}

private fun String?.charsetFromContentType(): Charset {
    val charsetName =
        this
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf(String::isNotBlank)
    return charsetName
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: StandardCharsets.UTF_8
}
