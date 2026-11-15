@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class XrayApiScanner(
    private val dispatchers: AppCoroutineDispatchers,
    private val loopbackHosts: List<String> = DetectionLoopbackHosts,
    private val scanRange: IntRange = 1024..65535,
    private val scanPorts: List<Int>? = null,
    private val connectTimeoutMs: Int = 200,
    private val grpcDeadlineMs: Long = 2000,
    private val maxConcurrency: Int = 100,
    private val progressUpdateEvery: Int = 512,
    private val scanDeadlineMs: Long = 30_000,
) {
    private val clients = loopbackHosts.associateWith { XrayApiClient(host = it, dispatchers = dispatchers) }

    suspend fun findXrayApi(onProgress: suspend (XrayScanProgress) -> Unit): XrayApiScanResult? =
        withTimeoutOrNull(scanDeadlineMs) {
            val portsTotal = scanPorts?.size ?: (scanRange.last - scanRange.first + 1).coerceAtLeast(0)
            val total = portsTotal * loopbackHosts.size

            var scannedOffset = 0
            for (host in loopbackHosts) {
                val result =
                    scanHost(
                        host = host,
                        scannedOffset = scannedOffset,
                        total = total,
                        onProgress = onProgress,
                    )
                if (result != null) return@withTimeoutOrNull result
                scannedOffset += portsTotal
            }

            null
        }

    @Suppress("NestedBlockDepth")
    private suspend fun scanHost(
        host: String,
        scannedOffset: Int,
        total: Int,
        onProgress: suspend (XrayScanProgress) -> Unit,
    ): XrayApiScanResult? =
        coroutineScope {
            val portsTotal = scanPorts?.size ?: (scanRange.last - scanRange.first + 1).coerceAtLeast(0)
            if (portsTotal <= 0) return@coroutineScope null

            val progress = HostProgress(host, scannedOffset, total, progressUpdateEvery, onProgress)
            val result = CompletableDeferred<XrayApiScanResult?>()
            val workerCount = maxConcurrency.coerceIn(1, portsTotal)
            val running = AtomicInteger(workerCount)
            val dispatcher = dispatchers.io.limitedParallelism(workerCount)

            onProgress(
                XrayScanProgress(
                    host = host,
                    scanned = scannedOffset,
                    total = total,
                    currentPort = scanPorts?.first() ?: scanRange.first,
                ),
            )

            val jobs =
                List(workerCount) { workerIndex ->
                    launch(dispatcher) {
                        try {
                            var portIndex = workerIndex
                            while (portIndex < portsTotal && !result.isCompleted) {
                                ensureActive()
                                val port = scanPorts?.get(portIndex) ?: scanRange.first + portIndex
                                val match = if (isTcpPortOpen(host, port)) tryListOutbounds(host, port) else null
                                progress.completedAttempt(port)
                                if (match != null) {
                                    result.complete(match)
                                    return@launch
                                }
                                portIndex += workerCount
                            }
                        } finally {
                            if (running.decrementAndGet() == 0) result.complete(null)
                        }
                    }
                }

            val match =
                try {
                    result.await()
                } finally {
                    jobs.forEach { it.cancel() }
                }
            jobs.joinAll()

            progress.flush()
            match
        }

    private class HostProgress(
        private val host: String,
        private val offset: Int,
        private val total: Int,
        private val updateEvery: Int,
        private val onProgress: suspend (XrayScanProgress) -> Unit,
    ) {
        private val lock = Mutex()
        private var completed = 0
        private var reported = 0
        private var lastPort = 0

        suspend fun completedAttempt(port: Int) =
            lock.withLock {
                completed++
                lastPort = port
                if (completed % updateEvery == 0) flush()
            }

        suspend fun flush() {
            if (completed == reported) return
            onProgress(XrayScanProgress(host, offset + completed, total, lastPort))
            reported = completed
        }
    }

    private suspend fun tryListOutbounds(
        host: String,
        port: Int,
    ): XrayApiScanResult? {
        val client = clients.getValue(host)
        val attempt = client.listOutbounds(port, deadlineMs = grpcDeadlineMs)
        val error = attempt.exceptionOrNull() ?: return attempt.getOrThrow()
        if (Status.fromThrowable(error).code != Status.Code.UNAVAILABLE) return null
        return client.listOutbounds(port, deadlineMs = grpcDeadlineMs).getOrNull()
    }

    private suspend fun isTcpPortOpen(
        host: String,
        port: Int,
    ): Boolean =
        Socket(Proxy.NO_PROXY).use { socket ->
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                if (continuation.isActive) {
                    val opened =
                        try {
                            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                            true
                        } catch (_: IOException) {
                            false
                        } catch (_: IllegalArgumentException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                    continuation.resume(opened)
                }
            }
        }
}
