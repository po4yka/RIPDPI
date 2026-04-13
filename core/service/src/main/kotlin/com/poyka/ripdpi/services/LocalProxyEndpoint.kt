package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import java.net.URI

internal const val VpnLocalProxyUsername: String = "ripdpi"

internal data class LocalProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

internal fun resolveLocalProxyEndpoint(
    telemetry: NativeRuntimeSnapshot,
    authToken: String?,
): LocalProxyEndpoint {
    val listenerAddress =
        requireNotNull(telemetry.listenerAddress) {
            "Proxy became ready without reporting a listener address"
        }

    val uri = URI("socks://$listenerAddress")
    val host = requireNotNull(uri.host) { "Proxy listener address is missing a host: $listenerAddress" }
    require(uri.port in 1..65535) { "Proxy listener address is missing a valid port: $listenerAddress" }
    val port = uri.port
    val password = authToken?.takeIf { it.isNotBlank() }
    return LocalProxyEndpoint(
        host = host,
        port = port,
        username = if (password != null) VpnLocalProxyUsername else null,
        password = password,
    )
}
