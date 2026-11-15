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

private val DetectionLoopbackHosts = listOf("127.0.0.1", "::1")
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

    suspend fun findOpenProxyEndpoint(onProgress: suspend (ScanProgress) -> Unit): ProxyEndpoint? =
        withTimeoutOrNull(scanDeadlineMs) {
            withContext(dispatchers.io) {
                val foundOnPopular = scanPopularPorts(onProgress)
                if (foundOnPopular != null || !fullRangeEnabled) return@withContext foundOnPopular
                scanFullRange(onProgress)
            }
        }

    private suspend fun scanPopularPorts(onProgress: suspend (ScanProgress) -> Unit): ProxyEndpoint? {
        val eligible = popularPorts.filter { it in scanRange && it !in excludePorts }
        if (eligible.isEmpty()) return null
        onProgress(ScanProgress(ScanPhase.POPULAR_PORTS, 0, eligible.size, eligible.first()))
        for ((index, port) in eligible.withIndex()) {
            coroutineContext.ensureActive()
            val found = tryPort(port)
            onProgress(ScanProgress(ScanPhase.POPULAR_PORTS, index + 1, eligible.size, port))
            if (found != null) return found
        }
        return null
    }

    @Suppress("NestedBlockDepth")
    private suspend fun scanFullRange(onProgress: suspend (ScanProgress) -> Unit): ProxyEndpoint? =
        coroutineScope {
            val skipSet = popularPorts.toHashSet() + excludePorts
            val ports = scanRange.filterNot(skipSet::contains)
            if (ports.isEmpty()) return@coroutineScope null
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
                                val candidate = tryPort(port)
                                val progress =
                                    completed.updateAndGet {
                                        it.copy(
                                            scanned = it.scanned + 1,
                                            currentPort = port,
                                        )
                                    }
                                if (candidate != null) {
                                    found.complete(candidate)
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

            select<ProxyEndpoint?> {
                found.onAwait { it }
                workers.onJoin { null }
            }.also {
                workers.cancelAndJoin()
                val finalProgress = completed.get()
                if (finalProgress.scanned > publishedCount) onProgress(finalProgress)
            }
        }

    private suspend fun tryPort(port: Int): ProxyEndpoint? {
        for (host in loopbackHosts) {
            coroutineContext.ensureActive()
            val type =
                ProxyProber.probeNoAuthProxyType(
                    host = host,
                    port = port,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                ) ?: continue

            return ProxyEndpoint(host, port, type)
        }
        return null
    }
}
