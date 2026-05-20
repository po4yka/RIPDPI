package com.poyka.ripdpi.core

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

interface RipDpiProxyRuntime {
    suspend fun startProxy(preferences: RipDpiProxyPreferences): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultProxyReadyTimeoutMs)

    suspend fun stopProxy()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot

    suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot)
}

internal const val defaultProxyReadyTimeoutMs = 5_000L
private const val readyLogPollInterval = 20L
private val geoDatabaseVersionsJson = Json { ignoreUnknownKeys = true }

/**
 * Thin JNI binding surface over the embedded SOCKS-proxy native session in
 * `libripdpi.so` (Rust crate `ripdpi-android`, delegating to
 * `ripdpi-android-proxy-adapter`). Each method maps 1:1 onto an `external` JNI
 * function; the interface exists so the lifecycle can be faked in unit tests.
 *
 * ## Handle lifecycle and ordering
 * One session is driven through [create] -> [start] -> [stop] -> [destroy], in
 * that order. [create] returns an opaque non-zero `Long` handle — a key into a
 * native registry, never a raw pointer — or `0` when session creation fails.
 * Every later call takes that handle; once [destroy] returns the handle is
 * retired and must never be reused.
 *
 * - [create] parses the config JSON, opens the loopback listener and registers
 *   an `Idle` session.
 * - [start] transitions `Idle -> Running` and then **blocks for the whole
 *   proxy lifetime**, returning `0` on a clean shutdown or a positive `errno`
 *   on failure. Always invoke it on a background dispatcher.
 * - [stop] signals the blocked [start] to unwind; it returns immediately.
 * - [destroy] retires the session and must run only after [start] has returned.
 *
 * ## Idempotency
 * [stop] is **not** silently idempotent: invoking it when the session is not
 * `Running` throws `IllegalStateException`. Callers either serialize lifecycle
 * calls under a mutex (as [RipDpiProxy] does) or treat that exception as
 * benign. [destroy] throws `IllegalStateException` if the session is still
 * `Running`. A failed [create] returns `0` rather than throwing for the null
 * handle, but malformed config still throws `IllegalArgumentException`.
 *
 * ## fd ownership
 * The proxy adopts no externally supplied file descriptors: it binds and owns
 * its own loopback listener socket and closes it during shutdown. Outbound
 * sockets opened by the native runtime are kept off the VPN tunnel via the
 * [RipDpiProxyNativeBindings.jniRegisterVpnProtect] callback — see the
 * `vpnservice-protect-invariant` rule.
 *
 * ## Error mapping
 * Native failures surface as Java exceptions thrown inside the JNI frame and
 * observed on return: `IllegalArgumentException` (bad handle or config),
 * `IllegalStateException` (wrong lifecycle state), `IOException` (listener or
 * socket failure) and `RuntimeException` (serialization failure or a contained
 * Rust panic). [start] additionally encodes failure as a positive `errno`
 * return value.
 *
 * ## Blocking
 * Only [start] blocks (for the proxy's entire run). [create], [stop],
 * [destroy], [pollTelemetry] and [updateNetworkSnapshot] are non-blocking; they
 * still run off the main thread because they cross JNI.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §6 (panic
 * containment), §7 (error mapping) and §10 (VpnService.protect callback).
 */
interface RipDpiProxyBindings {
    /** Creates an `Idle` proxy session from [configJson]; returns its handle, or `0` on failure. */
    fun create(configJson: String): Long

    /**
     * Runs the proxy event loop for [handle] and **blocks** until it stops.
     * Returns `0` on a clean shutdown or a positive `errno` on failure.
     */
    fun start(handle: Long): Int

    /** Signals a `Running` session to shut down; throws `IllegalStateException` if it is not running. */
    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    /** Retires the session for [handle]; must run only after [start] has returned. */
    fun destroy(handle: Long)

    fun updateNetworkSnapshot(
        handle: Long,
        snapshotJson: String,
    )

    fun geoDatabaseVersions(
        geoipDbPath: String,
        geositeDbPath: String,
    ): RipDpiGeoDatabaseVersions?

    fun geoIpMetadata(
        geoipDbPath: String,
        geositeDbPath: String,
        ip: String,
    ): RipDpiGeoIpMetadata?
}

@Serializable
data class RipDpiGeoDatabaseVersions(
    val geoipVersion: String? = null,
    val geositeVersion: String? = null,
)

@Serializable
data class RipDpiGeoIpMetadata(
    val countryCode: String? = null,
    val asn: Int? = null,
    val organization: String? = null,
)

class RipDpiProxyNativeBindings
    @Inject
    constructor() : RipDpiProxyBindings {
        companion object {
            init {
                RipDpiNativeLoader.ensureLoaded()
            }

            /**
             * Register a VPN socket protection callback.
             *
             * Stores a JNI `GlobalRef` to the [vpnService] so native code can call
             * `VpnService.protect(fd)` directly, instead of the Unix-domain-socket
             * relay. Must be called **before** any session [create]/[start] so the
             * native runtime can protect outbound sockets the moment it opens them;
             * registering later risks an unprotected socket looping into the TUN
             * (see the `vpnservice-protect-invariant` rule).
             *
             * The held `GlobalRef` is process-global and is released only by
             * [jniUnregisterVpnProtect]; failing to unregister leaks the reference
             * and pins the `VpnService` instance.
             */
            @JvmStatic
            external fun jniRegisterVpnProtect(vpnService: Any)

            /**
             * Unregister the VPN socket protection callback and release the
             * `GlobalRef` held since [jniRegisterVpnProtect]. Call when the VPN
             * service stops, after the proxy session has been [stop]ped/[destroy]ed.
             * Safe to call when no callback is registered.
             */
            @JvmStatic
            external fun jniUnregisterVpnProtect()
        }

        override fun create(configJson: String): Long = jniCreate(configJson)

        override fun start(handle: Long): Int = jniStart(handle)

        override fun stop(handle: Long) {
            jniStop(handle)
        }

        override fun pollTelemetry(handle: Long): String? = jniPollTelemetry(handle)

        override fun destroy(handle: Long) {
            jniDestroy(handle)
        }

        override fun updateNetworkSnapshot(
            handle: Long,
            snapshotJson: String,
        ) {
            jniUpdateNetworkSnapshot(handle, snapshotJson)
        }

        override fun geoDatabaseVersions(
            geoipDbPath: String,
            geositeDbPath: String,
        ): RipDpiGeoDatabaseVersions? =
            jniGeoDatabaseVersions(geoipDbPath, geositeDbPath)
                ?.let { payload ->
                    runCatching {
                        geoDatabaseVersionsJson.decodeFromString<RipDpiGeoDatabaseVersions>(payload)
                    }.getOrNull()
                }

        override fun geoIpMetadata(
            geoipDbPath: String,
            geositeDbPath: String,
            ip: String,
        ): RipDpiGeoIpMetadata? =
            jniGeoIpMetadata(geoipDbPath, geositeDbPath, ip)
                ?.let { payload ->
                    runCatching {
                        geoDatabaseVersionsJson.decodeFromString<RipDpiGeoIpMetadata>(payload)
                    }.getOrNull()
                }

        external fun jniStartPcapRecording(
            handle: Long,
            dirPath: String,
            maxBytes: Long,
        ): Boolean

        external fun jniStopPcapRecording(handle: Long): String?

        external fun jniIsPcapRecording(handle: Long): Boolean

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(handle: Long): Int

        private external fun jniStop(handle: Long)

        private external fun jniPollTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)

        private external fun jniUpdateNetworkSnapshot(
            handle: Long,
            snapshotJson: String,
        )

        private external fun jniGeoDatabaseVersions(
            geoipDbPath: String,
            geositeDbPath: String,
        ): String?

        private external fun jniGeoIpMetadata(
            geoipDbPath: String,
            geositeDbPath: String,
            ip: String,
        ): String?
    }

/**
 * Coroutine-friendly owner of a single native proxy handle (see
 * [RipDpiProxyBindings] for the raw JNI contract).
 *
 * Holds at most one live handle at a time and serializes every
 * lifecycle-sensitive JNI call under [mutex], so [stopProxy]/`destroy` cannot
 * retire the handle while another call is mid-flight. [startProxy] keeps the
 * blocking native [RipDpiProxyBindings.start] on `Dispatchers.IO` and treats
 * its return as the session ending — it then `destroy`s the handle in a
 * `finally` block, so callers never call `destroy` directly. A second
 * concurrent [startProxy] throws `NativeError.AlreadyRunning`; calling
 * [stopProxy]/[pollTelemetry] with no live handle throws `NativeError.NotRunning`
 * or yields an idle snapshot respectively.
 *
 * See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle).
 */
class RipDpiProxy(
    private val nativeBindings: RipDpiProxyBindings,
) : RipDpiProxyRuntime {
    private companion object {
        private const val READY_POLL_INTERVAL_MS = 50L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var handle = 0L
    private var readinessSignal: CompletableDeferred<Unit>? = null

    private suspend fun <T> withActiveHandle(block: suspend (Long) -> T): T? =
        mutex.withLock {
            val currentHandle = handle
            if (currentHandle == 0L) {
                null
            } else {
                // Keep lifecycle-sensitive JNI calls under the mutex so stop/destroy
                // cannot retire the native handle while a call is still in flight.
                block(currentHandle)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        val startupSignal = CompletableDeferred<Unit>()
        val handle =
            mutex.withLock {
                if (this.handle != 0L) {
                    throw NativeError.AlreadyRunning("Proxy")
                }

                readinessSignal = startupSignal
                try {
                    val createdHandle =
                        withContext(Dispatchers.IO) {
                            nativeBindings.create(preferences.toNativeConfigJson())
                        }
                    if (createdHandle == 0L) {
                        Logger.e { "Proxy native session creation returned null handle" }
                        throw NativeError.SessionCreationFailed("proxy")
                    }
                    Logger.d { "Proxy native session created: handle=$createdHandle" }
                    this.handle = createdHandle
                    createdHandle
                } catch (error: Exception) {
                    readinessSignal = null
                    startupSignal.completeExceptionally(error)
                    throw error
                }
            }

        // Yield to allow the UNDISPATCHED caller to regain control before
        // the blocking native event loop occupies this thread.
        yield()

        try {
            val capturedHandle = handle
            val completionHandle =
                coroutineContext[Job]!!.invokeOnCompletion {
                    // Always dispatch stop -- do not gate on the mutex.
                    // The volatile handle field is readable without the lock, and
                    // nativeBindings.stop() is idempotent on the Rust side.
                    try {
                        if (this@RipDpiProxy.handle == capturedHandle && capturedHandle != 0L) {
                            nativeBindings.stop(capturedHandle)
                        }
                    } catch (_: IllegalStateException) {
                        // stop() throws if the proxy is not running -- safe to ignore.
                    }
                }
            return try {
                withContext(Dispatchers.IO) { nativeBindings.start(handle) }
            } finally {
                completionHandle.dispose()
            }
        } catch (error: Exception) {
            startupSignal.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock {
                if (this.handle == handle) {
                    try {
                        nativeBindings.destroy(handle)
                    } finally {
                        this.handle = 0L
                    }
                }
                if (!startupSignal.isCompleted) {
                    startupSignal.completeExceptionally(IllegalStateException("Proxy exited before becoming ready"))
                }
                if (readinessSignal === startupSignal && startupSignal.getCompletionExceptionOrNull() == null) {
                    readinessSignal = null
                }
            }
        }
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        val startupSignal =
            mutex.withLock {
                readinessSignal
            } ?: throw NativeError.NotRunning("Proxy")

        Logger.d { "Awaiting proxy readiness (timeout=${timeoutMillis}ms)" }
        var pollCount = 0L
        var lastState = "idle"
        var lastEventMessage: String? = null
        try {
            withTimeout(timeoutMillis) {
                while (true) {
                    if (startupSignal.isCompleted) {
                        startupSignal.await()
                        return@withTimeout
                    }

                    val telemetry = pollTelemetry()
                    lastState = telemetry.state
                    lastEventMessage = telemetry.nativeEvents.lastOrNull()?.message
                    if (telemetry.hasRuntimeReadyEvent()) {
                        startupSignal.complete(Unit)
                        startupSignal.await()
                        return@withTimeout
                    }

                    pollCount++
                    if (pollCount % readyLogPollInterval == 0L) {
                        Logger.d { "Proxy readiness: state=${telemetry.state} polls=$pollCount" }
                    }

                    delay(READY_POLL_INTERVAL_MS)
                }
            }
        } catch (_: TimeoutCancellationException) {
            val details =
                buildString {
                    append("Timed out waiting for proxy readiness")
                    append(" state=")
                    append(lastState)
                    lastEventMessage?.let {
                        append(" lastEvent=")
                        append(it)
                    }
                }
            Logger.w { "$details after ${timeoutMillis}ms" }
            error(details)
        }
    }

    override suspend fun stopProxy() {
        mutex.withLock {
            if (handle == 0L) {
                throw NativeError.NotRunning("Proxy")
            }

            nativeBindings.stop(handle)
        }
    }

    override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) {
        val snapshotJson = json.encodeToString(NativeNetworkSnapshot.serializer(), snapshot)
        withActiveHandle { currentHandle ->
            withContext(Dispatchers.IO) { nativeBindings.updateNetworkSnapshot(currentHandle, snapshotJson) }
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        val payload: String? =
            withActiveHandle { currentHandle ->
                withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(currentHandle) }
            } ?: return NativeRuntimeSnapshot.idle(source = "proxy")
        return payload
            ?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
            ?: NativeRuntimeSnapshot.idle(source = "proxy")
    }
}

private fun NativeRuntimeSnapshot.hasRuntimeReadyEvent(): Boolean = nativeEvents.any { it.kind == "runtime_ready" }
