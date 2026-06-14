package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot worker that resumes the previously-active RIPDPI service after a
 * boot or app update. Driven by [BootReceiver] so the receiver's `onReceive`
 * stays short (no service start on the broadcast thread).
 *
 * Reads the device-protected [BootSessionStateStore] pointer, validates it via
 * [BootResumeProfileGuard], applies [decideBootResume], and — when the decision
 * is [BootResumeDecision.Resume] — asks the process-wide [ServiceController] to
 * start the recorded mode. A stale pointer (profile deleted) is cleared so
 * subsequent boots skip silently; the `MY_PACKAGE_REPLACED` "was running" flag
 * is read-once and cleared on every package-replaced run.
 */

@HiltWorker
class BootResumeWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val bootSessionStateStore: BootSessionStateStore,
        private val profileGuard: BootResumeProfileGuard,
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
    ) : CoroutineWorker(appContext, workerParams) {
        private val log = Logger.withTag("BootResumeWorker")

        override suspend fun doWork(): Result {
            val action = inputData.getString(KeyAction)
            val pointer = bootSessionStateStore.lastSession()
            val wasRunningAtUpdate = bootSessionStateStore.wasRunningAtUpdate()
            val resumable = pointer != null && profileGuard.isResumable(pointer.profileId)

            // MY_PACKAGE_REPLACED gate is read-once: clear it on every package-replaced run.
            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                bootSessionStateStore.setWasRunningAtUpdate(false)
            }
            // Drop a stale pointer so future boots skip without re-validating.
            if (pointer != null && !resumable) {
                bootSessionStateStore.clear()
            }

            return when (val decision = decideBootResume(action, pointer, resumable, wasRunningAtUpdate)) {
                is BootResumeDecision.Resume -> {
                    val result = serviceController.start(decision.mode)
                    // Publish a transient Reconnecting status so the UI/widget shows the
                    // bring-up window instead of Halted while the service races to
                    // Running. Decision is a pure, separately-tested helper; it is
                    // overwritten by Running on connect / Halted on failure.
                    reconnectingResumeMode(result, serviceStateStore.status.value.first)
                        ?.let { mode -> serviceStateStore.setStatus(AppStatus.Reconnecting, mode) }
                    log.i { "boot resume ($action): starting ${decision.mode} -> $result" }
                    Result.success()
                }

                is BootResumeDecision.Skip -> {
                    log.d { "boot resume ($action): skipped (${decision.reason})" }
                    Result.success()
                }
            }
        }

        companion object {
            const val UNIQUE_WORK_NAME = "ripdpi.boot.resume"
            const val KeyAction = "boot_action"

            fun enqueue(
                context: Context,
                action: String,
            ) {
                val request =
                    OneTimeWorkRequestBuilder<BootResumeWorker>()
                        .setInputData(workDataOf(KeyAction to action))
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

/**
 * Mode to publish [AppStatus.Reconnecting] for after a boot-resume start, or null to
 * leave the status untouched. Pure, so the headline boot-resume write is unit-tested
 * without a WorkManager harness (mirrors [decideBootResume]). Reconnecting is published
 * only when the start was [ServiceStartResult.Accepted] AND the store is still
 * [AppStatus.Halted]: a Rejected start must not leave a stuck Reconnecting, and an
 * already-active session (e.g. an automation-intercepted Accepted that dispatched no
 * foreground service) must not be demoted.
 */
internal fun reconnectingResumeMode(
    result: ServiceStartResult,
    currentStatus: AppStatus,
): Mode? =
    (result as? ServiceStartResult.Accepted)
        ?.takeIf { currentStatus == AppStatus.Halted }
        ?.mode
