package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.lifetime.HandleReservation
import com.poyka.ripdpi.data.NativeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Thin JNI binding surface over the network-diagnostics native session in
 * `libripdpi.so` (Rust crate `ripdpi-android`, delegating to
 * `ripdpi-android-diagnostics-adapter`). Each method maps 1:1 onto an
 * `external` JNI function; the interface exists so it can be faked in tests.
 *
 * ## Handle lifecycle and ordering
 * [create] registers a session and returns an opaque non-zero `Long` handle (a
 * native registry key, not a pointer), or `0` on failure. The handle then
 * accepts any number of scan cycles — each cycle is [startScan] followed by
 * polling via [pollProgress]/[takeReport] and optional [cancelScan] — until
 * [destroy] retires it. [pollPassiveEvents] is independent of scan state. After
 * [destroy] the handle is dead and must never be reused.
 *
 * ## Idempotency
 * [startScan] is **not** idempotent: starting a second scan while one is
 * running throws `IllegalStateException` ("diagnostics scan already running").
 * [cancelScan] **is** idempotent — it is a no-op when no scan is active.
 * [takeReport] has take semantics: it consumes the finished report, so a second
 * call returns `null` until the next scan completes.
 *
 * ## fd ownership
 * Diagnostics adopts no externally supplied file descriptors; any probe sockets
 * it opens are created and closed entirely within the native session.
 *
 * ## Error mapping
 * Native failures throw Java exceptions from the JNI frame:
 * `IllegalArgumentException` (invalid/unknown handle, malformed request JSON or
 * session id), `IllegalStateException` (scan already running), and
 * `RuntimeException` (session-creation failure or a contained Rust panic). The
 * `String?`-returning polls yield `null` on a contained panic.
 *
 * ## Blocking
 * No method blocks. [startScan] spawns the scan on a native worker and returns
 * immediately; progress and results are observed by polling. There is no JNI
 * callback channel — the Kotlin side polls.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §7 (error
 * mapping), §8 (callback rules) and the diagnostics scan pipeline in
 * `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`.
 */
interface NetworkDiagnosticsBindings {
    /** Registers a diagnostics session; returns its handle, or `0` on failure. */
    fun create(): Long

    /**
     * Spawns a scan for [requestJson] tagged [sessionId] and returns immediately.
     * Throws `IllegalStateException` if a scan is already running on [handle].
     */
    fun startScan(
        handle: Long,
        requestJson: String,
        sessionId: String,
    )

    /** Requests cancellation of the running scan; a no-op when no scan is active. */
    fun cancelScan(handle: Long)

    fun pollProgress(handle: Long): String?

    /** Returns the finished scan report once, consuming it; `null` until the next scan completes. */
    fun takeReport(handle: Long): String?

    fun pollPassiveEvents(handle: Long): String?

    /** Retires the session for [handle]; the handle must not be reused afterwards. */
    fun destroy(handle: Long)
}

class NetworkDiagnosticsNativeBindings
    @Inject
    constructor() : NetworkDiagnosticsBindings {
        companion object {
            init {
                RipDpiNativeLoader.ensureLoaded()
            }
        }

        override fun create(): Long = jniCreate()

        override fun startScan(
            handle: Long,
            requestJson: String,
            sessionId: String,
        ) {
            jniStartScan(handle, requestJson, sessionId)
        }

        override fun cancelScan(handle: Long) {
            jniCancelScan(handle)
        }

        override fun pollProgress(handle: Long): String? = jniPollProgress(handle)

        override fun takeReport(handle: Long): String? = jniTakeReport(handle)

        override fun pollPassiveEvents(handle: Long): String? = jniPollPassiveEvents(handle)

        override fun destroy(handle: Long) {
            jniDestroy(handle)
        }

        private external fun jniCreate(): Long

        private external fun jniStartScan(
            handle: Long,
            requestJson: String,
            sessionId: String,
        )

        private external fun jniCancelScan(handle: Long)

        private external fun jniPollProgress(handle: Long): String?

        private external fun jniTakeReport(handle: Long): String?

        private external fun jniPollPassiveEvents(handle: Long): String?

        private external fun jniDestroy(handle: Long)
    }

interface NetworkDiagnosticsBridge {
    suspend fun startScan(
        requestJson: String,
        sessionId: String,
    )

    suspend fun cancelScan()

    suspend fun pollProgressJson(): String?

    suspend fun takeReportJson(): String?

    suspend fun pollPassiveEventsJson(): String?

    suspend fun destroy()
}

/**
 * Coroutine-friendly owner of a single native diagnostics handle (see
 * [NetworkDiagnosticsBindings] for the raw JNI contract).
 *
 * The handle is created **lazily**: `ensureHandleExclusive` calls
 * [NetworkDiagnosticsBindings.create] on the first scan or poll and reuses it
 * for the lifetime of this instance, so there is no separate create step for
 * callers. Read-style JNI calls use [HandleReservation], so polls can overlap
 * and [destroy] drains them before retiring the handle. [destroy] is idempotent
 * — a no-op once the handle has been cleared — and after it the next call
 * lazily creates a fresh handle. [cancelScan] is a no-op when no handle exists
 * and never creates a new handle.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle).
 */
class NetworkDiagnostics
    @Inject
    constructor(
        private val nativeBindings: NetworkDiagnosticsBindings,
    ) : NetworkDiagnosticsBridge {
        private val reservations = HandleReservation()
        private var handle = 0L

        private fun ensureHandleExclusive(): Long {
            if (handle == 0L) {
                val createdHandle = nativeBindings.create()
                if (createdHandle == 0L) {
                    throw NativeError.SessionCreationFailed("diagnostics")
                }
                handle = createdHandle
            }
            return handle
        }

        private suspend fun ensureHandle() {
            reservations.withExclusive {
                ensureHandleExclusive()
            }
        }

        private suspend fun <T> withLazyHandle(block: suspend (Long) -> T): T {
            while (true) {
                val reservedCall =
                    reservations.withReservationOrNull({ handle }) { currentHandle ->
                        ReservedCall(block(currentHandle))
                    }
                if (reservedCall != null) {
                    return reservedCall.value
                }
                ensureHandle()
            }
        }

        override suspend fun startScan(
            requestJson: String,
            sessionId: String,
        ) {
            withLazyHandle { h ->
                withContext(Dispatchers.IO) { nativeBindings.startScan(h, requestJson, sessionId) }
            }
        }

        override suspend fun cancelScan() {
            reservations.withReservationOrNull({ handle }) { currentHandle ->
                withContext(Dispatchers.IO) { nativeBindings.cancelScan(currentHandle) }
            }
        }

        override suspend fun pollProgressJson(): String? =
            withLazyHandle { h ->
                withContext(Dispatchers.IO) { nativeBindings.pollProgress(h) }
            }

        override suspend fun takeReportJson(): String? =
            withLazyHandle { h ->
                withContext(Dispatchers.IO) { nativeBindings.takeReport(h) }
            }

        override suspend fun pollPassiveEventsJson(): String? =
            withLazyHandle { h ->
                withContext(Dispatchers.IO) { nativeBindings.pollPassiveEvents(h) }
            }

        override suspend fun destroy() {
            reservations.withExclusiveNonCancellable {
                if (handle != 0L) {
                    val currentHandle = handle
                    try {
                        withContext(Dispatchers.IO) { nativeBindings.destroy(currentHandle) }
                    } finally {
                        handle = 0L
                    }
                }
            }
        }
    }

private data class ReservedCall<T>(
    val value: T,
)
