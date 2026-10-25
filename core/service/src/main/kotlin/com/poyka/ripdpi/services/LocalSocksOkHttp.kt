package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocks5Client
import com.poyka.ripdpi.core.LocalSocksCredentialRegistry
import com.poyka.ripdpi.core.LocalSocksCredentials
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Routes OkHttp through the app-owned loopback SOCKS5 listener at [endpoint], presenting the session
 * credentials its owner registered. `java.net` SOCKS can take credentials only from the process-wide
 * `Authenticator`, so the SOCKS handshake runs in the socket factory instead. Host names stay unresolved
 * and go to the listener as domain names, as with `Proxy.Type.SOCKS`.
 */
internal fun OkHttpClient.Builder.localSocksProxy(endpoint: RelayProbeEndpoint): OkHttpClient.Builder =
    proxy(Proxy.NO_PROXY)
        .socketFactory(
            LocalSocksSocketFactory(
                endpoint.host,
                endpoint.port,
                LocalSocksCredentialRegistry.lookup(endpoint.host, endpoint.port),
            ),
        ).dns { hostname -> listOf(InetAddress.getByAddress(hostname, UnresolvedAddress)) }

private class LocalSocksSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val credentials: LocalSocksCredentials?,
) : SocketFactory() {
    override fun createSocket(): Socket = LocalSocksSocket(proxyHost, proxyPort, credentials)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = createSocket(host, port)

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = createSocket(address, port)
}

/** Connects to the SOCKS listener, then tunnels to the target that OkHttp passes to [connect]. */
private class LocalSocksSocket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val credentials: LocalSocksCredentials?,
) : Socket() {
    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        val target = requireNotNull(endpoint as? InetSocketAddress) { "SOCKS target must be an InetSocketAddress" }
        super.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
        // The SOCKS handshake is part of connecting: bound it by the connect timeout, as java.net SOCKS does.
        val readTimeout = soTimeout
        if (timeout > 0) soTimeout = timeout
        LocalSocks5Client.connect(this, target, credentials)
        soTimeout = readTimeout
    }
}

/** Placeholder that keeps the host name; [LocalSocks5Client] sends any-local targets as domain names. */
private val UnresolvedAddress = ByteArray(4)
