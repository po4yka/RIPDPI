package com.poyka.ripdpi.core

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    constructor() : NetworkDiagnosticsBridge {
        companion object {
            init {
                System.loadLibrary("ripdpi")
            }
        }

        private val mutex = Mutex()
        private var handle = 0L

        private suspend fun ensureHandle(): Long =
            mutex.withLock {
                if (handle == 0L) {
                    handle = jniCreate()
                }
                handle
            }

        override suspend fun startScan(
            requestJson: String,
            sessionId: String,
        ) {
            val currentHandle = ensureHandle()
            withContext(Dispatchers.IO) { jniStartScan(currentHandle, requestJson, sessionId) }
        }

        override suspend fun cancelScan() {
            mutex.withLock {
                if (handle != 0L) {
                    jniCancelScan(handle)
                }
            }
        }

        override suspend fun pollProgressJson(): String? {
            val currentHandle = ensureHandle()
            return withContext(Dispatchers.IO) { jniPollProgress(currentHandle) }
        }

        override suspend fun takeReportJson(): String? {
            val currentHandle = ensureHandle()
            return withContext(Dispatchers.IO) { jniTakeReport(currentHandle) }
        }

        override suspend fun pollPassiveEventsJson(): String? {
            val currentHandle = ensureHandle()
            return withContext(Dispatchers.IO) { jniPollPassiveEvents(currentHandle) }
        }

        override suspend fun destroy() {
            mutex.withLock {
                if (handle != 0L) {
                    try {
                        jniDestroy(handle)
                    } finally {
                        handle = 0L
                    }
                }
            }
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
