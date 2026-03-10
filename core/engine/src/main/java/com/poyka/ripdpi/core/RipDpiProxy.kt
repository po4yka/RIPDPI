package com.poyka.ripdpi.core

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

interface RipDpiProxyRuntime {
    suspend fun startProxy(preferences: RipDpiProxyPreferences): Int

    suspend fun stopProxy()

    suspend fun pollTelemetry(): NativeRuntimeSnapshot
}

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
                System.loadLibrary("ripdpi")
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
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        val handle =
            mutex.withLock {
                if (this.handle != 0L) {
                    throw IllegalStateException("Proxy is already running")
                }

                val createdHandle = nativeBindings.create(preferences.toNativeConfigJson())
                if (createdHandle == 0L) {
                    throw IllegalStateException("Native proxy session was not created")
                }
                this.handle = createdHandle
                createdHandle
            }

        try {
            return withContext(Dispatchers.IO) { nativeBindings.start(handle) }
        } finally {
            mutex.withLock {
                if (this.handle == handle) {
                    try {
                        nativeBindings.destroy(handle)
                    } finally {
                        this.handle = 0L
                    }
                }
            }
        }
    }

    override suspend fun stopProxy() {
        mutex.withLock {
            if (handle == 0L) {
                throw IllegalStateException("Proxy is not running")
            }

            nativeBindings.stop(handle)
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        val currentHandle =
            mutex.withLock {
                handle
            }
        if (currentHandle == 0L) {
            return NativeRuntimeSnapshot.idle(source = "proxy")
        }
        val payload = withContext(Dispatchers.IO) { nativeBindings.pollTelemetry(currentHandle) }
        return payload
            ?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(NativeRuntimeSnapshot.serializer(), it) }
            ?: NativeRuntimeSnapshot.idle(source = "proxy")
    }
}
