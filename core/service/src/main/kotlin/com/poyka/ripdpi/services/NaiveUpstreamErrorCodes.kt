package com.poyka.ripdpi.services

// Only errors that blame the proxy hop. Destination-side codes (refused, name lookup, reset)
// say nothing about the proxy and stay silent.
private val ProxySideErrorCode =
    Regex("""(net::)?ERR_(PROXY_|TUNNEL_CONNECTION_FAILED|SSL_|CERT_|HTTP2_|QUIC_|H2_OR_QUIC_REQUIRED)[A-Z0-9_]*""")

internal fun proxySideNetErrorFailureClass(code: String): String? {
    if (!ProxySideErrorCode.matches(code)) return null
    val error = code.removePrefix("net::")
    return when {
        error.startsWith("ERR_PROXY_AUTH_") -> "auth"

        error.startsWith("ERR_CERT_") || error.startsWith("ERR_SSL_") -> "tls"

        error.startsWith("ERR_HTTP2_") ||
            error == "ERR_TUNNEL_CONNECTION_FAILED" ||
            error == "ERR_H2_OR_QUIC_REQUIRED" -> "http_connect"

        else -> "connect"
    }
}
