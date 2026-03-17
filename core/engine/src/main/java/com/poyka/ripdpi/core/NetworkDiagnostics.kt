package com.poyka.ripdpi.core

import android.content.Context
import com.poyka.ripdpi.data.NativeError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface NetworkDiagnosticsBindings {
    fun create(): Long

    fun startScan(
        handle: Long,
        requestJson: String,
        sessionId: String,
    )

    fun cancelScan(handle: Long)

    fun pollProgress(handle: Long): String?

    fun takeReport(handle: Long): String?

    fun pollPassiveEvents(handle: Long): String?

    fun destroy(handle: Long)
}

class NetworkDiagnosticsNativeBindings
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NetworkDiagnosticsBindings {
        companion object {
            init {
                RipDpiNativeLoader.ensureLoaded()
            }
        }

        init {
            RipDpiNativeLoader.ensureLoaded(context)
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

class NetworkDiagnostics
    @Inject
    constructor(
        private val nativeBindings: NetworkDiagnosticsBindings,
    ) : NetworkDiagnosticsBridge {
        private val mutex = Mutex()
        private var handle = 0L

        private fun ensureHandleLocked(): Long {
            if (handle == 0L) {
                val createdHandle = nativeBindings.create()
                if (createdHandle == 0L) {
                    throw NativeError.SessionCreationFailed("diagnostics")
                }
                handle = createdHandle
            }
            return handle
        }

        override suspend fun startScan(
            requestJson: String,
            sessionId: String,
        ) {
            mutex.withLock {
                val h = ensureHandleLocked()
                withContext(Dispatchers.IO) { nativeBindings.startScan(h, requestJson, sessionId) }
            }
        }

        override suspend fun cancelScan() {
            mutex.withLock {
                if (handle != 0L) {
                    nativeBindings.cancelScan(handle)
                }
            }
        }

        override suspend fun pollProgressJson(): String? =
            mutex.withLock {
                val h = ensureHandleLocked()
                withContext(Dispatchers.IO) { nativeBindings.pollProgress(h) }
            }

        override suspend fun takeReportJson(): String? =
            mutex.withLock {
                val h = ensureHandleLocked()
                withContext(Dispatchers.IO) { nativeBindings.takeReport(h) }
            }

        override suspend fun pollPassiveEventsJson(): String? =
            mutex.withLock {
                val h = ensureHandleLocked()
                withContext(Dispatchers.IO) { nativeBindings.pollPassiveEvents(h) }
            }

        override suspend fun destroy() {
            mutex.withLock {
                if (handle != 0L) {
                    try {
                        nativeBindings.destroy(handle)
                    } finally {
                        handle = 0L
                    }
                }
            }
        }
    }
