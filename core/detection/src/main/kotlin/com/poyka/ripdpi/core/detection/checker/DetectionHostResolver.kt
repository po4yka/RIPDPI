package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

private const val MaxConcurrentDetectionLookups = 2
private val lookupSlots = Semaphore(MaxConcurrentDetectionLookups)
private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal suspend fun resolveDetectionAddresses(
    host: String,
    timeoutMs: Long = 3_000L,
): List<InetAddress>? =
    NumericIp.parseAddress(host)?.let { listOf(it.toInetAddress()) }
        ?: resolveDetectionHostname(timeoutMs) { InetAddress.getAllByName(host).toList() }

internal suspend fun resolveDetectionHostname(
    timeoutMs: Long,
    lookup: () -> List<InetAddress>,
): List<InetAddress>? =
    withContext(Dispatchers.Default) {
        // getaddrinfo cannot be interrupted. Keep its lease until it returns, but detach its caller.
        val pending = lookupScope.async { lookupSlots.withPermit { lookup() } }
        try {
            withTimeoutOrNull(timeoutMs) { pending.await() }
        } finally {
            // Cancel a queued lookup. An active blocking lookup keeps its slot until its finally runs.
            pending.cancel()
        }
    }
