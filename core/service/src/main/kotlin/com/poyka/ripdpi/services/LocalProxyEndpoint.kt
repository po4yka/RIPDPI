package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import java.net.URI

internal const val VpnLocalProxyUsername: String = "ripdpi"
private const val MaxTcpPort = 65_535
private const val UnixListenerAddress = "unix"

internal data class LocalProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val unixPath: String? = null,
) {
    init {
        if (unixPath == null) {
            require(host.isNotBlank() && port in 1..MaxTcpPort) { "Proxy endpoint needs a host and port" }
        } else {
            require(host.isBlank() && port == 0) { "Proxy endpoint names a Unix path or a host and port, not both" }
            require(isProxyUnixPath(unixPath)) { "Proxy endpoint Unix path is invalid" }
            require(!password.isNullOrBlank()) { "Proxy endpoint Unix path needs credentials" }
        }
    }

    override fun toString(): String =
        if (unixPath == null) {
            "LocalProxyEndpoint(host=$host, port=$port, username=[REDACTED], password=[REDACTED])"
        } else {
            "LocalProxyEndpoint(unix, username=[REDACTED], password=[REDACTED])"
        }
}

internal fun resolveLocalProxyEndpoint(
    telemetry: NativeRuntimeSnapshot,
    authToken: String?,
    unixPath: String? = null,
): LocalProxyEndpoint {
    val listenerAddress =
        requireNotNull(telemetry.listenerAddress) {
            "Proxy became ready without reporting a listener address"
        }
    if (unixPath != null) {
        require(listenerAddress == UnixListenerAddress) { "Proxy did not report the Unix listener" }
        val unixPassword = requireNotNull(authToken?.takeIf { it.isNotBlank() }) { "Unix listener needs a token" }
        return LocalProxyEndpoint(
            host = "",
            port = 0,
            username = VpnLocalProxyUsername,
            password = unixPassword,
            unixPath = unixPath,
        )
    }

    val uri = URI("socks://$listenerAddress")
    val host =
        requireNotNull(uri.host) { "Proxy listener address is missing a host: $listenerAddress" }
            .removeSurrounding("[", "]")
    require(uri.port in 1..MaxTcpPort) { "Proxy listener address is missing a valid port: $listenerAddress" }
    val port = uri.port
    val password = authToken?.takeIf { it.isNotBlank() }
    return LocalProxyEndpoint(
        host = host,
        port = port,
        username = if (password != null) VpnLocalProxyUsername else null,
        password = password,
    )
}
