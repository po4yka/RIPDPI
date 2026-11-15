package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TelegramDcFailure {
    TIMEOUT,
    CONNECT,
    LOCAL_POLICY,
}

data class DcReachability(
    val label: String,
    val ip: String,
    val reachable: Boolean,
    val rttMs: Long?,
    val failure: TelegramDcFailure? = null,
)

class TelegramDcPinger(
    private val socketConnector: TelegramDcSocketConnector = RealTelegramDcSocketConnector(),
) {
    suspend fun pingAll(): List<DcReachability> =
        withContext(Dispatchers.IO) {
            TelegramDcEndpoints.map { endpoint -> ping(endpoint) }
        }

    private suspend fun ping(endpoint: TelegramDcEndpoint): DcReachability {
        val (rtt, failure) =
            try {
                socketConnector.connect(endpoint.ip, TelegramDcPort, DcPingTimeoutMs) to null
            } catch (_: SocketTimeoutException) {
                null to TelegramDcFailure.TIMEOUT
            } catch (_: IOException) {
                null to TelegramDcFailure.CONNECT
            } catch (_: SecurityException) {
                null to TelegramDcFailure.LOCAL_POLICY
            }
        return DcReachability(endpoint.label, endpoint.ip, rtt != null, rtt, failure)
    }

    private data class TelegramDcEndpoint(
        val label: String,
        val ip: String,
    )

    companion object {
        private const val TelegramDcPort = 443
        private const val DcPingTimeoutMs = 5_000
        private val TelegramDcEndpoints =
            listOf(
                TelegramDcEndpoint("DC1", "149.154.175.53"),
                TelegramDcEndpoint("DC2", "149.154.167.51"),
                TelegramDcEndpoint("DC3", "149.154.175.100"),
                TelegramDcEndpoint("DC4", "149.154.167.91"),
                TelegramDcEndpoint("DC5", "91.108.56.130"),
            )
    }
}

fun interface TelegramDcSocketConnector {
    suspend fun connect(
        ip: String,
        port: Int,
        timeoutMs: Int,
    ): Long
}

private class RealTelegramDcSocketConnector : TelegramDcSocketConnector {
    override suspend fun connect(
        ip: String,
        port: Int,
        timeoutMs: Int,
    ): Long =
        Socket().use { socket ->
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    val startedAt = System.nanoTime()
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    continuation.resume(elapsedMs)
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                } catch (error: SecurityException) {
                    continuation.resumeWithException(error)
                }
            }
        }
}
