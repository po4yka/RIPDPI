package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import java.io.Closeable
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> Closeable.withStunCancellation(action: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        if (continuation.isActive) {
            try {
                continuation.resume(action())
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

internal suspend fun resolveStunAddress(
    host: String,
    lookup: (String) -> InetAddress = InetAddress::getByName,
): InetAddress {
    NumericIp.parseAddress(host)?.let { return it.toInetAddress() }
    StunLookupSlots.acquire()
    val pending = StunLookupScope.async { lookup(host) }
    // A cancelled caller must not free admission while its blocking platform lookup still runs.
    pending.invokeOnCompletion { StunLookupSlots.release() }
    return pending.await()
}

private const val MaxConcurrentStunLookups = 2
private val StunLookupSlots = Semaphore(MaxConcurrentStunLookups)
private val StunLookupScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MaxConcurrentStunLookups))
