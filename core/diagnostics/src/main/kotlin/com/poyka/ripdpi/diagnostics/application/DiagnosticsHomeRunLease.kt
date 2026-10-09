@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes Home ownership with scan admission and profile changes. */
@Singleton
class DiagnosticsHomeRunLease
    @Inject
    constructor() {
        private val admissionMutex = Mutex()
        private val ownerId = MutableStateFlow<String?>(null)

        fun isActive(): Boolean = ownerId.value != null

        internal fun isOwnedBy(runId: String): Boolean = ownerId.value == runId

        internal fun permits(owner: String?): Boolean = ownerId.value == owner

        internal suspend fun acquire(
            runId: String,
            canAcquire: () -> Boolean = { true },
        ): Boolean =
            admissionMutex.withLock {
                if (ownerId.value != null || !canAcquire()) {
                    false
                } else {
                    ownerId.value = runId
                    true
                }
            }

        internal suspend fun release(runId: String) {
            admissionMutex.withLock {
                if (ownerId.value == runId) ownerId.value = null
            }
        }

        internal suspend fun <T> withAdmission(block: suspend () -> T): T = admissionMutex.withLock { block() }
    }
