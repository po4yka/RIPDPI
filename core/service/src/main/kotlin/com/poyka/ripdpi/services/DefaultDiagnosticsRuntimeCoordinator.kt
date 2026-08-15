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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        private val rawPathWindowMutex = Mutex()
        private var rawPathWindow: RawPathWindow? = null

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
            val diagnostics = appSettingsRepository.snapshot().toSettingsSections().diagnostics
            runInRawPathWindow(
                label = "Raw-path scan",
                resumeIfRuntimeWasRunning = diagnostics.diagnosticsAutoResumeAfterRawScan,
                block = block,
            )
        }

        override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) {
            runInRawPathWindow(
                label = "Automatic raw-path scan",
                resumeIfRuntimeWasRunning = true,
                block = block,
            )
        }

        private suspend fun runInRawPathWindow(
            label: String,
            resumeIfRuntimeWasRunning: Boolean,
            block: suspend () -> Unit,
        ) {
            enterRawPathWindow(label, resumeIfRuntimeWasRunning)
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    leaveRawPathWindow()
                }
            }
        }

        private suspend fun enterRawPathWindow(
            label: String,
            resumeIfRuntimeWasRunning: Boolean,
        ) {
            rawPathWindowMutex.withLock {
                rawPathWindow?.let { activeWindow ->
                    activeWindow.participantCount += 1
                    activeWindow.shouldResume =
                        activeWindow.shouldResume ||
                        (activeWindow.runtimeWasRunning && resumeIfRuntimeWasRunning)
                    Logger.d { "$label joined active raw-path window" }
                    return@withLock
                }

                val resumeLease = runtimeResumeIntentTracker.captureResumeLease()
                val (status, mode) = serviceStateStore.status.value
                val runtimeWasRunning = status == AppStatus.Running
                rawPathWindow =
                    RawPathWindow(
                        mode = mode,
                        resumeLease = resumeLease,
                        runtimeWasRunning = runtimeWasRunning,
                        shouldResume = runtimeWasRunning && resumeIfRuntimeWasRunning,
                    )
                runtimeModeProjectionStore.markDiagnosticsScanActive(true)
                var windowPrepared = false
                try {
                    val projection = runtimeModeProjectionStore.projection.first()
                    Logger.d { "$label starting; runtime mode = $projection" }
                    if (runtimeWasRunning) {
                        runtimeControlPlane.execute(
                            RuntimeControlCommand.StopRuntime(RuntimeControlReason.DiagnosticsRawPathScan),
                        )
                        waitForStatus(AppStatus.Halted)
                    }
                    windowPrepared = true
                } finally {
                    if (!windowPrepared) {
                        rawPathWindow = null
                        runCatching { runtimeModeProjectionStore.markDiagnosticsScanActive(false) }
                    }
                }
            }
        }

        private suspend fun leaveRawPathWindow() {
            rawPathWindowMutex.withLock {
                val activeWindow = checkNotNull(rawPathWindow) { "Raw-path window is not active" }
                activeWindow.participantCount -= 1
                if (activeWindow.participantCount > 0) {
                    return@withLock
                }
                try {
                    if (activeWindow.shouldResume) {
                        resumeRuntimeIfOwned(activeWindow.mode, activeWindow.resumeLease)
                    }
                } finally {
                    rawPathWindow = null
                    runtimeModeProjectionStore.markDiagnosticsScanActive(false)
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
                }
            if (startResult == null) {
                waitForResumeResolution(resumeLease, compensateStoppedIntent = false)
                return
            }
            if (resumeRuntime(startResult)) {
                waitForResumeResolution(resumeLease, compensateStoppedIntent = true)
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

        private suspend fun waitForStatus(target: AppStatus) {
            repeat(waitAttempts) {
                if (serviceStateStore.status.value.first == target) {
                    return
                }
                delay(waitDelayMs)
            }
            error("Timed out waiting for service status $target")
        }

        private suspend fun waitForResumeResolution(
            resumeLease: ResumeLease,
            compensateStoppedIntent: Boolean,
        ) {
            var waitState = ResumeWaitState()
            for (attempt in 0 until waitAttempts) {
                waitState = evaluateResumeAttempt(resumeLease, waitState, compensateStoppedIntent)
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
            compensateStoppedIntent: Boolean,
        ): ResumeWaitState {
            val status = serviceStateStore.status.value.first
            return when (val ownership = runtimeResumeIntentTracker.ownership(resumeLease)) {
                ResumeLeaseOwnership.Owned -> {
                    waitState.copy(resolved = status == AppStatus.Running)
                }

                is ResumeLeaseOwnership.Superseded -> {
                    evaluateSupersededIntent(ownership, waitState, compensateStoppedIntent)
                }
            }
        }

        private fun evaluateSupersededIntent(
            ownership: ResumeLeaseOwnership.Superseded,
            waitState: ResumeWaitState,
            compensateStoppedIntent: Boolean,
        ): ResumeWaitState =
            when (ownership.intent) {
                UserRuntimeIntent.Running -> {
                    waitState.copy(resolved = serviceStateStore.status.value.first == AppStatus.Running)
                }

                UserRuntimeIntent.Stopped -> {
                    reconcileStoppedIntent(ownership, waitState, compensateStoppedIntent)
                }

                UserRuntimeIntent.Unknown -> {
                    waitState.copy(resolved = false)
                }
            }

        private fun reconcileStoppedIntent(
            ownership: ResumeLeaseOwnership.Superseded,
            waitState: ResumeWaitState,
            compensateStoppedIntent: Boolean,
        ): ResumeWaitState {
            if (!compensateStoppedIntent && serviceStateStore.status.value.first == AppStatus.Halted) {
                return waitState.copy(resolved = true)
            }
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

        private data class RawPathWindow(
            val mode: Mode,
            val resumeLease: ResumeLease,
            val runtimeWasRunning: Boolean,
            var shouldResume: Boolean,
            var participantCount: Int = 1,
        )
    }
