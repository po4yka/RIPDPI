package com.poyka.ripdpi.core

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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

interface RipDpiProxyBindings {
    fun create(configJson: String): Long

    fun start(handle: Long): Int

    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    fun destroy(handle: Long)

    fun updateNetworkSnapshot(
        handle: Long,
        snapshotJson: String,
    )
}

class RipDpiProxyNativeBindings
    @Inject
    constructor() : RipDpiProxyBindings {
        companion object {
            init {
                RipDpiNativeLoader.ensureLoaded()
            }

            /**
             * Register a VPN socket protection callback. Called when VPN service starts.
             * Stores a global JNI reference to the VpnService for direct protect(fd) calls
             * from native code, replacing the Unix domain socket approach.
             */
            @JvmStatic
            external fun jniRegisterVpnProtect(vpnService: Any)

            /** Unregister the VPN socket protection callback. Called when VPN service stops. */
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

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(handle: Long): Int

        private external fun jniStop(handle: Long)

        private external fun jniPollTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)

        private external fun jniUpdateNetworkSnapshot(
            handle: Long,
            snapshotJson: String,
        )
    }

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
        val deadline = System.currentTimeMillis() + timeoutMillis
        var pollCount = 0L
        while (true) {
            if (startupSignal.isCompleted) {
                startupSignal.await()
                return
            }

            val telemetry = pollTelemetry()
            if (telemetry.state == "running") {
                startupSignal.complete(Unit)
                startupSignal.await()
                return
            }

            pollCount++
            if (pollCount % readyLogPollInterval == 0L) {
                val elapsed = timeoutMillis - (deadline - System.currentTimeMillis())
                Logger.d { "Proxy readiness: state=${telemetry.state} elapsed=${elapsed}ms" }
            }

            if (System.currentTimeMillis() >= deadline) {
                Logger.w { "Proxy readiness timed out after ${timeoutMillis}ms" }
                error("Timed out waiting for proxy readiness")
            }

            delay(READY_POLL_INTERVAL_MS)
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
