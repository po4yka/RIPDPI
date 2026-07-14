package com.poyka.ripdpi.services

import android.content.Intent
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resumes a recorded session while [BootReceiver] still owns the boot broadcast.
 * Local validation runs before [ServiceController.start], which must remain inside
 * that lifetime so Android's boot foreground-service exemption still applies.
 */
@Singleton
class BootResumeCoordinator
    @Inject
    constructor(
        private val bootSessionStateStore: BootSessionStateStore,
        private val profileGuard: BootResumeProfileGuard,
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
    ) {
        private val log = Logger.withTag("BootResumeCoordinator")

        suspend fun resume(action: String) {
            val pointer = bootSessionStateStore.lastSession()
            val wasRunningAtUpdate = bootSessionStateStore.wasRunningAtUpdate()
            val resumable = pointer != null && profileGuard.isResumable(pointer.profileId)

            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                bootSessionStateStore.setWasRunningAtUpdate(false)
            }
            if (pointer != null && !resumable) {
                bootSessionStateStore.clear()
            }

            when (val decision = decideBootResume(action, pointer, resumable, wasRunningAtUpdate)) {
                is BootResumeDecision.Resume -> {
                    val result = serviceController.start(decision.mode)
                    reconnectingResumeMode(result, serviceStateStore.status.value.first)
                        ?.let { mode -> serviceStateStore.setStatus(AppStatus.Reconnecting, mode) }
                    log.i { "boot resume ($action): starting ${decision.mode} -> $result" }
                }

                is BootResumeDecision.Skip -> {
                    log.d { "boot resume ($action): skipped (${decision.reason})" }
                }
            }
        }
    }

/**
 * Mode to publish [AppStatus.Reconnecting] for after a boot-resume start, or null to
 * leave the status untouched. Reconnecting is published only for an accepted start
 * from a halted baseline.
 */
internal fun reconnectingResumeMode(
    result: ServiceStartResult,
    currentStatus: AppStatus,
): Mode? =
    (result as? ServiceStartResult.Accepted)
        ?.takeIf { currentStatus == AppStatus.Halted }
        ?.mode
