package com.poyka.ripdpi.core

import android.content.Context
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

interface RipDpiProxyRuntime {
    suspend fun startProxy(preferences: RipDpiProxyPreferences): Int

    suspend fun awaitReady(timeoutMillis: Long = DEFAULT_PROXY_READY_TIMEOUT_MS)

    suspend fun stopProxy()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot

    suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot)
}

internal const val DEFAULT_PROXY_READY_TIMEOUT_MS = 5_000L

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
    constructor(
        @ApplicationContext context: Context,
    ) : RipDpiProxyBindings {
        companion object {
            init {
                RipDpiNativeLoader.ensureLoaded()
            }
        }

        init {
            RipDpiNativeLoader.ensureLoaded(context)
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
    private var handle = 0L
    private var readinessSignal: CompletableDeferred<Unit>? = null

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
                        logcat(LogPriority.ERROR) { "Proxy native session creation returned null handle" }
                        throw NativeError.SessionCreationFailed("proxy")
                    }
                    logcat(LogPriority.DEBUG) { "Proxy native session created: handle=$createdHandle" }
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
                    if (mutex.tryLock()) {
                        try {
                            if (this@RipDpiProxy.handle == capturedHandle && capturedHandle != 0L) {
                                nativeBindings.stop(capturedHandle)
                            }
                        } finally {
                            mutex.unlock()
                        }
                    }
                    // else: stopProxy() or the finally block already holds the mutex
                    // and will handle stop/destroy
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

        logcat(LogPriority.DEBUG) { "Awaiting proxy readiness (timeout=${timeoutMillis}ms)" }
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
            if (pollCount % 20 == 0L) {
                val elapsed = timeoutMillis - (deadline - System.currentTimeMillis())
                logcat(LogPriority.DEBUG) { "Proxy readiness: state=${telemetry.state} elapsed=${elapsed}ms" }
            }

            if (System.currentTimeMillis() >= deadline) {
                logcat(LogPriority.WARN) { "Proxy readiness timed out after ${timeoutMillis}ms" }
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
        val currentHandle = mutex.withLock { handle }
        if (currentHandle == 0L) return
        val snapshotJson = json.encodeToString(NativeNetworkSnapshot.serializer(), snapshot)
        withContext(Dispatchers.IO) { nativeBindings.updateNetworkSnapshot(currentHandle, snapshotJson) }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        val currentHandle = mutex.withLock { handle }
        if (currentHandle == 0L) return NativeRuntimeSnapshot.idle(source = "proxy")
        val payload = withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(currentHandle) }
        return payload
            ?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
            ?: NativeRuntimeSnapshot.idle(source = "proxy")
    }
}
