package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

interface RipDpiWarpRuntime {
    suspend fun start(config: RipDpiWarpConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = defaultWarpReadyTimeoutMs)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val defaultWarpReadyTimeoutMs = 5_000L

interface RipDpiWarpBindings {
    fun create(configJson: String): Long

    fun start(handle: Long): Int

    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    fun destroy(handle: Long)
}

object RipDpiWarpNativeLoader {
    init {
        System.loadLibrary("ripdpi-warp")
    }

    fun ensureLoaded() = Unit
}

class RipDpiWarpNativeBindings
    @Inject
    constructor() : RipDpiWarpBindings {
        companion object {
            init {
                RipDpiWarpNativeLoader.ensureLoaded()
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

private val warpJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class WarpRuntimeNativeConfig(
    val enabled: Boolean,
    val routeMode: String,
    val routeHosts: String,
    val builtInRulesEnabled: Boolean,
    val endpointSelectionMode: String,
    val manualEndpoint: RipDpiWarpManualEndpointConfig,
    val scannerEnabled: Boolean,
    val scannerParallelism: Int,
    val scannerMaxRttMs: Int,
    val amnezia: RipDpiWarpAmneziaConfig,
    val localSocksHost: String,
    val localSocksPort: Int,
)

class RipDpiWarp(
    private val nativeBindings: RipDpiWarpBindings,
) : RipDpiWarpRuntime {
    private companion object {
        private const val ReadyPollIntervalMs = 50L
    }

    private val mutex = Mutex()
    private var readinessSignal: CompletableDeferred<Unit>? = null

    @Volatile private var handle = 0L

    @Suppress("TooGenericExceptionCaught")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun start(config: RipDpiWarpConfig): Int {
        val startupSignal = CompletableDeferred<Unit>()
        val nativeConfig =
            WarpRuntimeNativeConfig(
                enabled = config.enabled,
                routeMode = config.routeMode,
                routeHosts = config.routeHosts,
                builtInRulesEnabled = config.builtInRulesEnabled,
                endpointSelectionMode = config.endpointSelectionMode,
                manualEndpoint = config.manualEndpoint,
                scannerEnabled = config.scannerEnabled,
                scannerParallelism = config.scannerParallelism,
                scannerMaxRttMs = config.scannerMaxRttMs,
                amnezia = config.amnezia,
                localSocksHost = config.localSocksHost,
                localSocksPort = config.localSocksPort,
            )
        val createdHandle =
            mutex.withLock {
                if (handle != 0L) {
                    throw NativeError.AlreadyRunning("WARP")
                }
                readinessSignal = startupSignal
                try {
                    val newHandle =
                        withContext(Dispatchers.IO) {
                            nativeBindings.create(warpJson.encodeToString(nativeConfig))
                        }
                    if (newHandle == 0L) {
                        throw NativeError.SessionCreationFailed("warp")
                    }
                    handle = newHandle
                    newHandle
                } catch (error: Exception) {
                    readinessSignal = null
                    startupSignal.completeExceptionally(error)
                    throw error
                }
            }

        yield()

        try {
            val completionHandle =
                coroutineContext[Job]!!.invokeOnCompletion {
                    try {
                        if (handle == createdHandle && createdHandle != 0L) {
                            nativeBindings.stop(createdHandle)
                        }
                    } catch (_: IllegalStateException) {
                    }
                }
            return try {
                withContext(Dispatchers.IO) { nativeBindings.start(createdHandle) }
            } finally {
                completionHandle.dispose()
            }
        } catch (error: Exception) {
            startupSignal.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock {
                if (handle == createdHandle) {
                    try {
                        nativeBindings.destroy(createdHandle)
                    } finally {
                        handle = 0L
                    }
                }
                if (!startupSignal.isCompleted) {
                    startupSignal.completeExceptionally(IllegalStateException("WARP exited before becoming ready"))
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
            } ?: throw NativeError.NotRunning("WARP")
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            if (startupSignal.isCompleted) {
                startupSignal.await()
                return
            }
            if (pollTelemetry().state == "running") {
                startupSignal.complete(Unit)
                startupSignal.await()
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                error("WARP readiness timed out")
            }
            delay(ReadyPollIntervalMs)
        }
    }

    override suspend fun stop() {
        val activeHandle =
            mutex.withLock {
                val currentHandle = handle
                handle = 0L
                readinessSignal = null
                currentHandle
            }
        if (activeHandle != 0L) {
            withContext(Dispatchers.IO) {
                runCatching { nativeBindings.stop(activeHandle) }
                nativeBindings.destroy(activeHandle)
            }
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        if (handle == 0L) return NativeRuntimeSnapshot.idle(source = "warp")
        val telemetryJson = withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(handle) }
        return telemetryJson
            ?.takeIf { it.isNotBlank() }
            ?.let { warpJson.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
            ?: NativeRuntimeSnapshot.idle(source = "warp")
    }
}
