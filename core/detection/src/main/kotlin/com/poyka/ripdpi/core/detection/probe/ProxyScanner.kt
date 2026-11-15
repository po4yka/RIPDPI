@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

private val ValidProxyScanPorts = 1..65535

internal class ProxyScanner(
    private val dispatchers: AppCoroutineDispatchers,
    private val loopbackHosts: List<String> = DetectionLoopbackHosts,
    private val popularPorts: List<Int> =
        (
            VpnAppCatalog.localhostProxyPorts + listOf(1081, 7890, 7891)
        ).distinct().sorted(),
    private val scanRange: IntRange = 1024..65535,
    private val fullRangeEnabled: Boolean = true,
    private val connectTimeoutMs: Int = 15,
    private val readTimeoutMs: Int = 30,
    private val maxConcurrency: Int = 64,
    private val progressUpdateEvery: Int = 256,
    private val excludePorts: Set<Int> = KnownLocalServices.excludedPorts,
    private val scanDeadlineMs: Long = LocalScanDeadlineMs,
) {
    init {
        require(loopbackHosts.isNotEmpty() && loopbackHosts.all(DetectionLoopbackHosts::contains))
        require(popularPorts.all(ValidProxyScanPorts::contains))
        require(
            scanRange.isEmpty() || (scanRange.first in ValidProxyScanPorts && scanRange.last in ValidProxyScanPorts),
        )
        require(maxConcurrency > 0)
        require(progressUpdateEvery > 0)
    }

    suspend fun scan(onProgress: suspend (ScanProgress) -> Unit): LocalScanResult<ProxyEndpoint> =
        withTimeoutOrNull(scanDeadlineMs) {
            withContext(dispatchers.io) {
                val popular = scanPopularPorts(onProgress)
                if (popular.match != null || !fullRangeEnabled) return@withContext popular
                val full = scanFullRange(onProgress)
                full.copy(status = popular.status.merge(full.status))
            }
        } ?: LocalScanResult(null, LocalScanStatus.TIMED_OUT)

    private suspend fun scanPopularPorts(onProgress: suspend (ScanProgress) -> Unit): LocalScanResult<ProxyEndpoint> {
        val eligible = popularPorts.filter { it in scanRange && it !in excludePorts }
        if (eligible.isEmpty()) return LocalScanResult(null, LocalScanStatus.SKIPPED)
        var status = LocalScanStatus.NO_MATCH
        onProgress(ScanProgress(ScanPhase.POPULAR_PORTS, 0, eligible.size, eligible.first()))
        for ((index, port) in eligible.withIndex()) {
            coroutineContext.ensureActive()
            val result = tryPort(port)
            onProgress(ScanProgress(ScanPhase.POPULAR_PORTS, index + 1, eligible.size, port))
            if (result.match != null) return result
            status = status.merge(result.status)
        }
        return LocalScanResult(null, status)
    }

    @Suppress("NestedBlockDepth")
    private suspend fun scanFullRange(onProgress: suspend (ScanProgress) -> Unit): LocalScanResult<ProxyEndpoint> =
        coroutineScope {
            val skipSet = popularPorts.toHashSet() + excludePorts
            val ports = scanRange.filterNot(skipSet::contains)
            if (ports.isEmpty()) return@coroutineScope LocalScanResult(null, LocalScanStatus.SKIPPED)
            val status = AtomicReference(LocalScanStatus.NO_MATCH)
            val completed = AtomicReference(ScanProgress(ScanPhase.FULL_RANGE, 0, ports.size, ports.first()))
            val found = CompletableDeferred<ProxyEndpoint>()
            val publishLock = Mutex()
            var publishedCount = 0

            onProgress(completed.get())
            val workerCount = minOf(maxConcurrency, ports.size)
            val workers =
                launch {
                    repeat(workerCount) { workerIndex ->
                        launch worker@{
                            for (index in workerIndex until ports.size step workerCount) {
                                coroutineContext.ensureActive()
                                if (found.isCompleted) return@worker
                                val port = ports[index]
                                val result = tryPort(port)
                                status.updateAndGet { it.merge(result.status) }
                                val progress =
                                    completed.updateAndGet {
                                        it.copy(
                                            scanned = it.scanned + 1,
                                            currentPort = port,
                                        )
                                    }
                                result.match?.let { match ->
                                    found.complete(match)
                                    return@worker
                                }
                                if (progress.scanned % progressUpdateEvery == 0) {
                                    publishLock.withLock {
                                        if (progress.scanned > publishedCount) {
                                            onProgress(progress)
                                            publishedCount = progress.scanned
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            val match =
                select<ProxyEndpoint?> {
                    found.onAwait { it }
                    workers.onJoin { null }
                }
            workers.cancelAndJoin()
            val finalProgress = completed.get()
            if (finalProgress.scanned > publishedCount) onProgress(finalProgress)
            LocalScanResult(match, status.get())
        }

    private suspend fun tryPort(port: Int): LocalScanResult<ProxyEndpoint> {
        var status = LocalScanStatus.NO_MATCH
        for (host in loopbackHosts) {
            coroutineContext.ensureActive()
            val next =
                when (ProxyProber.probePort(host, port, connectTimeoutMs, readTimeoutMs)) {
                    ProxyProber.PortProbeResult.SOCKS5_NO_AUTH -> {
                        return LocalScanResult(ProxyEndpoint(host, port, ProxyType.SOCKS5), LocalScanStatus.MATCHED)
                    }

                    ProxyProber.PortProbeResult.HTTP_CONNECT_PROXY -> {
                        return LocalScanResult(ProxyEndpoint(host, port, ProxyType.HTTP), LocalScanStatus.MATCHED)
                    }

                    ProxyProber.PortProbeResult.CLOSED,
                    ProxyProber.PortProbeResult.UNKNOWN_TCP_SERVICE,
                    -> {
                        LocalScanStatus.NO_MATCH
                    }

                    ProxyProber.PortProbeResult.REJECTED -> {
                        LocalScanStatus.REJECTED
                    }

                    ProxyProber.PortProbeResult.TIMED_OUT -> {
                        LocalScanStatus.TIMED_OUT
                    }

                    ProxyProber.PortProbeResult.UNAVAILABLE -> {
                        LocalScanStatus.UNAVAILABLE
                    }

                    ProxyProber.PortProbeResult.PARTIAL -> {
                        LocalScanStatus.PARTIAL
                    }
                }
            status = status.merge(next)
        }
        return LocalScanResult(null, status)
    }
}
