package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal class RelayPreflightUnavailableException : IllegalStateException("Relay preflight is unavailable")

internal class RelayPreflightPreemptedException :
    CancellationException("Relay preflight was preempted by service start")

/** Gives service start priority over an admitted, process-local transient runtime. */
@Singleton
class ServiceRuntimeAdmissionInterlock
    @Inject
    constructor() {
        private val gate = Mutex()
        private var activePreflight: Job? = null
        private var serviceStartInProgress: Boolean = false

        suspend fun <T> runPreflight(block: suspend () -> T): T {
            val job = currentCoroutineContext()[Job] ?: error("Relay preflight requires a Job")
            gate.withLock {
                if (serviceStartInProgress || activePreflight != null) {
                    throw RelayPreflightUnavailableException()
                }
                activePreflight = job
            }
            return try {
                block()
            } finally {
                gate.withLock {
                    if (activePreflight === job) activePreflight = null
                }
            }
        }

        suspend fun cancelAndAwaitPreflight() {
            val job = gate.withLock { activePreflight }
            job?.cancel(RelayPreflightPreemptedException())
            job?.join()
        }

        suspend fun <T> runServiceStart(block: suspend () -> T): T {
            val job =
                gate.withLock {
                    check(!serviceStartInProgress) { "Service start is already in progress" }
                    serviceStartInProgress = true
                    activePreflight
                }
            return try {
                job?.cancel(RelayPreflightPreemptedException())
                job?.join()
                block()
            } finally {
                gate.withLock { serviceStartInProgress = false }
            }
        }
    }

internal typealias RelayPreflightInterlock = ServiceRuntimeAdmissionInterlock
