package com.poyka.ripdpi.core

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RipDpiProxy
    @Inject
    constructor() {
    companion object {
        init {
            System.loadLibrary("ripdpi")
        }
    }

    private val mutex = Mutex()
    private var handle = 0L

    suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        val handle =
            mutex.withLock {
                if (this.handle != 0L) {
                    throw IllegalStateException("Proxy is already running")
                }

                val createdHandle = jniCreate(preferences.toNativeConfigJson())
                if (createdHandle == 0L) {
                    throw IllegalStateException("Native proxy session was not created")
                }
                this.handle = createdHandle
                createdHandle
            }

        try {
            return withContext(Dispatchers.IO) { jniStart(handle) }
        } finally {
            mutex.withLock {
                if (this.handle == handle) {
                    try {
                        jniDestroy(handle)
                    } finally {
                        this.handle = 0L
                    }
                }
            }
        }
    }

    suspend fun stopProxy() {
        mutex.withLock {
            if (handle == 0L) {
                throw IllegalStateException("Proxy is not running")
            }

            jniStop(handle)
        }
    }

    private external fun jniCreate(configJson: String): Long

    private external fun jniStart(handle: Long): Int

    private external fun jniStop(handle: Long)

    private external fun jniDestroy(handle: Long)
}
