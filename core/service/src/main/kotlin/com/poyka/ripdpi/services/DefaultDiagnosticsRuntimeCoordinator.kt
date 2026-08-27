package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RawPathExecutionCancelledException
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RawPathExecutionSettlement
import com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome
import com.poyka.ripdpi.data.RawPathRuntimeContext
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.toRawPathRuntimeStatus
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.service.runtime.RuntimeModeProjectionStore
import com.poyka.ripdpi.service.runtime.control.RuntimeControlCommand
import com.poyka.ripdpi.service.runtime.control.RuntimeControlPlane
import com.poyka.ripdpi.service.runtime.control.RuntimeControlReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
        private val serviceRuntimeRegistry: ServiceRuntimeRegistry,
        private val vpnRouteEvidenceProvider: VpnRouteEvidenceProvider,
    ) : DiagnosticsRuntimeCoordinator {
        private var waitAttempts: Int = 50
        private var waitDelayMs: Long = 200L
        private val rawPathWindowMutex = Mutex()
        private var rawPathWindow: RawPathWindow? = null
        private var nextRawPathWindowGeneration: Long = 0L

        internal constructor(
            runtimeControlPlane: RuntimeControlPlane,
            runtimeModeProjectionStore: RuntimeModeProjectionStore,
            serviceStateStore: ServiceStateStore,
            appSettingsRepository: AppSettingsRepository,
            runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
            serviceController: ServiceController,
            serviceRuntimeRegistry: ServiceRuntimeRegistry,
            vpnRouteEvidenceProvider: VpnRouteEvidenceProvider,
            waitAttempts: Int,
            waitDelayMs: Long,
        ) : this(
            runtimeControlPlane,
            runtimeModeProjectionStore,
            serviceStateStore,
            appSettingsRepository,
            runtimeResumeIntentTracker,
            serviceController,
            serviceRuntimeRegistry,
            vpnRouteEvidenceProvider,
        ) {
            this.waitAttempts = waitAttempts
            this.waitDelayMs = waitDelayMs
        }

        override suspend fun runRawPathScan(block: suspend () -> Unit): RawPathExecutionResult {
            val diagnostics = appSettingsRepository.snapshot().toSettingsSections().diagnostics
            return runInRawPathWindow(
                label = "Raw-path scan",
                resumeIfRuntimeWasRunning = diagnostics.diagnosticsAutoResumeAfterRawScan,
                block = block,
            )
        }

        override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit): RawPathExecutionResult =
            runInRawPathWindow(
                label = "Automatic raw-path scan",
                resumeIfRuntimeWasRunning = true,
                block = block,
            )

        override suspend fun acquireInPathRouteLease(): DiagnosticsInPathRouteLease? {
            val published = serviceRuntimeRegistry.current(Mode.VPN)?.diagnosticsInPathRouteLease ?: return null
            val evidence = vpnRouteEvidenceProvider.capture()
            return if (isRouteEligible(published, evidence)) {
                published.copy(issuedRevision = evidence.callbackRevision).takeIf(::isInPathRouteLeaseCurrent)
            } else {
                null
            }
        }

        override fun isInPathRouteLeaseCurrent(lease: DiagnosticsInPathRouteLease): Boolean {
            if (lease.issuedRevision == null) return false
            val evidence = vpnRouteEvidenceProvider.capture()
            return isRouteEligible(lease, evidence) && lease.issuedRevision == evidence.callbackRevision &&
                serviceRuntimeRegistry.current(Mode.VPN)?.diagnosticsInPathRouteLease ==
                lease.copy(issuedRevision = null)
        }

        private fun isRouteEligible(
            lease: DiagnosticsInPathRouteLease,
            evidence: VpnRouteEvidence,
        ): Boolean =
            serviceStateStore.status.value == (AppStatus.Running to Mode.VPN) &&
                evidence.lifecycle?.generation == lease.routeGeneration &&
                evidence.isEligibleForInPathLease()

        @Suppress("TooGenericExceptionCaught")
        private suspend fun runInRawPathWindow(
            label: String,
            resumeIfRuntimeWasRunning: Boolean,
            block: suspend () -> Unit,
        ): RawPathExecutionResult {
            enterRawPathWindow(label, resumeIfRuntimeWasRunning)?.let { return it }
            var executionOutcome = RawPathExecutionOutcome.Completed
            var executionFailure: String? = null
            var cancellation: CancellationException? = null
            var settlement: RawPathExecutionSettlement? = null
            try {
                block()
            } catch (failure: CancellationException) {
                executionOutcome = RawPathExecutionOutcome.BlockCancelled
                executionFailure = failure.message ?: failure::class.java.simpleName
                cancellation = failure
            } catch (failure: Exception) {
                executionOutcome = RawPathExecutionOutcome.BlockFailed
                executionFailure = failure.message ?: failure::class.java.simpleName
            } finally {
                settlement =
                    withContext(NonCancellable) {
                        leaveRawPathWindow()
                    }
            }
            val result =
                RawPathExecutionResult(
                    settlement = checkNotNull(settlement),
                    executionOutcome = executionOutcome,
                    executionFailure = executionFailure,
                )
            cancellation?.let { throw RawPathExecutionCancelledException(result, it) }
            return result
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun enterRawPathWindow(
            label: String,
            resumeIfRuntimeWasRunning: Boolean,
        ): RawPathExecutionResult? =
            rawPathWindowMutex.withLock {
                rawPathWindow?.let { activeWindow ->
                    activeWindow.participantCount += 1
                    activeWindow.shouldResume =
                        activeWindow.shouldResume ||
                        (activeWindow.runtimeWasRunning && resumeIfRuntimeWasRunning)
                    Logger.d { "$label joined active raw-path window" }
                    return@withLock null
                }

                val resumeLease = runtimeResumeIntentTracker.captureResumeLease()
                val (status, mode) = serviceStateStore.status.value
                val runtimeWasRunning = status == AppStatus.Running
                nextRawPathWindowGeneration += 1
                rawPathWindow =
                    RawPathWindow(
                        rawWindowGeneration = nextRawPathWindowGeneration,
                        mode = mode,
                        resumeLease = resumeLease,
                        runtimeWasRunning = runtimeWasRunning,
                        shouldResume = runtimeWasRunning && resumeIfRuntimeWasRunning,
                    )
                runtimeModeProjectionStore.markDiagnosticsScanActive(true)
                try {
                    val projection = runtimeModeProjectionStore.projection.first()
                    Logger.d { "$label starting; runtime mode = $projection" }
                    if (runtimeWasRunning) {
                        runtimeControlPlane.execute(
                            RuntimeControlCommand.StopRuntime(RuntimeControlReason.DiagnosticsRawPathScan),
                        )
                        checkNotNull(rawPathWindow).stopIssued = true
                        waitForStatus(AppStatus.Halted)
                        checkNotNull(rawPathWindow).stopObserved = true
                    }
                    null
                } catch (failure: CancellationException) {
                    val result =
                        withContext(NonCancellable) {
                            settleEntryFailure(
                                checkNotNull(rawPathWindow),
                                RawPathExecutionOutcome.EntryCancelled,
                                failure.message ?: failure::class.java.simpleName,
                            )
                        }
                    throw RawPathExecutionCancelledException(result, failure)
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        settleEntryFailure(
                            checkNotNull(rawPathWindow),
                            RawPathExecutionOutcome.EntryFailed,
                            failure.message ?: failure::class.java.simpleName,
                        )
                    }
                }
            }

        private suspend fun leaveRawPathWindow(): RawPathExecutionSettlement {
            val settlement =
                rawPathWindowMutex.withLock {
                    val activeWindow = checkNotNull(rawPathWindow) { "Raw-path window is not active" }
                    activeWindow.participantCount -= 1
                    if (activeWindow.participantCount > 0) {
                        return@withLock activeWindow.settlement
                    }
                    activeWindow.settlement.complete(settleRawPathWindow(activeWindow))
                    rawPathWindow = null
                    runtimeModeProjectionStore.markDiagnosticsScanActive(false)
                    activeWindow.settlement
                }
            return settlement.await()
        }

        private suspend fun settleEntryFailure(
            activeWindow: RawPathWindow,
            executionOutcome: RawPathExecutionOutcome,
            executionFailure: String,
        ): RawPathExecutionResult {
            val settlement = settleRawPathWindow(activeWindow, avoidDuplicateStart = true)
            activeWindow.settlement.complete(settlement)
            rawPathWindow = null
            runtimeModeProjectionStore.markDiagnosticsScanActive(false)
            return RawPathExecutionResult(
                settlement = settlement,
                executionOutcome = executionOutcome,
                executionFailure = executionFailure,
            )
        }

        private suspend fun settleRawPathWindow(activeWindow: RawPathWindow): RawPathExecutionSettlement =
            settleRawPathWindow(activeWindow, avoidDuplicateStart = false)

        private suspend fun settleRawPathWindow(
            activeWindow: RawPathWindow,
            avoidDuplicateStart: Boolean,
        ): RawPathExecutionSettlement {
            if (activeWindow.stopIssued && serviceStateStore.status.value.first == AppStatus.Halted) {
                activeWindow.stopObserved = true
            }
            val stopMayStillConverge = activeWindow.stopIssued && !activeWindow.stopObserved
            val restoration =
                when {
                    !activeWindow.runtimeWasRunning -> {
                        RestoreSettlement(RawPathExecutionSettlementOutcome.RestoreNotRequired)
                    }

                    !activeWindow.shouldResume -> {
                        RestoreSettlement(RawPathExecutionSettlementOutcome.RestorePolicyDisabled)
                    }

                    avoidDuplicateStart &&
                        !stopMayStillConverge &&
                        serviceStateStore.status.value.first == AppStatus.Running -> {
                        RestoreSettlement(RawPathExecutionSettlementOutcome.Restored)
                    }

                    else -> {
                        restoreRuntimeIfOwned(
                            mode = activeWindow.mode,
                            resumeLease = activeWindow.resumeLease,
                            requireStopObservedBeforeRunning = stopMayStillConverge,
                        )
                    }
                }
            val (postStatus, postMode) = serviceStateStore.status.value
            return RawPathExecutionSettlement(
                rawWindowGeneration = activeWindow.rawWindowGeneration,
                resumeIntentGeneration = activeWindow.resumeLease.generation,
                outcome = restoration.outcome,
                runtimeWasRunning = activeWindow.runtimeWasRunning,
                resumeRequired = activeWindow.shouldResume,
                postRuntimeContext =
                    RawPathRuntimeContext(
                        status = postStatus.toRawPathRuntimeStatus(),
                        mode = postMode,
                    ),
                restoreFailure = restoration.failure,
            )
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun restoreRuntimeIfOwned(
            mode: Mode,
            resumeLease: ResumeLease,
            requireStopObservedBeforeRunning: Boolean,
        ): RestoreSettlement =
            try {
                if (requireStopObservedBeforeRunning) {
                    waitForStatus(AppStatus.Halted)
                }
                val startResult =
                    runtimeResumeIntentTracker.runIfOwned(resumeLease) {
                        serviceController.startForDiagnostics(mode)
                    }
                when {
                    startResult == null -> {
                        waitForResumeResolution(resumeLease, compensateStoppedIntent = false)
                        outcomeFromFinalOwnership(resumeLease)
                    }

                    resumeRuntime(startResult) -> {
                        waitForResumeResolution(resumeLease, compensateStoppedIntent = true)
                        outcomeFromFinalOwnership(resumeLease)
                    }

                    else -> {
                        RestoreSettlement(
                            outcome = RawPathExecutionSettlementOutcome.RestoreFailed,
                            failure = "resume_rejected",
                        )
                    }
                }
            } catch (failure: Exception) {
                RestoreSettlement(
                    outcome = RawPathExecutionSettlementOutcome.RestoreFailed,
                    failure = failure.message ?: failure::class.java.simpleName,
                )
            }

        private fun outcomeFromFinalOwnership(resumeLease: ResumeLease): RestoreSettlement =
            when (val ownership = runtimeResumeIntentTracker.ownership(resumeLease)) {
                is ResumeLeaseOwnership.Superseded -> {
                    if (ownership.intent == UserRuntimeIntent.Stopped) {
                        RestoreSettlement(RawPathExecutionSettlementOutcome.SupersededByUserStop)
                    } else {
                        RestoreSettlement(RawPathExecutionSettlementOutcome.Restored)
                    }
                }

                ResumeLeaseOwnership.Owned -> {
                    RestoreSettlement(RawPathExecutionSettlementOutcome.Restored)
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

        private data class RestoreSettlement(
            val outcome: RawPathExecutionSettlementOutcome,
            val failure: String? = null,
        )

        private data class RawPathWindow(
            val rawWindowGeneration: Long,
            val mode: Mode,
            val resumeLease: ResumeLease,
            val runtimeWasRunning: Boolean,
            var shouldResume: Boolean,
            var stopIssued: Boolean = false,
            var stopObserved: Boolean = false,
            var participantCount: Int = 1,
            val settlement: CompletableDeferred<RawPathExecutionSettlement> = CompletableDeferred(),
        )
    }
