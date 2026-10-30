package com.poyka.ripdpi.services

// Only errors that blame the proxy hop. Destination-side codes (refused, name lookup, reset)
// say nothing about the proxy and stay silent.
private val ProxySideErrorCode =
    Regex("""(net::)?ERR_(PROXY_|TUNNEL_CONNECTION_FAILED|SSL_|CERT_|HTTP2_|QUIC_|H2_OR_QUIC_REQUIRED)[A-Z0-9_]*""")

internal fun isProxySideNetError(code: String): Boolean = ProxySideErrorCode.matchEntire(code) != null

// Chromium's codes for a proxy 407: the client sends the profile credentials preemptively, so a
// rejection ends the tunnel with ERR_PROXY_AUTH_UNSUPPORTED (seen against Caddy forwardproxy) or
// ERR_PROXY_AUTH_REQUESTED.
private val ProxyAuthErrorCode = Regex("""(net::)?ERR_PROXY_AUTH_[A-Z0-9_]*""")

internal fun isProxyAuthNetError(code: String): Boolean = ProxyAuthErrorCode.matchEntire(code) != null
