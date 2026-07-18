package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles a durable running-session intent when Android recreates the app UI
 * without re-delivering the VPN service's `START_STICKY` intent.
 *
 * The activity-visible caller supplies foreground-service start eligibility. A
 * sticky service restart remains the primary recovery path; a non-halted
 * process-local state therefore makes this fallback a no-op.
 */
@Singleton
class ProcessDeathResumeCoordinator
    @Inject
    constructor(
        private val bootSessionStateStore: BootSessionStateStore,
        private val profileGuard: BootResumeProfileGuard,
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
        private val serviceIntentArbiter: ServiceIntentArbiter,
    ) {
        private val resumeMutex = Mutex()
        private var completed = false
        private val log = Logger.withTag("ProcessDeathResume")

        suspend fun resumeIfNeeded() {
            resumeMutex.withLock {
                if (!completed) reconcile()
            }
        }

        private suspend fun reconcile() {
            val pointer = bootSessionStateStore.lastSession()
            if (pointer == null || !isCurrent(pointer)) {
                completed = true
            } else {
                val resumable = profileGuard.isResumable(pointer.profileId)
                when {
                    !resumable -> {
                        completed = true
                        bootSessionStateStore.clear()
                        log.w { "Skipping process-death resume because the recorded profile is stale" }
                    }

                    else -> {
                        arbitrateStart(pointer)
                    }
                }
            }
        }

        private fun arbitrateStart(pointer: BootSessionPointer) {
            serviceIntentArbiter.serialize {
                if (isCurrent(pointer)) {
                    start(pointer)
                } else {
                    completed = true
                    log.i { "Skipping superseded process-death resume" }
                }
            }
        }

        private fun start(pointer: BootSessionPointer) {
            val result = serviceController.start(pointer.mode)
            if (result is ServiceStartResult.Accepted) {
                completed = true
            }
            reconnectingResumeMode(result, serviceStateStore.status.value.first)
                ?.let { mode -> serviceStateStore.setStatus(AppStatus.Reconnecting, mode) }
            log.i { "process-death resume: starting ${pointer.mode} -> $result" }
        }

        private fun isCurrent(pointer: BootSessionPointer): Boolean =
            serviceStateStore.status.value.first == AppStatus.Halted &&
                bootSessionStateStore.wasRunningAtUpdate() &&
                bootSessionStateStore.lastSession() == pointer
    }
