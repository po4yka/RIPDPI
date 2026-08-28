package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.SshHostKeyProbeFailure
import com.poyka.ripdpi.core.SshHostKeyProbeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

internal class SshProbeOperationLease(
    private val caller: Job? = null,
) {
    private val active = AtomicBoolean(true)

    // Job cancellation is visible before the caller's finally can be dispatched.
    fun isActive(): Boolean = active.get() && caller?.isActive != false

    fun revoke() {
        active.set(false)
    }
}

/** Owns blocking DNS/JNI work until its cleanup ends, even if the requesting screen leaves. */
internal class SshProbeOperationRunner(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = ProbeTimeoutMillis,
) {
    private val lock = Any()
    private var active: Deferred<SshHostKeyProbeResult>? = null

    suspend fun run(operation: suspend (SshProbeOperationLease) -> SshHostKeyProbeResult): SshHostKeyProbeResult {
        val lease = SshProbeOperationLease(currentCoroutineContext()[Job])
        val worker =
            synchronized(lock) {
                if (active?.isCompleted == false) {
                    return SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.Busy)
                }
                scope
                    .async(dispatcher, start = CoroutineStart.LAZY) {
                        try {
                            operation(lease)
                        } finally {
                            lease.revoke()
                        }
                    }.also { active = it }
            }
        worker.start()
        return try {
            withTimeout(timeoutMillis) { worker.await() }
        } catch (_: TimeoutCancellationException) {
            SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.Timeout)
        } finally {
            // Do not cancel the owned worker: blocking DNS and JNI must finish before reuse.
            lease.revoke()
        }
    }

    private companion object {
        const val ProbeTimeoutMillis = 10_000L
    }
}
