package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class CloudflareWorkerTransportConfig(
    val workerUrl: String,
    val credentialRef: String = "",
    val authBearer: SecretString,
) {
    init {
        require(credentialRef.isNotBlank()) { "Cloudflare Worker credential reference must not be blank" }
        validateWorkerUrl(workerUrl)
        validateBearer(authBearer.value)
    }
}

private fun validateWorkerUrl(workerUrl: String) {
    val url = workerUrl.trim()
    require(url == workerUrl && url.isNotEmpty()) { "Cloudflare Worker URL must be trimmed and non-empty" }
    require(url.none { it.isWhitespace() || it.isISOControl() }) {
        "Cloudflare Worker URL must not contain whitespace or control characters"
    }
    val parsed =
        runCatching {
            URI(
                url,
            )
        }.getOrElse { throw IllegalArgumentException("Invalid Cloudflare Worker URL", it) }
    require(parsed.scheme == "https" || parsed.scheme == "wss") {
        "Cloudflare Worker URL scheme must be https or wss"
    }
    require(!parsed.host.isNullOrBlank()) { "Cloudflare Worker URL must contain a valid hostname" }
    require(parsed.userInfo == null) { "Cloudflare Worker URL must not contain userinfo" }
    require(parsed.fragment == null) { "Cloudflare Worker URL must not contain a fragment" }
    require(parsed.port == UnspecifiedPort || parsed.port in MinimumTcpPort..MaximumTcpPort) {
        "Cloudflare Worker URL contains an invalid port"
    }
}

private fun validateBearer(bearer: String) {
    require(bearer.length in 1..MaximumBearerLength && bearer.isRfc6750BearerToken()) {
        "Cloudflare Worker bearer must be a bounded RFC 6750 bearer token"
    }
}

private fun String.isRfc6750BearerToken(): Boolean {
    val paddingStart = indexOf('=').let { if (it == -1) length else it }
    if (paddingStart == 0) return false
    return take(paddingStart).all { it.isAsciiLetterOrDigit() || it in "-._~+/" } &&
        drop(paddingStart).all { it == '=' }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private const val UnspecifiedPort = -1
private const val MinimumTcpPort = 1
private const val MaximumTcpPort = 65535
private const val MaximumBearerLength = 4096
