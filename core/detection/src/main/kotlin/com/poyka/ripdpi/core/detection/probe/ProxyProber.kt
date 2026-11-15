@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal object ProxyProber {
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

    suspend fun probePort(
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

    private suspend fun probeSocks5NoAuth(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult =
        probeSocket(host, port, connectTimeoutMs, readTimeoutMs) {
            getOutputStream().writeSocks5NoAuthGreeting()
            val response = getInputStream()
            if (response.read() != 0x05) return@probeSocket PortProbeResult.UNKNOWN_TCP_SERVICE
            when (response.read()) {
                0x00 -> PortProbeResult.SOCKS5_NO_AUTH
                0xFF -> PortProbeResult.REJECTED
                -1 -> PortProbeResult.PARTIAL
                else -> PortProbeResult.UNKNOWN_TCP_SERVICE
            }
        }

    private suspend fun probeHttpConnect(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): PortProbeResult =
        probeSocket(host, port, connectTimeoutMs, readTimeoutMs) {
            getOutputStream().writeHttpConnectProbe()
            HttpResponseHeadReader(this).readFinalResult()
        }

    private suspend fun probeSocket(
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        probe: Socket.() -> PortProbeResult,
    ): PortProbeResult =
        withCancellableLoopbackSocket { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                socket.probe()
            } catch (_: ConnectException) {
                PortProbeResult.CLOSED
            } catch (_: SocketTimeoutException) {
                PortProbeResult.TIMED_OUT
            } catch (_: IOException) {
                PortProbeResult.UNAVAILABLE
            } catch (_: SecurityException) {
                PortProbeResult.UNAVAILABLE
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

    private class HttpResponseHeadReader(
        private val socket: Socket,
    ) {
        private val input = socket.getInputStream().buffered()
        private val deadlineNanos = System.nanoTime() + socket.soTimeout * 1_000_000L
        private var remainingBytes = 4_096

        private var sawHttpPrefix = false

        fun readFinalResult(): PortProbeResult {
            repeat(9) {
                val status = readLine(256)?.let(HttpStatus::matchEntire)
                val result =
                    when {
                        status == null -> {
                            if (sawHttpPrefix) PortProbeResult.PARTIAL else PortProbeResult.UNKNOWN_TCP_SERVICE
                        }

                        !readFields() -> {
                            PortProbeResult.PARTIAL
                        }

                        else -> {
                            when (status.groupValues[1].toInt()) {
                                in 200..299 -> PortProbeResult.HTTP_CONNECT_PROXY
                                101, in 300..599 -> PortProbeResult.REJECTED
                                else -> null
                            }
                        }
                    }
                if (result != null) return result
            }
            return PortProbeResult.PARTIAL
        }

        private fun readFields(): Boolean {
            while (remainingBytes > 0) {
                val line = readLine(remainingBytes) ?: break
                if (line.isEmpty()) return true
                if (!HttpField.matches(line)) break
            }
            return false
        }

        private fun readLine(limit: Int): String? {
            val line = StringBuilder()
            for (index in 0 until minOf(limit, remainingBytes)) {
                remainingBytes--
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) throw SocketTimeoutException()
                socket.soTimeout = ((remainingNanos + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)
                val byte = input.read()
                if (byte < 0) break
                if (byte == '\n'.code) {
                    return if (line.endsWith('\r')) line.dropLast(1).toString() else null
                }
                line.append(byte.toChar())
                if (line.length == 5 && line.startsWith("HTTP/")) sawHttpPrefix = true
            }
            return null
        }

        companion object {
            private val HttpStatus = Regex("""HTTP/1\.[01][ \t]+([1-5][0-9]{2})(?:[ \t][\t\x20-\x7e\x80-\xff]*)?""")
            private val HttpField = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+:[\t\x20-\x7e\x80-\xff]*""")
        }
    }
}
