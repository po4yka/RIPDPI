package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val RelayReadyPollIntervalMs = 100L
private const val RelayReadyTimeoutMs = 5_000L

internal fun interface SocksReadinessConnector {
    fun canConnect(
        host: String,
        port: Int,
    ): Boolean
}

internal class SocketSocksReadinessConnector : SocksReadinessConnector {
    override fun canConnect(
        host: String,
        port: Int,
    ): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 250)
                socket.soTimeout = 250
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                val response = socket.getInputStream().readFully(2)
                if (!response.contentEquals(byteArrayOf(0x05, 0x00))) {
                    error("unexpected SOCKS5 readiness response")
                }
            }
            true
        }.getOrDefault(false)
}

internal class SubprocessRelayReadinessProbe(
    private val connector: SocksReadinessConnector = SocketSocksReadinessConnector(),
    private val pollIntervalMs: Long = RelayReadyPollIntervalMs,
    private val timeoutMs: Long = RelayReadyTimeoutMs,
) {
    suspend fun waitUntilReady(
        config: ResolvedRipDpiRelayConfig,
        isRunning: () -> Boolean,
        onFailure: (String) -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) {
                val message = "Subprocess relay exited before readiness"
                onFailure(message)
                error(message)
            }
            if (connector.canConnect(config.localSocksHost, config.localSocksPort)) {
                return
            }
            delay(pollIntervalMs)
        }
        val message = "Subprocess relay readiness timed out"
        onFailure(message)
        error(message)
    }

    suspend fun waitForManagedClientListener(
        bridgeSpec: ManagedClientSocksBridgeSpec,
        currentListener: () -> InetSocketAddress?,
        isRunning: () -> Boolean,
        onFailure: (String) -> Unit,
    ): InetSocketAddress {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentListener()?.let { return it }
            if (!isRunning()) {
                val message = "Managed PT exited before advertising a SOCKS listener"
                onFailure(message)
                error(message)
            }
            delay(pollIntervalMs)
        }
        val message = "Managed PT listener readiness timed out for ${bridgeSpec.methodName}"
        onFailure(message)
        error(message)
    }
}

private fun java.io.InputStream.readFully(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buffer, offset, length - offset)
        if (read < 0) {
            throw IOException("Unexpected end of readiness probe stream")
        }
        offset += read
    }
    return buffer
}
