package com.poyka.ripdpi.core

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface RipDpiProxyRuntime {
    suspend fun startProxy(preferences: RipDpiProxyPreferences): Int

    suspend fun stopProxy()
}

interface RipDpiProxyBindings {
    fun create(configJson: String): Long

    fun start(handle: Long): Int

    fun stop(handle: Long)

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

        override fun destroy(handle: Long) {
            jniDestroy(handle)
        }

        private external fun jniCreate(configJson: String): Long

        private external fun jniStart(handle: Long): Int

        private external fun jniStop(handle: Long)

        private external fun jniDestroy(handle: Long)
    }

class RipDpiProxy(
    private val nativeBindings: RipDpiProxyBindings,
) : RipDpiProxyRuntime {
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
}
