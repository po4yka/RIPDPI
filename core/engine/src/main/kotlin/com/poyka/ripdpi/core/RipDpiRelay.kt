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

interface RipDpiRelayRuntime {
    suspend fun start(config: RipDpiRelayConfig): Int

    suspend fun awaitReady(timeoutMillis: Long = DEFAULT_RELAY_READY_TIMEOUT_MS)

    suspend fun stop()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

internal const val DEFAULT_RELAY_READY_TIMEOUT_MS = 5_000L

interface RipDpiRelayBindings {
    fun create(configJson: String): Long

    fun start(handle: Long): Int

    fun stop(handle: Long)

    fun pollTelemetry(handle: Long): String?

    fun destroy(handle: Long)
}

class RipDpiRelayNativeLoader {
    companion object {
        init {
            System.loadLibrary("ripdpi-relay")
        }

        fun ensureLoaded() = Unit
    }
}

class RipDpiRelayNativeBindings
    @Inject
    constructor() : RipDpiRelayBindings {
        companion object {
            init {
                RipDpiRelayNativeLoader.ensureLoaded()
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

private val relayJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class RelayRuntimeNativeConfig(
    val enabled: Boolean,
    val kind: String,
    val profileId: String,
    val server: String,
    val serverPort: Int,
    val serverName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val chainEntryServer: String,
    val chainEntryPort: Int,
    val chainEntryServerName: String,
    val chainEntryPublicKey: String,
    val chainEntryShortId: String,
    val chainExitServer: String,
    val chainExitPort: Int,
    val chainExitServerName: String,
    val chainExitPublicKey: String,
    val chainExitShortId: String,
    val masqueUrl: String,
    val masqueUseHttp2Fallback: Boolean,
    val masqueCloudflareMode: Boolean,
    val localSocksHost: String,
    val localSocksPort: Int,
    val udpEnabled: Boolean,
    val tcpFallbackEnabled: Boolean,
)

class RipDpiRelay(
    private val nativeBindings: RipDpiRelayBindings,
) : RipDpiRelayRuntime {
    private companion object {
        private const val ReadyPollIntervalMs = 50L
    }

    private val mutex = Mutex()
    private var readinessSignal: CompletableDeferred<Unit>? = null

    @Volatile private var handle = 0L

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun start(config: RipDpiRelayConfig): Int {
        val startupSignal = CompletableDeferred<Unit>()
        val nativeConfig =
            RelayRuntimeNativeConfig(
                enabled = config.enabled,
                kind = config.kind,
                profileId = config.profileId,
                server = config.server,
                serverPort = config.serverPort,
                serverName = config.serverName,
                realityPublicKey = config.realityPublicKey,
                realityShortId = config.realityShortId,
                chainEntryServer = config.chainEntryServer,
                chainEntryPort = config.chainEntryPort,
                chainEntryServerName = config.chainEntryServerName,
                chainEntryPublicKey = config.chainEntryPublicKey,
                chainEntryShortId = config.chainEntryShortId,
                chainExitServer = config.chainExitServer,
                chainExitPort = config.chainExitPort,
                chainExitServerName = config.chainExitServerName,
                chainExitPublicKey = config.chainExitPublicKey,
                chainExitShortId = config.chainExitShortId,
                masqueUrl = config.masqueUrl,
                masqueUseHttp2Fallback = config.masqueUseHttp2Fallback,
                masqueCloudflareMode = config.masqueCloudflareMode,
                localSocksHost = config.localSocksHost,
                localSocksPort = config.localSocksPort,
                udpEnabled = config.udpEnabled,
                tcpFallbackEnabled = config.tcpFallbackEnabled,
            )
        val createdHandle =
            mutex.withLock {
                if (handle != 0L) {
                    throw NativeError.AlreadyRunning("relay")
                }
                readinessSignal = startupSignal
                try {
                    val newHandle =
                        withContext(Dispatchers.IO) {
                            nativeBindings.create(relayJson.encodeToString(nativeConfig))
                        }
                    if (newHandle == 0L) {
                        throw NativeError.SessionCreationFailed("relay")
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
                    startupSignal.completeExceptionally(IllegalStateException("Relay exited before becoming ready"))
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
            } ?: throw NativeError.NotRunning("relay")
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
                throw IllegalStateException("Relay readiness timed out")
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
        val activeHandle = handle
        if (activeHandle == 0L) {
            return NativeRuntimeSnapshot.idle(source = "relay")
        }
        val telemetryJson =
            withContext(Dispatchers.IO) {
                nativeBindings.pollTelemetry(activeHandle)
            } ?: return NativeRuntimeSnapshot.idle(source = "relay")
        return relayJson.decodeFromString(NativeRuntimeSnapshot.serializer(), telemetryJson)
    }
}
