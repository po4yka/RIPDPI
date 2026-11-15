@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
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
        REJECTED,
        TIMED_OUT,
        UNAVAILABLE,
        PARTIAL,
    }

    internal fun probePort(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult {
        require(host in DetectionLoopbackHosts) { "Local probes require a numeric loopback address" }
        require(port in 1..65535 && connectTimeoutMs > 0 && readTimeoutMs > 0)
        val socks = probeSocks5NoAuth(host, port, connectTimeoutMs, readTimeoutMs)
        if (socks == PortProbeResult.SOCKS5_NO_AUTH || socks == PortProbeResult.CLOSED) return socks
        val http = probeHttpConnect(host, port, connectTimeoutMs, readTimeoutMs)
        return when {
            http == PortProbeResult.HTTP_CONNECT_PROXY -> http
            socks == PortProbeResult.REJECTED || http == PortProbeResult.REJECTED -> PortProbeResult.REJECTED
            socks == PortProbeResult.UNKNOWN_TCP_SERVICE -> http
            http == PortProbeResult.UNKNOWN_TCP_SERVICE || http == PortProbeResult.CLOSED || http == socks -> socks
            else -> PortProbeResult.PARTIAL
        }
    }

    private fun probeSocks5NoAuth(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult =
        probeSocket(host, port, connectTimeoutMs, readTimeoutMs) {
            getOutputStream().writeSocks5NoAuthGreeting()
            val response = getInputStream().readExactly(2)
            when {
                response == null || response[0] != 0x05.toByte() -> PortProbeResult.UNKNOWN_TCP_SERVICE
                response[1] == 0x00.toByte() -> PortProbeResult.SOCKS5_NO_AUTH
                response[1] == 0xFF.toByte() -> PortProbeResult.REJECTED
                else -> PortProbeResult.UNKNOWN_TCP_SERVICE
            }
        }

    private fun probeHttpConnect(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult =
        probeSocket(host, port, connectTimeoutMs, readTimeoutMs) {
            getOutputStream().writeHttpConnectProbe()
            val code = HttpResponseHeadReader(this).readFinalStatus()
            when {
                code == null -> PortProbeResult.UNKNOWN_TCP_SERVICE
                code in 200..299 -> PortProbeResult.HTTP_CONNECT_PROXY
                else -> PortProbeResult.REJECTED
            }
        }

    private inline fun probeSocket(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        probe: Socket.() -> PortProbeResult,
    ): PortProbeResult =
        try {
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                socket.probe()
            }
        } catch (_: ConnectException) {
            PortProbeResult.CLOSED
        } catch (_: SocketTimeoutException) {
            PortProbeResult.TIMED_OUT
        } catch (_: IOException) {
            PortProbeResult.UNAVAILABLE
        } catch (_: SecurityException) {
            PortProbeResult.UNAVAILABLE
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
