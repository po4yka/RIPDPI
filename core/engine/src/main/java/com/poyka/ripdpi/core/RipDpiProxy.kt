package com.poyka.ripdpi.core

import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

interface RipDpiProxyRuntime {
    suspend fun startProxy(preferences: RipDpiProxyPreferences): Int

    suspend fun awaitReady(timeoutMillis: Long = DEFAULT_PROXY_READY_TIMEOUT_MS)

    suspend fun stopProxy()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val DEFAULT_PROXY_READY_TIMEOUT_MS = 5_000L

interface RipDpiProxyBindings {
    fun create(configJson: String): Long

    fun start(handle: Long): Int

    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    fun destroy(handle: Long)
}

class RipDpiProxyNativeBindings
    @Inject
    constructor() : RipDpiProxyBindings {
        companion object {
            init {
                RipDpiNativeLoader.ensureLoaded()
            }
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

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(handle: Long): Int

        private external fun jniStop(handle: Long)

        private external fun jniPollTelemetry(handle: Long): String?

        private external fun jniDestroy(handle: Long)
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
                    val createdHandle = nativeBindings.create(preferences.toNativeConfigJson())
                    if (createdHandle == 0L) {
                        throw NativeError.SessionCreationFailed("proxy")
                    }
                    this.handle = createdHandle
                    createdHandle
                } catch (error: Exception) {
                    readinessSignal = null
                    startupSignal.completeExceptionally(error)
                    throw error
                }
            }

        try {
            val completionHandle = coroutineContext[Job]!!.invokeOnCompletion {
                nativeBindings.stop(handle)
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
                if (readinessSignal === startupSignal) {
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

        try {
            withTimeout(timeoutMillis) {
                while (true) {
                    if (startupSignal.isCompleted) {
                        startupSignal.await()
                        return@withTimeout
                    }

                    if (pollTelemetry().state == "running") {
                        startupSignal.complete(Unit)
                        startupSignal.await()
                        return@withTimeout
                    }

                    delay(READY_POLL_INTERVAL_MS)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("Timed out waiting for proxy readiness", error)
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

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        val payload =
            mutex.withLock {
                if (handle == 0L) {
                    return NativeRuntimeSnapshot.idle(source = "proxy")
                }
                withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(handle) }
            }
        return payload
            ?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
            ?: NativeRuntimeSnapshot.idle(source = "proxy")
    }
}
