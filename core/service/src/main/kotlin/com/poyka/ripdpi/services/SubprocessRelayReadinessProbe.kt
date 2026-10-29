package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocks5Client
import com.poyka.ripdpi.core.LocalSocksCredentials
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.localSocksCredentialsOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

private const val RelayReadyPollIntervalMs = 100L
private const val RelayReadyTimeoutMs = 5_000L
private const val SocksReadinessConnectTimeoutMs = 250

internal fun interface SocksReadinessConnector {
    fun canConnect(
        host: String,
        port: Int,
        credentials: LocalSocksCredentials,
    ): Boolean
}

internal class SocketSocksReadinessConnector : SocksReadinessConnector {
    override fun canConnect(
        host: String,
        port: Int,
        credentials: LocalSocksCredentials,
    ): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), SocksReadinessConnectTimeoutMs)
                socket.soTimeout = SocksReadinessConnectTimeoutMs
                LocalSocks5Client.authenticate(socket.getInputStream(), socket.getOutputStream(), credentials)
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
        val credentials =
            requireNotNull(localSocksCredentialsOrNull(config.localSocksUsername, config.localSocksPassword))
        var ready = false
        withTimeoutOrNull<Unit>(timeoutMs) {
            while (!ready) {
                if (!isRunning()) {
                    val message = "Subprocess relay exited before readiness"
                    onFailure(message)
                    error(message)
                }
                ready = connector.canConnect(config.localSocksHost, config.localSocksPort, credentials)
                if (!ready) {
                    delay(pollIntervalMs)
                }
            }
        }
        if (ready) return
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
        var listener: InetSocketAddress? = null
        withTimeoutOrNull<Unit>(timeoutMs) {
            while (listener == null) {
                listener = currentListener()
                if (listener == null) {
                    if (!isRunning()) {
                        val message = "Managed PT exited before advertising a SOCKS listener"
                        onFailure(message)
                        error(message)
                    }
                    delay(pollIntervalMs)
                }
            }
        }
        listener?.let { return it }
        val message = "Managed PT listener readiness timed out for ${bridgeSpec.methodName}"
        onFailure(message)
        error(message)
    }
}
