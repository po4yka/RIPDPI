@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    private val scanDeadlineMs: Long = LocalScanDeadlineMs,
) {
    private val clients = loopbackHosts.associateWith { XrayApiClient(host = it, dispatchers = dispatchers) }
    private val ports =
        (scanPorts?.asSequence() ?: scanRange.asSequence())
            .filter { it in 1..65535 }
            .distinct()
            .toList()

    internal suspend fun scan(onProgress: suspend (XrayScanProgress) -> Unit): LocalScanResult<XrayApiScanResult> =
        withTimeoutOrNull(scanDeadlineMs) {
            if (ports.isEmpty() ||
                clients.isEmpty()
            ) {
                return@withTimeoutOrNull LocalScanResult(null, LocalScanStatus.SKIPPED)
            }
            val total = ports.size * clients.size
            var outcome = LocalScanStatus.NO_MATCH
            for ((index, host) in clients.keys.withIndex()) {
                val result = scanHost(host, index * ports.size, total, onProgress)
                if (result.match != null) return@withTimeoutOrNull result
                outcome = outcome.merge(result.status)
            }
            LocalScanResult(null, outcome)
        } ?: LocalScanResult(null, LocalScanStatus.TIMED_OUT)

    @Suppress("NestedBlockDepth")
    private suspend fun scanHost(
        host: String,
        scannedOffset: Int,
        total: Int,
        onProgress: suspend (XrayScanProgress) -> Unit,
    ): LocalScanResult<XrayApiScanResult> =
        coroutineScope {
            val progress = HostProgress(host, scannedOffset, total, progressUpdateEvery, onProgress)
            val result = CompletableDeferred<XrayApiScanResult?>()
            val outcome = AtomicReference(LocalScanStatus.NO_MATCH)
            val workerCount = maxConcurrency.coerceIn(1, ports.size)
            val running = AtomicInteger(workerCount)
            val dispatcher = dispatchers.io.limitedParallelism(workerCount)

            onProgress(
                XrayScanProgress(
                    host = host,
                    scanned = scannedOffset,
                    total = total,
                    currentPort = ports.first(),
                ),
            )

            val jobs =
                List(workerCount) { workerIndex ->
                    launch(dispatcher) {
                        try {
                            var portIndex = workerIndex
                            while (portIndex < ports.size && !result.isCompleted) {
                                ensureActive()
                                val port = ports[portIndex]
                                val attempt = tryPort(host, port)
                                outcome.updateAndGet { it.merge(attempt.status) }
                                progress.completedAttempt(port)
                                if (attempt.match != null) {
                                    result.complete(attempt.match)
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
            LocalScanResult(match, if (match != null) LocalScanStatus.MATCHED else outcome.get())
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

    private suspend fun tryPort(
        host: String,
        port: Int,
    ): LocalScanResult<XrayApiScanResult> {
        connectFailure(host, port)?.let { return LocalScanResult(null, it) }

        val client = clients.getValue(host)
        val first = client.listOutbounds(port, deadlineMs = grpcDeadlineMs)
        val attempt =
            if (first.exceptionOrNull()?.let(Status::fromThrowable)?.code == Status.Code.UNAVAILABLE) {
                client.listOutbounds(port, deadlineMs = grpcDeadlineMs)
            } else {
                first
            }
        return attempt.fold(
            onSuccess = { LocalScanResult(it, LocalScanStatus.MATCHED) },
            onFailure = { LocalScanResult(null, rpcFailureStatus(Status.fromThrowable(it).code)) },
        )
    }

    private fun rpcFailureStatus(code: Status.Code): LocalScanStatus =
        when (code) {
            Status.Code.UNAUTHENTICATED,
            Status.Code.PERMISSION_DENIED,
            -> LocalScanStatus.REJECTED

            Status.Code.UNIMPLEMENTED -> LocalScanStatus.NO_MATCH

            Status.Code.DEADLINE_EXCEEDED -> LocalScanStatus.TIMED_OUT

            else -> LocalScanStatus.UNAVAILABLE
        }

    private suspend fun connectFailure(
        host: String,
        port: Int,
    ): LocalScanStatus? =
        withCancellableLoopbackSocket { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                null
            } catch (_: ConnectException) {
                LocalScanStatus.NO_MATCH
            } catch (_: SocketTimeoutException) {
                LocalScanStatus.TIMED_OUT
            } catch (_: IOException) {
                LocalScanStatus.UNAVAILABLE
            } catch (_: SecurityException) {
                LocalScanStatus.UNAVAILABLE
            }
        }
}
