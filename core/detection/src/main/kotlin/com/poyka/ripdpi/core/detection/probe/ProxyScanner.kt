@file:Suppress("MagicNumber")

package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.max

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private val maxConcurrency: Int = 512,
    private val progressUpdateEvery: Int = 256,
    private val excludePorts: Set<Int> = KnownLocalServices.excludedPorts,
) {
    suspend fun findOpenProxyEndpoint(onProgress: suspend (ScanProgress) -> Unit): ProxyEndpoint? {
        val foundOnPopular = scanPopularPorts(onProgress)
        if (foundOnPopular != null || !fullRangeEnabled) return foundOnPopular
        return scanFullRange(onProgress)
    }

    private suspend fun scanPopularPorts(onProgress: suspend (ScanProgress) -> Unit): ProxyEndpoint? {
        val eligible = popularPorts.filter { it in scanRange && it !in excludePorts }
        for ((index, port) in eligible.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(
                ScanProgress(
                    phase = ScanPhase.POPULAR_PORTS,
                    scanned = index + 1,
                    total = eligible.size,
                    currentPort = port,
                ),
            )
            val found = tryPort(port)
            if (found != null) return found
        }
        return null
    }

    @Suppress("NestedBlockDepth")
    private suspend fun scanFullRange(onProgress: suspend (ScanProgress) -> Unit): ProxyEndpoint? =
        withContext(dispatchers.io) {
            coroutineScope {
                val popularSet = popularPorts.toHashSet()
                val skipSet = popularSet + excludePorts
                val firstPort = scanRange.firstOrNull { it !in skipSet } ?: return@coroutineScope null
                val total = scanRange.count { it !in skipSet }
                val scanned = AtomicInteger(0)
                val found = AtomicReference<ProxyEndpoint?>(null)

                val dispatcher = dispatchers.io.limitedParallelism(max(1, maxConcurrency))

                onProgress(
                    ScanProgress(
                        phase = ScanPhase.FULL_RANGE,
                        scanned = 0,
                        total = total,
                        currentPort = firstPort,
                    ),
                )

                val jobs =
                    (0 until maxConcurrency).map { workerIndex ->
                        launch(dispatcher) {
                            var port = scanRange.first + workerIndex
                            while (port <= scanRange.last) {
                                coroutineContext.ensureActive()
                                if (found.get() != null) return@launch
                                if (port !in skipSet) {
                                    val count = scanned.incrementAndGet()
                                    if (count % progressUpdateEvery == 0) {
                                        onProgress(
                                            ScanProgress(
                                                phase = ScanPhase.FULL_RANGE,
                                                scanned = count,
                                                total = total,
                                                currentPort = port,
                                            ),
                                        )
                                    }

                                    val candidate = tryPort(port)
                                    if (candidate != null) {
                                        found.compareAndSet(null, candidate)
                                        return@launch
                                    }
                                }
                                port += maxConcurrency
                            }
                        }
                    }
                jobs.joinAll()

                found.get()
            }
        }

    private suspend fun tryPort(port: Int): ProxyEndpoint? =
        withContext(dispatchers.io) {
            for (host in loopbackHosts) {
                val type =
                    ProxyProber.probeNoAuthProxyType(
                        host = host,
                        port = port,
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = readTimeoutMs,
                    ) ?: continue

                return@withContext ProxyEndpoint(host, port, type)
            }
            null
        }
}
