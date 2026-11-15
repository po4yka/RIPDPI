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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class ProxyScanner(
    private val dispatchers: AppCoroutineDispatchers,
    private val loopbackHosts: List<String> = listOf("127.0.0.1", "::1"),
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
        require(maxConcurrency > 0)
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
        for ((index, port) in eligible.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(ScanProgress(ScanPhase.POPULAR_PORTS, index + 1, eligible.size, port))
            val found = tryPort(port)
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
            val scanned = AtomicInteger(0)
            val found = CompletableDeferred<ProxyEndpoint>()

            onProgress(ScanProgress(ScanPhase.FULL_RANGE, 0, ports.size, ports.first()))
            val workerCount = minOf(maxConcurrency, ports.size)
            val workers =
                launch {
                    repeat(workerCount) { workerIndex ->
                        launch worker@{
                            for (index in workerIndex until ports.size step workerCount) {
                                coroutineContext.ensureActive()
                                if (found.isCompleted) return@worker
                                val port = ports[index]
                                val count = scanned.incrementAndGet()
                                if (count % progressUpdateEvery == 0) {
                                    onProgress(ScanProgress(ScanPhase.FULL_RANGE, count, ports.size, port))
                                }
                                val candidate = tryPort(port)
                                if (candidate != null) {
                                    found.complete(candidate)
                                    return@worker
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
