package com.poyka.ripdpi.services

// Only errors that blame the proxy hop. Destination-side codes (refused, name lookup, reset)
// say nothing about the proxy and stay silent.
private val ProxySideErrorCode =
    Regex("""(net::)?ERR_(PROXY_|TUNNEL_CONNECTION_FAILED|SSL_|CERT_|HTTP2_|QUIC_|H2_OR_QUIC_REQUIRED)[A-Z0-9_]*""")

internal fun isProxySideNetError(code: String): Boolean = ProxySideErrorCode.matchEntire(code) != null
