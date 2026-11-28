package com.poyka.ripdpi.services

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.poyka.ripdpi.core.LocalSocks5Client
import com.poyka.ripdpi.core.LocalSocksConnectRejectedException
import com.poyka.ripdpi.core.LocalSocksCredentialRegistry
import com.poyka.ripdpi.core.LocalSocksCredentials
import okhttp3.OkHttpClient
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketImpl
import java.net.SocketOptions
import java.net.SocketTimeoutException
import javax.net.SocketFactory

private const val SocksReplyHostUnreachable = 4
private const val MaxUnixPathBytes = 108
private const val MaxPort = 65_535

/** An app-owned loopback SOCKS5 listener and the session credentials it requires, if any. */
data class LocalSocksRoute(
    val host: String,
    val port: Int,
    val credentials: LocalSocksCredentials? = null,
    /** The listener cannot resolve names (the noDomain setting), so the client must send addresses. */
    val resolveNamesLocally: Boolean = false,
    val unixPath: String? = null,
) {
    init {
        if (unixPath == null) {
            require(host.isNotBlank() && port in 1..MaxPort) { "SOCKS route needs a host and port or a Unix path" }
        } else {
            require(host.isBlank() && port == 0) { "SOCKS route names a Unix path or a host and port, not both" }
            require(unixPath.startsWith("/") && '\u0000' !in unixPath) { "SOCKS Unix path is invalid" }
            require(unixPath.toByteArray(Charsets.UTF_8).size < MaxUnixPathBytes) { "SOCKS Unix path is too long" }
            requireNotNull(credentials) { "SOCKS Unix route needs credentials" }
        }
    }

    override fun toString(): String =
        if (unixPath == null) {
            "LocalSocksRoute(host=$host, port=$port, credentials=$credentials, " +
                "resolveNamesLocally=$resolveNamesLocally)"
        } else {
            "LocalSocksRoute(unix, credentials=$credentials, resolveNamesLocally=$resolveNamesLocally)"
        }
}

fun LocalSocksRoute.withLogin(
    username: String,
    password: String,
): LocalSocksRoute = copy(credentials = LocalSocksCredentials(username, password))

/**
 * Authenticates on the connected [socket] and opens a CONNECT tunnel to [target].
 *
 * @throws ProtocolException when the listener refuses the handshake or the CONNECT.
 */
fun LocalSocksRoute.openTunnel(
    socket: Socket,
    target: InetSocketAddress,
) {
    LocalSocks5Client.connect(socket, target, credentials)
}

fun LocalSocksRoute.connectProxySocket(timeoutMillis: Int): Socket =
    if (unixPath == null) {
        Socket().apply { connect(InetSocketAddress(host, port), timeoutMillis) }
    } else {
        UnixProxySocket(unixPath).apply { connect(unixPlaceholderAddress(), timeoutMillis) }
    }

private fun unixPlaceholderAddress() = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)

/** The listener answered a CONNECT with the RFC 1928 "host unreachable" reply. */
fun ProtocolException.isLocalSocksHostUnreachable(): Boolean =
    (this as? LocalSocksConnectRejectedException)?.reply == SocksReplyHostUnreachable

/** Routes OkHttp through the listener at [endpoint], presenting the session credentials its owner registered. */
internal fun OkHttpClient.Builder.localSocksProxy(endpoint: RelayProbeEndpoint): OkHttpClient.Builder =
    localSocksProxy(
        LocalSocksRoute(
            endpoint.host,
            endpoint.port,
            endpoint.localCredentials(),
        ),
    )

internal fun RelayProbeEndpoint.localCredentials(): LocalSocksCredentials? =
    credentials?.let { LocalSocksCredentials(it.username, it.password) }
        ?: LocalSocksCredentialRegistry.lookup(host, port)

/**
 * Routes OkHttp through [route]. `java.net` SOCKS can take credentials only from the process-wide
 * `Authenticator`, so the SOCKS handshake runs in the socket factory instead. Host names stay unresolved
 * and go to the listener as domain names, as with `Proxy.Type.SOCKS`.
 */
fun OkHttpClient.Builder.localSocksProxy(route: LocalSocksRoute): OkHttpClient.Builder =
    proxy(Proxy.NO_PROXY)
        .socketFactory(LocalSocksSocketFactory(route))
        .apply {
            if (!route.resolveNamesLocally) {
                dns { hostname -> listOf(InetAddress.getByAddress(hostname, UnresolvedAddress)) }
            }
        }

private class LocalSocksSocketFactory(
    private val route: LocalSocksRoute,
) : SocketFactory() {
    override fun createSocket(): Socket =
        route.unixPath?.let { LocalSocksUnixSocket(it, route.credentials) }
            ?: LocalSocksSocket(route.host, route.port, route.credentials)

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
        tunnel(target, timeout, credentials)
    }
}

private open class UnixProxySocket(
    path: String,
) : Socket(UnixSocketImpl(path))

private class LocalSocksUnixSocket(
    path: String,
    private val credentials: LocalSocksCredentials?,
) : UnixProxySocket(path) {
    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        val target = requireNotNull(endpoint as? InetSocketAddress) { "SOCKS target must be an InetSocketAddress" }
        super.connect(unixPlaceholderAddress(), timeout)
        tunnel(target, timeout, credentials)
    }
}

private fun Socket.tunnel(
    target: InetSocketAddress,
    timeout: Int,
    credentials: LocalSocksCredentials?,
) {
    // The SOCKS handshake is part of connecting: bound it by the connect timeout, as java.net SOCKS does.
    val readTimeout = soTimeout
    if (timeout > 0) soTimeout = timeout
    LocalSocks5Client.connect(this, target, credentials)
    soTimeout = readTimeout
}

private inline fun errnoIo(
    timeoutMessage: String,
    call: () -> Int,
): Int {
    while (true) {
        try {
            return call()
        } catch (e: ErrnoException) {
            when (e.errno) {
                OsConstants.EINTR -> continue
                OsConstants.EAGAIN -> throw SocketTimeoutException(timeoutMessage)
                else -> throw IOException(OsConstants.errnoName(e.errno), e)
            }
        }
    }
}

private class UnixSocketImpl(
    private val path: String,
) : SocketImpl() {
    private val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
    private var readTimeoutMillis = 0

    override fun create(stream: Boolean) = Unit

    override fun connect(
        host: String,
        port: Int,
    ) = connect(InetSocketAddress.createUnresolved(host, port), 0)

    override fun connect(
        address: InetAddress,
        port: Int,
    ) = connect(InetSocketAddress(address, port), 0)

    override fun connect(
        address: SocketAddress,
        timeout: Int,
    ) {
        socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.soTimeout = readTimeoutMillis
        val descriptor: FileDescriptor = socket.fileDescriptor
        fd = descriptor
        this.address = InetAddress.getLoopbackAddress()
        this.port = 0
    }

    override fun bind(
        host: InetAddress,
        port: Int,
    ) {
        localport = 0
    }

    override fun listen(backlog: Int) = throw UnsupportedOperationException("client socket")

    override fun accept(s: SocketImpl) = throw UnsupportedOperationException("client socket")

    override fun getInputStream(): InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toUByte().toInt()
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (len == 0) return 0
                val count = errnoIo("Read timed out") { Os.read(requireNotNull(fd), b, off, len) }
                return if (count == 0) -1 else count
            }

            override fun available(): Int = socket.inputStream.available()
        }

    override fun getOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                var done = 0
                while (done < len) {
                    done += errnoIo("Write timed out") { Os.write(requireNotNull(fd), b, off + done, len - done) }
                }
            }
        }

    override fun available(): Int = inputStream.available()

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        socket.close()
    }

    override fun shutdownInput() = socket.shutdownInput()

    override fun shutdownOutput() = socket.shutdownOutput()

    override fun sendUrgentData(data: Int) = throw UnsupportedOperationException("urgent data")

    override fun setOption(
        optID: Int,
        value: Any?,
    ) {
        if (optID == SocketOptions.SO_TIMEOUT) {
            readTimeoutMillis = value as Int
            if (socket.isConnected) socket.soTimeout = readTimeoutMillis
        }
    }

    override fun getOption(optID: Int): Any =
        when (optID) {
            SocketOptions.SO_TIMEOUT -> readTimeoutMillis
            SocketOptions.SO_BINDADDR -> InetAddress.getLoopbackAddress()
            SocketOptions.SO_LINGER -> -1
            SocketOptions.SO_RCVBUF, SocketOptions.SO_SNDBUF, SocketOptions.IP_TOS -> 0
            else -> false
        }
}

/** Placeholder that keeps the host name; [LocalSocks5Client] sends any-local targets as domain names. */
private val UnresolvedAddress = ByteArray(4)
