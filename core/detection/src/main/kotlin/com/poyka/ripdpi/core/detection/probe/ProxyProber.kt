@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
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
        require(host in DetectionLoopbackHosts) { "Local probes require a numeric loopback address" }
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
        Socket(Proxy.NO_PROXY).use { socket ->
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
        Socket(Proxy.NO_PROXY).use { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (_: Exception) {
                return PortProbeResult.CLOSED
            }

            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true

            try {
                socket.getOutputStream().writeHttpConnectProbe()
                val code = HttpResponseHeadReader(socket).readFinalStatus()
                if (code != null && code in 200..299) {
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

    private class HttpResponseHeadReader(
        private val socket: Socket,
    ) {
        private val input = socket.getInputStream().buffered()
        private val deadlineNanos = System.nanoTime() + socket.soTimeout * 1_000_000L
        private var remainingBytes = 4_096

        fun readFinalStatus(): Int? {
            repeat(9) {
                val line = readLine(256) ?: return null
                val match = HttpStatus.matchEntire(line) ?: return null
                val code = match.groupValues[1].toInt()
                if (!readFields()) return null
                if (code >= 200) return code
                if (code == 101) return null
            }
            return null
        }

        private fun readFields(): Boolean {
            while (true) {
                val line = readLine(remainingBytes) ?: return false
                if (line.isEmpty()) return true
                if (!HttpField.matches(line)) return false
            }
        }

        private fun readLine(limit: Int): String? {
            val line = StringBuilder()
            repeat(limit) {
                if (remainingBytes-- <= 0) return null
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) throw SocketTimeoutException()
                socket.soTimeout = ((remainingNanos + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) {
                    return if (line.endsWith('\r')) line.dropLast(1).toString() else null
                }
                line.append(byte.toChar())
            }
            return null
        }

        companion object {
            private val HttpStatus = Regex("""HTTP/1\.[01][ \t]+([1-5][0-9]{2})(?:[ \t][\t\x20-\x7e\x80-\xff]*)?""")
            private val HttpField = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+:[\t\x20-\x7e\x80-\xff]*""")
        }
    }
}
