package com.poyka.ripdpi.core

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    constructor() : NetworkDiagnosticsBindings {
        companion object {
            init {
                System.loadLibrary("ripdpi")
            }
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

        private suspend fun ensureHandle(): Long =
            mutex.withLock {
                if (handle == 0L) {
                    val createdHandle = nativeBindings.create()
                    if (createdHandle == 0L) {
                        throw IllegalStateException("Native diagnostics session was not created")
                    }
                    handle = createdHandle
                }
                handle
            }

        override suspend fun startScan(
            requestJson: String,
            sessionId: String,
    ) {
        val currentHandle = ensureHandle()
        withContext(Dispatchers.IO) { nativeBindings.startScan(currentHandle, requestJson, sessionId) }
    }

    override suspend fun cancelScan() {
        mutex.withLock {
            if (handle != 0L) {
                nativeBindings.cancelScan(handle)
            }
        }
    }

    override suspend fun pollProgressJson(): String? {
        val currentHandle = ensureHandle()
        return withContext(Dispatchers.IO) { nativeBindings.pollProgress(currentHandle) }
    }

    override suspend fun takeReportJson(): String? {
        val currentHandle = ensureHandle()
        return withContext(Dispatchers.IO) { nativeBindings.takeReport(currentHandle) }
    }

    override suspend fun pollPassiveEventsJson(): String? {
        val currentHandle = ensureHandle()
        return withContext(Dispatchers.IO) { nativeBindings.pollPassiveEvents(currentHandle) }
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
