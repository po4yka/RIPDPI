package com.poyka.ripdpi.core.detection.probe

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object ProxyProber {
    internal enum class PortProbeResult {
        CLOSED,
        UNKNOWN_TCP_SERVICE,
        SOCKS5_NO_AUTH,
        HTTP_CONNECT_PROXY,
    }

    fun probeNoAuthProxyType(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ProxyType? =
        when (probePort(host, port, connectTimeoutMs, readTimeoutMs)) {
            PortProbeResult.SOCKS5_NO_AUTH -> ProxyType.SOCKS5

            PortProbeResult.HTTP_CONNECT_PROXY -> ProxyType.HTTP

            PortProbeResult.CLOSED,
            PortProbeResult.UNKNOWN_TCP_SERVICE,
            -> null
        }

    internal fun probePort(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult {
        val socksResult = probeSocks5NoAuth(host, port, connectTimeoutMs, readTimeoutMs)
        return when (socksResult) {
            PortProbeResult.SOCKS5_NO_AUTH,
            PortProbeResult.CLOSED,
            -> socksResult

            PortProbeResult.UNKNOWN_TCP_SERVICE -> probeHttpConnect(host, port, connectTimeoutMs, readTimeoutMs)

            PortProbeResult.HTTP_CONNECT_PROXY -> PortProbeResult.HTTP_CONNECT_PROXY
        }
    }

    private fun probeSocks5NoAuth(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult =
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (_: Exception) {
                return PortProbeResult.CLOSED
            }

            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true

            try {
                socket.getOutputStream().writeSocks5NoAuthGreeting()
                val response =
                    socket.getInputStream().readExactly(2)
                        ?: return PortProbeResult.UNKNOWN_TCP_SERVICE
                val version = response[0].toInt() and 0xFF
                val method = response[1].toInt() and 0xFF
                if (version == 0x05 && method == 0x00) {
                    PortProbeResult.SOCKS5_NO_AUTH
                } else {
                    PortProbeResult.UNKNOWN_TCP_SERVICE
                }
            } catch (_: SocketTimeoutException) {
                PortProbeResult.UNKNOWN_TCP_SERVICE
            } catch (_: Exception) {
                PortProbeResult.UNKNOWN_TCP_SERVICE
            }
        }

    private fun probeHttpConnect(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult =
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (_: Exception) {
                return PortProbeResult.CLOSED
            }

            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true

            try {
                socket.getOutputStream().writeHttpConnectProbe()
                val response =
                    socket.getInputStream().readAsciiPrefix()
                        ?: return PortProbeResult.UNKNOWN_TCP_SERVICE
                val code =
                    Regex("^HTTP/1\\.[01]\\s+(\\d{3})")
                        .find(response)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: return PortProbeResult.UNKNOWN_TCP_SERVICE

                if (code == 200) {
                    PortProbeResult.HTTP_CONNECT_PROXY
                } else {
                    PortProbeResult.UNKNOWN_TCP_SERVICE
                }
            } catch (_: SocketTimeoutException) {
                PortProbeResult.UNKNOWN_TCP_SERVICE
            } catch (_: Exception) {
                PortProbeResult.UNKNOWN_TCP_SERVICE
            }
        }

    private fun OutputStream.writeSocks5NoAuthGreeting() {
        write(byteArrayOf(0x05, 0x01, 0x00))
        flush()
    }

    private fun OutputStream.writeHttpConnectProbe() {
        write(
            (
                "CONNECT ifconfig.me:443 HTTP/1.1\r\n" +
                    "Host: ifconfig.me:443\r\n" +
                    "Proxy-Connection: Keep-Alive\r\n" +
                    "\r\n"
            ).encodeToByteArray(),
        )
        flush()
    }

    private fun InputStream.readExactly(byteCount: Int): ByteArray? {
        val buffer = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = read(buffer, offset, byteCount - offset)
            if (read <= 0) return null
            offset += read
        }
        return buffer
    }

    private fun InputStream.readAsciiPrefix(limit: Int = 256): String? {
        val buffer = ByteArray(limit)
        val read = read(buffer)
        if (read <= 0) return null
        return buffer.decodeToString(0, read)
    }
}
