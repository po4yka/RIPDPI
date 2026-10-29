package com.poyka.ripdpi.proxyimport

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** U+FEFF, which some editors put at the start of a UTF-8 file and the `.conf` parser does not trim. */
internal val ByteOrderMark: String = Char(0xFEFF).toString()

/** Largest shared or picked import file RIPDPI reads; real `.conf` files and keys are a few KiB. */
internal const val MaxImportFileBytes = PendingProxyImportStore.MaxAmneziaWgConfBytes
private const val ImportReadTimeoutMillis = 3_000L
private const val ImportReadConcurrency = 2
private const val ImportWorkerIdleSeconds = 30L

/** Returns null for a failed, timed-out, oversized, or binary import. Admission never queues work. */
internal suspend fun readImportText(
    resolver: ContentResolver,
    uri: Uri,
): String? =
    withTimeoutOrNull(ImportReadTimeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val request = ImportReadRequest(resolver, uri) { continuation.resume(it) }
            continuation.invokeOnCancellation { request.cancel() }
            try {
                ImportReaderWorkers.reads.execute(request)
            } catch (_: RejectedExecutionException) {
                request.complete(null)
            }
        }
    }

private object ImportReaderWorkers {
    val reads = boundedExecutor("ripdpi-import-read")
    val cancellations = boundedExecutor("ripdpi-import-cancel")

    private fun boundedExecutor(name: String): ThreadPoolExecutor =
        ThreadPoolExecutor(
            0,
            ImportReadConcurrency,
            ImportWorkerIdleSeconds,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        )
}

private class ImportReadRequest(
    private val resolver: ContentResolver,
    private val uri: Uri,
    onComplete: (String?) -> Unit,
) : Runnable {
    private val cancelled = AtomicBoolean(false)
    private val cancellationSignal = CancellationSignal()
    private val completion = AtomicReference<((String?) -> Unit)?>(onComplete)
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ImportReadTimeoutMillis)

    override fun run() {
        // Provider failures must not crash the process that also hosts the VPN.
        val text =
            runCatching {
                if (cancelled.get() || System.nanoTime() >= deadlineNanos) return@runCatching null
                resolver.openAssetFileDescriptor(uri, "r", cancellationSignal)?.use { descriptor ->
                    readImportDescriptor(descriptor, deadlineNanos, cancelled::get)
                }
            }.getOrNull()
        complete(text)
    }

    fun complete(text: String?) {
        completion.getAndSet(null)?.invoke(text)
    }

    fun cancel() {
        cancelled.set(true)
        completion.set(null)
        // Cancellation can call an untrusted provider through Binder. Keep it off Main and bounded too.
        try {
            ImportReaderWorkers.cancellations.execute { runCatching { cancellationSignal.cancel() } }
        } catch (_: RejectedExecutionException) {
            // A provider that ignores cancellation may occupy a worker, but cannot grow either pool.
            Unit
        }
    }
}

/** Decodes a bounded UTF-8 file and strips an optional byte-order mark. */
internal fun decodeImportText(bytes: ByteArray): String? {
    if (bytes.size > MaxImportFileBytes) return null
    val text = String(bytes, Charsets.UTF_8).removePrefix(ByteOrderMark)
    return text.takeUnless { '�' in it }
}
