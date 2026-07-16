package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.service.runtime.RuntimeModeProjectionStore
import com.poyka.ripdpi.service.runtime.control.RuntimeControlCommand
import com.poyka.ripdpi.service.runtime.control.RuntimeControlPlane
import com.poyka.ripdpi.service.runtime.control.RuntimeControlReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service-side bridge to the diagnostics scan engine — sequences a raw-path
 * scan against service state: stops a running service before the scan and
 * auto-resumes it afterward when the `diagnosticsAutoResumeAfterRawScan`
 * setting is on. See `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`.
 *
 * The initial pause is issued through [RuntimeControlPlane]. Resume and
 * reconciliation intents use [ServiceController] inside the same generation
 * lock as explicit user intent, so their ownership check and dispatch are
 * atomic with respect to a newer Start or accepted Stop.
 *
 * For the duration of a scan the coordinator publishes the diagnostics-scan
 * layer into [RuntimeModeProjectionStore] and logs the runtime mode the scan
 * runs against. That is read-only observability — it changes neither the scan
 * nor any service start/stop transition.
 */
@Singleton
internal class DefaultDiagnosticsRuntimeCoordinator
    @Inject
    constructor(
        private val runtimeControlPlane: RuntimeControlPlane,
        private val runtimeModeProjectionStore: RuntimeModeProjectionStore,
        private val serviceStateStore: ServiceStateStore,
        private val appSettingsRepository: AppSettingsRepository,
        private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
        private val serviceController: ServiceController,
    ) : DiagnosticsRuntimeCoordinator {
        private var waitAttempts: Int = 50
        private var waitDelayMs: Long = 200L

        internal constructor(
            runtimeControlPlane: RuntimeControlPlane,
            runtimeModeProjectionStore: RuntimeModeProjectionStore,
            serviceStateStore: ServiceStateStore,
            appSettingsRepository: AppSettingsRepository,
            runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
            serviceController: ServiceController,
            waitAttempts: Int,
            waitDelayMs: Long,
        ) : this(
            runtimeControlPlane,
            runtimeModeProjectionStore,
            serviceStateStore,
            appSettingsRepository,
            runtimeResumeIntentTracker,
            serviceController,
        ) {
            this.waitAttempts = waitAttempts
            this.waitDelayMs = waitDelayMs
        }

        override suspend fun runRawPathScan(block: suspend () -> Unit) {
            val resumeLease = runtimeResumeIntentTracker.captureResumeLease()
            reportingScanActivity("Raw-path scan") {
                val (status, mode) = serviceStateStore.status.value
                val diagnostics = appSettingsRepository.snapshot().toSettingsSections().diagnostics
                val shouldResume = status == AppStatus.Running && diagnostics.diagnosticsAutoResumeAfterRawScan

                if (status == AppStatus.Running) {
                    runtimeControlPlane.execute(
                        RuntimeControlCommand.StopRuntime(RuntimeControlReason.DiagnosticsRawPathScan),
                    )
                    waitForStatus(AppStatus.Halted)
                }

                try {
                    block()
                } finally {
                    if (shouldResume) {
                        resumeRuntimeIfOwned(mode, resumeLease)
                    }
                }
            }
        }

        override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) {
            val resumeLease = runtimeResumeIntentTracker.captureResumeLease()
            reportingScanActivity("Automatic raw-path scan") {
                val (status, mode) = serviceStateStore.status.value
                val shouldResume = status == AppStatus.Running

                if (status == AppStatus.Running) {
                    runtimeControlPlane.execute(
                        RuntimeControlCommand.StopRuntime(RuntimeControlReason.DiagnosticsRawPathScan),
                    )
                    waitForStatus(AppStatus.Halted)
                }

                try {
                    block()
                } finally {
                    if (shouldResume) {
                        resumeRuntimeIfOwned(mode, resumeLease)
                    }
                }
            }
        }

        private suspend fun resumeRuntimeIfOwned(
            mode: Mode,
            resumeLease: ResumeLease,
        ) {
            val startResult =
                runtimeResumeIntentTracker.runIfOwned(resumeLease) {
                    serviceController.startForDiagnostics(mode)
                } ?: return
            if (resumeRuntime(startResult)) {
                waitForResumeResolution(resumeLease)
            }
        }

        private fun resumeRuntime(startResult: ServiceStartResult): Boolean =
            when (startResult) {
                is ServiceStartResult.Accepted -> {
                    true
                }

                is ServiceStartResult.Rejected -> {
                    Logger.w { "Diagnostics runtime resume rejected for ${startResult.mode}: ${startResult.reason}" }
                    false
                }
            }

        /**
         * Run a raw-path scan while keeping [RuntimeModeProjectionStore] in step:
         * publish the diagnostics-scan layer as active for the scan's duration,
         * and log the runtime mode the scan is operating against.
         *
         * This is read-only observability wrapped around the existing scan — it
         * changes neither the scan nor any service start/stop transition.
         */
        private suspend fun reportingScanActivity(
            label: String,
            scan: suspend () -> Unit,
        ) {
            runtimeModeProjectionStore.markDiagnosticsScanActive(true)
            try {
                val projection = runtimeModeProjectionStore.projection.first()
                Logger.d { "$label starting; runtime mode = $projection" }
                scan()
            } finally {
                runtimeModeProjectionStore.markDiagnosticsScanActive(false)
            }
        }

        private suspend fun waitForStatus(target: AppStatus) {
            repeat(waitAttempts) {
                if (serviceStateStore.status.value.first == target) {
                    return
                }
                delay(waitDelayMs)
            }
            error("Timed out waiting for service status $target")
        }

        private suspend fun waitForResumeResolution(resumeLease: ResumeLease) {
            var waitState = ResumeWaitState()
            for (attempt in 0 until waitAttempts) {
                waitState = evaluateResumeAttempt(resumeLease, waitState)
                if (waitState.resolved) {
                    break
                }
                if (attempt < waitAttempts - 1) {
                    delay(waitDelayMs)
                }
            }
            if (waitState.resolved) {
                return
            }
            when (runtimeResumeIntentTracker.ownership(resumeLease)) {
                ResumeLeaseOwnership.Owned -> {
                    error("Timed out waiting for service status ${AppStatus.Running}")
                }

                is ResumeLeaseOwnership.Superseded -> {
                    error("Timed out waiting for diagnostics resume or newer user intent")
                }
            }
        }

        private fun evaluateResumeAttempt(
            resumeLease: ResumeLease,
            waitState: ResumeWaitState,
        ): ResumeWaitState {
            val status = serviceStateStore.status.value.first
            return when (val ownership = runtimeResumeIntentTracker.ownership(resumeLease)) {
                ResumeLeaseOwnership.Owned -> waitState.copy(resolved = status == AppStatus.Running)
                is ResumeLeaseOwnership.Superseded -> evaluateSupersededIntent(ownership, waitState)
            }
        }

        private fun evaluateSupersededIntent(
            ownership: ResumeLeaseOwnership.Superseded,
            waitState: ResumeWaitState,
        ): ResumeWaitState =
            when (ownership.intent) {
                UserRuntimeIntent.Running -> waitState.copy(resolved = true)
                UserRuntimeIntent.Stopped -> reconcileStoppedIntent(ownership, waitState)
                UserRuntimeIntent.Unknown -> waitState.copy(resolved = false)
            }

        private fun reconcileStoppedIntent(
            ownership: ResumeLeaseOwnership.Superseded,
            waitState: ResumeWaitState,
        ): ResumeWaitState {
            val stopped =
                waitState.compensatedGeneration == ownership.generation ||
                    runtimeResumeIntentTracker.runCompensatingStopIfCurrent(ownership) {
                        serviceController.stopForDiagnosticsCompensation()
                    }
            return ResumeWaitState(
                resolved = serviceStateStore.status.value.first == AppStatus.Halted,
                compensatedGeneration = ownership.generation.takeIf { stopped },
            )
        }

        private data class ResumeWaitState(
            val resolved: Boolean = false,
            val compensatedGeneration: Long? = null,
        )
    }
