@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RawPathExecutionCancelledException
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanRequestFactory
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DiagnosticsScanExecutionCoordinator
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val activeScanRegistry: ActiveScanRegistry,
        private val bridgeExecutionService: BridgeExecutionService,
        private val bridgePollingService: BridgePollingService,
        private val scanFinalizationService: ScanFinalizationService,
        private val scanRequestFactory: DiagnosticsScanRequestFactory,
        private val serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
    ) {
        private companion object {
            const val ServiceResumeWaitAttempts = 50
            const val ServiceResumeWaitDelayMs = 200L
        }

        internal suspend fun execute(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            rawPathRunner: suspend (suspend () -> Unit) -> RawPathExecutionResult,
            startBridgeBeforeAwait: Boolean = false,
        ) {
            val ownerId = activeScanRegistry.sessionOwnership.ownerId(prepared.sessionId)
            val executionJob = currentCoroutineContext()[Job]
            if (ownerId != null && executionJob != null) {
                activeScanRegistry.ownerExecutions.register(ownerId, executionJob)
            }
            try {
                executeOwned(
                    prepared = prepared,
                    handle = handle,
                    rawPathRunner = rawPathRunner,
                    startBridgeBeforeAwait = startBridgeBeforeAwait,
                    ownerId = ownerId,
                )
            } finally {
                if (ownerId != null && executionJob != null) {
                    activeScanRegistry.ownerExecutions.unregister(ownerId, executionJob)
                }
            }
        }

        private suspend fun executeOwned(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            rawPathRunner: suspend (suspend () -> Unit) -> RawPathExecutionResult,
            startBridgeBeforeAwait: Boolean,
            ownerId: String?,
        ) {
            val pollingState = BridgeReportPollingState()
            val outcome =
                runPrimaryScan(
                    prepared = prepared,
                    handle = handle,
                    rawPathRunner = rawPathRunner,
                    startBridgeBeforeAwait = startBridgeBeforeAwait,
                    pollingState = pollingState,
                )
            if (
                outcome.failure != null &&
                outcome.failure !is RawPathExecutionCancelledException &&
                activeScanRegistry.cancellationSummaryFor(prepared.sessionId) != null
            ) {
                activeScanRegistry.cancelledSessionFailures.putIfAbsent(prepared.sessionId, outcome.failure)
            }
            cleanupPrimaryScan(
                prepared = prepared,
                handle = handle,
                failure = outcome.failure ?: outcome.externalCancellation,
                persistFailure = !outcome.rawPathTerminalBarrierIncomplete,
                pollingState = pollingState,
            )
            outcome.externalCancellation?.let { cancellation -> throw cancellation }

            if (outcome.failure == null && outcome.finalizationResult?.shouldReprobeWithCorrectedDns == true) {
                runDnsCorrectedReprobe(
                    original = prepared,
                    finalizationResult = requireNotNull(outcome.finalizationResult),
                    ownerId = ownerId,
                )
            }
        }

        private suspend fun runPrimaryScan(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            rawPathRunner: suspend (suspend () -> Unit) -> RawPathExecutionResult,
            startBridgeBeforeAwait: Boolean,
            pollingState: BridgeReportPollingState,
        ): PrimaryScanOutcome {
            var finalizationResult: ScanFinalizationResult? = null
            var externalCancellation: CancellationException? = null
            var failure: Throwable? = null
            var rawPathSettlementPersisted = false
            var rawPathTerminalBarrierIncomplete = false
            try {
                val scanBlock =
                    primaryScanBlock(
                        prepared = prepared,
                        handle = handle,
                        startBridgeBeforeAwait = startBridgeBeforeAwait,
                        pollingState = pollingState,
                    ) { result -> finalizationResult = result }
                when (prepared.pathMode) {
                    ScanPathMode.RAW_PATH -> {
                        val recovery =
                            runAndRecoverRawPathExecution(
                                prepared = prepared,
                                handle = handle,
                                rawPathRunner = rawPathRunner,
                                scanBlock = scanBlock,
                                finalizationResult = { finalizationResult },
                                pollingState = pollingState,
                            )
                        finalizationResult = recovery.finalizationResult
                        persistRawPathSettlement(prepared, recovery.result, finalizationResult)
                        rawPathSettlementPersisted = true
                        if (recovery.result.executionOutcome != RawPathExecutionOutcome.Completed) {
                            throw RawPathExecutionFailureException(recovery.result)
                        }
                        currentCoroutineContext().ensureActive()
                    }

                    ScanPathMode.IN_PATH -> {
                        scanBlock()
                    }
                }
            } catch (error: RawPathExecutionCancelledException) {
                val cancellation =
                    settleCancelledRawPath(prepared, handle, error, finalizationResult, pollingState)
                finalizationResult = cancellation.finalizationResult
                rawPathSettlementPersisted = cancellation.settlementPersisted
                rawPathTerminalBarrierIncomplete = cancellation.terminalBarrierIncomplete
                failure = cancellation.failure
                externalCancellation = cancellation.externalCancellation
            } catch (error: CancellationException) {
                if (prepared.pathMode == ScanPathMode.RAW_PATH && !rawPathSettlementPersisted) {
                    rawPathTerminalBarrierIncomplete = true
                }
                if (activeScanRegistry.cancellationSummaryFor(prepared.sessionId) != null) {
                    failure = error
                } else {
                    externalCancellation = error
                }
            } catch (error: RawPathSettlementPersistenceException) {
                rawPathTerminalBarrierIncomplete = true
                failure = error
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                failure = error
            }
            return PrimaryScanOutcome(
                failure = failure,
                finalizationResult = finalizationResult,
                externalCancellation = externalCancellation,
                rawPathTerminalBarrierIncomplete = rawPathTerminalBarrierIncomplete,
            )
        }

        private suspend fun settleCancelledRawPath(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            error: RawPathExecutionCancelledException,
            finalizationResult: ScanFinalizationResult?,
            pollingState: BridgeReportPollingState,
        ): RawPathCancellationOutcome {
            val cancellationSummary = activeScanRegistry.cancellationSummaryFor(prepared.sessionId)
            val settlementResult =
                cancellationSummary?.let { summary ->
                    error.result.copy(executionFailure = summary)
                } ?: error.result
            val recovery =
                runCatching {
                    recoverCancelledRawPathFinalization(
                        prepared,
                        handle,
                        settlementResult,
                        finalizationResult,
                        pollingState,
                    )
                }
            val recoveredFinalization = recovery.getOrNull()
            val settlementFailure =
                runCatching {
                    persistRawPathSettlement(prepared, settlementResult, recoveredFinalization)
                }.exceptionOrNull()
            recovery.exceptionOrNull()?.let { recoveryFailure ->
                error.addSuppressed(recoveryFailure)
                activeScanRegistry.cancelledSessionFailures[prepared.sessionId] = recoveryFailure
            }
            settlementFailure?.let(error::addSuppressed)
            return RawPathCancellationOutcome(
                finalizationResult = recoveredFinalization,
                settlementPersisted = settlementFailure == null,
                terminalBarrierIncomplete = settlementFailure != null,
                failure = error.takeIf { cancellationSummary != null || recovery.isFailure },
                externalCancellation = error.takeIf { cancellationSummary == null },
            )
        }

        private suspend fun recoverCancelledRawPathFinalization(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            result: RawPathExecutionResult,
            finalizationResult: ScanFinalizationResult?,
            pollingState: BridgeReportPollingState,
        ): ScanFinalizationResult? =
            when {
                finalizationResult != null -> {
                    finalizationResult
                }

                else -> {
                    activeScanRegistry.consumeCancelledSessionReport(prepared.sessionId)?.let(pollingState::observe)
                    withContext(NonCancellable) {
                        recoverRawPathReport(
                            prepared = prepared,
                            handle = handle,
                            result = result,
                            pollingState = pollingState,
                        ).finalizationResult
                    }
                }
            }

        private suspend fun runAndRecoverRawPathExecution(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            rawPathRunner: suspend (suspend () -> Unit) -> RawPathExecutionResult,
            scanBlock: suspend () -> Unit,
            finalizationResult: () -> ScanFinalizationResult?,
            pollingState: BridgeReportPollingState,
        ): RawPathRecovery =
            recoverRawPathEvidenceBeforeSettlement(
                prepared = prepared,
                handle = handle,
                result = rawPathRunner(scanBlock),
                finalizationResult = finalizationResult(),
                pollingState = pollingState,
            )

        private suspend fun recoverRawPathEvidenceBeforeSettlement(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            result: RawPathExecutionResult,
            finalizationResult: ScanFinalizationResult?,
            pollingState: BridgeReportPollingState,
        ): RawPathRecovery =
            when {
                result.executionOutcome == RawPathExecutionOutcome.Completed -> {
                    RawPathRecovery(result, finalizationResult)
                }

                finalizationResult != null -> {
                    RawPathRecovery(result, finalizationResult)
                }

                else -> {
                    recoverRawPathReport(prepared, handle, result, pollingState)
                }
            }

        private suspend fun recoverRawPathReport(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            result: RawPathExecutionResult,
            pollingState: BridgeReportPollingState,
        ): RawPathRecovery =
            when (val outcome = bridgePollingService.awaitTerminalReportOutcome(handle, pollingState)) {
                is TerminalReportAwaitOutcome.Terminal -> {
                    val recoveredFinalization =
                        runCatching {
                            scanFinalizationService.finalize(prepared, outcome.reportJson)
                        }.getOrNull()
                    if (recoveredFinalization == null) {
                        runCatching {
                            scanFinalizationService.persistRawPathRecoveryReport(prepared, outcome.reportJson)
                        }
                        RawPathRecovery(result, null)
                    } else {
                        runCatching { bridgePollingService.persistPassiveEvents(handle) }
                        RawPathRecovery(
                            result = result,
                            finalizationResult = recoveredFinalization,
                        )
                    }
                }

                is TerminalReportAwaitOutcome.TerminalUnavailable -> {
                    outcome.latestCheckpointJson?.let { checkpointJson ->
                        scanFinalizationService.persistRawPathRecoveryReport(prepared, checkpointJson)
                    }
                    RawPathRecovery(result, null)
                }
            }

        /**
         * # Cancel safety:
         * Once the runtime coordinator has settled the raw-path window, receipt persistence and
         * terminal publication are completed as one non-cancellable lifecycle step. The receipt
         * is written first; a persistence failure therefore cannot publish a terminal session
         * without the post-runtime evidence required by the home-stage barrier.
         */
        private suspend fun persistRawPathSettlement(
            prepared: PreparedDiagnosticsScan,
            result: RawPathExecutionResult,
            finalizationResult: ScanFinalizationResult?,
        ) {
            try {
                withContext(NonCancellable) {
                    scanFinalizationService.persistRawPathSettlement(
                        prepared = prepared,
                        result = result,
                        finalizationResult = finalizationResult,
                    )
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                throw RawPathSettlementPersistenceException(error)
            }
        }

        private fun primaryScanBlock(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            startBridgeBeforeAwait: Boolean,
            pollingState: BridgeReportPollingState,
            onFinalized: (ScanFinalizationResult) -> Unit,
        ): suspend () -> Unit =
            {
                if (startBridgeBeforeAwait) {
                    bridgeExecutionService.start(
                        handle = handle,
                        requestJson = prepared.requestJson,
                    )
                }
                bridgePollingService.awaitCompletion(
                    prepared = prepared,
                    handle = handle,
                    activeScanRegistry = activeScanRegistry,
                    pollingState = pollingState,
                ) { reportJson ->
                    onFinalized(
                        scanFinalizationService.finalize(
                            prepared = prepared,
                            reportJson = reportJson,
                        ),
                    )
                    bridgePollingService.persistPassiveEvents(handle)
                    if (prepared.exposeProgress) {
                        activeScanRegistry.updateProgress(prepared.sessionId, null)
                    }
                }
            }

        private suspend fun cleanupPrimaryScan(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            failure: Throwable?,
            persistFailure: Boolean,
            pollingState: BridgeReportPollingState,
        ) {
            withContext(NonCancellable) {
                try {
                    if (failure != null && persistFailure) {
                        val fallbackPersistence =
                            runCatching {
                                persistTerminalOrCheckpointFallback(
                                    prepared = prepared,
                                    outcome = bridgePollingService.awaitTerminalReportOutcome(handle, pollingState),
                                )
                            }
                        fallbackPersistence.exceptionOrNull()?.let { fallbackFailure ->
                            if (fallbackFailure !== failure) failure.addSuppressed(fallbackFailure)
                        }
                        if (fallbackPersistence.getOrDefault(false).not()) {
                            persistPrimaryFailure(
                                prepared = prepared,
                                failure = failure,
                            )
                        }
                    }
                } finally {
                    runCatching { bridgeExecutionService.destroy(handle) }
                    activeScanRegistry.removePreparedScan(prepared.sessionId)
                    if (prepared.exposeProgress) {
                        activeScanRegistry.updateProgress(prepared.sessionId, null)
                    }
                }
            }
        }

        private suspend fun persistTerminalOrCheckpointFallback(
            prepared: PreparedDiagnosticsScan,
            outcome: TerminalReportAwaitOutcome,
        ): Boolean =
            when (outcome) {
                is TerminalReportAwaitOutcome.Terminal -> {
                    val runningSession =
                        scanRecordStore.getScanSession(prepared.sessionId)?.takeIf { it.status == "running" }
                            ?: return false
                    val finalizeFailure =
                        runCatching {
                            scanFinalizationService.finalize(
                                prepared = prepared,
                                reportJson = outcome.reportJson,
                            )
                        }.exceptionOrNull()
                    if (finalizeFailure is CancellationException) throw finalizeFailure
                    if (finalizeFailure != null) {
                        Logger.w(finalizeFailure) {
                            "Scan finalization failed; persisted partial session instead"
                        }
                        persistPartialScanSession(runningSession, outcome.reportJson, scanRecordStore)
                    }
                    true
                }

                is TerminalReportAwaitOutcome.TerminalUnavailable -> {
                    outcome.latestCheckpointJson?.let { checkpointJson ->
                        val runningSession =
                            scanRecordStore.getScanSession(prepared.sessionId)?.takeIf { it.status == "running" }
                        if (runningSession == null) {
                            false
                        } else {
                            persistPartialScanSession(runningSession, checkpointJson, scanRecordStore)
                            true
                        }
                    } ?: false
                }
            }

        private suspend fun persistPrimaryFailure(
            prepared: PreparedDiagnosticsScan,
            failure: Throwable,
        ) {
            val partialReportJson = activeScanRegistry.consumeCancelledSessionReport(prepared.sessionId)
            val runningSession =
                scanRecordStore.getScanSession(prepared.sessionId)?.takeIf {
                    it.status == "running"
                }
            if (partialReportJson != null && runningSession != null) {
                persistPartialScanSession(runningSession, partialReportJson, scanRecordStore)
            } else {
                DiagnosticsReportPersister.persistScanFailure(
                    prepared.sessionId,
                    failure.summaryForScan(prepared.sessionId, activeScanRegistry),
                    scanRecordStore,
                )
            }
        }

        private suspend fun runDnsCorrectedReprobe(
            original: PreparedDiagnosticsScan,
            finalizationResult: ScanFinalizationResult,
            ownerId: String?,
        ) {
            val preparedReprobe =
                scanRequestFactory.prepareReprobe(
                    original = original,
                    preferredDnsPathOverride = finalizationResult.correctedDnsPath,
                )
            var reprobe = preparedReprobe
            var reprobeHandle: BridgeSessionHandle? = null
            val reprobePollingState = BridgeReportPollingState()
            val reprobeFailure =
                runCatching {
                    activeScanRegistry.rememberPreparedScan(preparedReprobe, ownerId)
                    scanRecordStore.upsertScanSession(preparedReprobe.initialSession)
                    // Reserve the hidden scan slot and bind the execution job BEFORE
                    // the VPN-resume wait. Admission control (hasActiveScan /
                    // HiddenAutomaticProbeConflict) reads only the registry, so a slot
                    // reserved here is what prevents a manual or automatic start from
                    // being admitted into the resume window and probing the same
                    // network path concurrently with this re-probe.
                    reprobeHandle =
                        bridgeExecutionService.createHandle(
                            sessionId = preparedReprobe.sessionId,
                            registerActiveBridge = false,
                        )
                    val reprobeExecutionJob = checkNotNull(currentCoroutineContext()[Job])
                    check(
                        activeScanRegistry.registerExecution(
                            sessionId = preparedReprobe.sessionId,
                            job = reprobeExecutionJob,
                            registerActiveBridge = false,
                        ),
                    ) { "DNS-corrected re-probe was cancelled before startup" }
                    waitForVpnServiceResume()
                    reprobe =
                        preparedReprobe.bindCurrentInPathRoute(
                            serviceStateStore = serviceStateStore,
                            runtimeCoordinator = runtimeCoordinator,
                            scanRequestFactory = scanRequestFactory,
                        )
                    bridgeExecutionService.start(
                        handle = requireNotNull(reprobeHandle),
                        requestJson = reprobe.requestJson,
                    )
                    bridgePollingService.awaitCompletion(
                        prepared = reprobe,
                        handle = requireNotNull(reprobeHandle),
                        activeScanRegistry = activeScanRegistry,
                        pollingState = reprobePollingState,
                    ) { reportJson ->
                        scanFinalizationService.finalize(
                            prepared = reprobe,
                            reportJson = reportJson,
                        )
                        bridgePollingService.persistPassiveEvents(requireNotNull(reprobeHandle))
                    }
                }.exceptionOrNull()
            var reportPersisted = false
            try {
                reportPersisted =
                    persistDnsReprobeFailureEvidence(
                        reprobe = reprobe,
                        reprobeHandle = reprobeHandle,
                        pollingState = reprobePollingState,
                        failure = reprobeFailure,
                    )
            } finally {
                cleanupDnsReprobe(reprobe, reprobeHandle, reprobeFailure, reportPersisted)
            }
            if (reprobeFailure is CancellationException) throw reprobeFailure
        }

        private suspend fun persistDnsReprobeFailureEvidence(
            reprobe: PreparedDiagnosticsScan,
            reprobeHandle: BridgeSessionHandle?,
            pollingState: BridgeReportPollingState,
            failure: Throwable?,
        ): Boolean {
            val reportPersisted =
                if (failure == null) {
                    false
                } else {
                    withContext(NonCancellable) {
                        reprobeHandle?.let { handle ->
                            persistTerminalOrCheckpointFallback(
                                prepared = reprobe,
                                outcome = bridgePollingService.awaitTerminalReportOutcome(handle, pollingState),
                            )
                        } == true
                    }
                }
            if (!reportPersisted && failure != null && failure !is CancellationException) {
                DiagnosticsReportPersister.persistScanFailure(
                    reprobe.sessionId,
                    failure.message ?: "DNS-corrected re-probe failed",
                    scanRecordStore,
                )
            }
            return reportPersisted
        }

        private suspend fun cleanupDnsReprobe(
            reprobe: PreparedDiagnosticsScan,
            reprobeHandle: BridgeSessionHandle?,
            failure: Throwable?,
            reportPersisted: Boolean,
        ) = withContext(NonCancellable) {
            if (
                !reportPersisted &&
                failure is CancellationException &&
                activeScanRegistry.cancellationSummaryFor(reprobe.sessionId) == null
            ) {
                DiagnosticsReportPersister.persistScanFailure(
                    reprobe.sessionId,
                    "Diagnostics scan canceled during startup",
                    scanRecordStore,
                )
            }
            activeScanRegistry.removePreparedScan(reprobe.sessionId)
            reprobeHandle?.let { handle ->
                runCatching { bridgeExecutionService.destroy(handle) }
            }
        }

        private suspend fun waitForVpnServiceResume() {
            repeat(ServiceResumeWaitAttempts) {
                if (serviceStateStore.status.value == AppStatus.Running to Mode.VPN) {
                    return
                }
                delay(ServiceResumeWaitDelayMs)
            }
            error("Timed out waiting for VPN service to resume before DNS-corrected re-probe")
        }
    }

private fun Throwable.summaryForScan(
    sessionId: String,
    activeScanRegistry: ActiveScanRegistry,
): String = activeScanRegistry.cancellationSummaryFor(sessionId) ?: message ?: "Diagnostics scan failed"

private data class PrimaryScanOutcome(
    val failure: Throwable?,
    val finalizationResult: ScanFinalizationResult?,
    val externalCancellation: CancellationException?,
    val rawPathTerminalBarrierIncomplete: Boolean,
)

private class RawPathSettlementPersistenceException(
    cause: Throwable,
) : IllegalStateException("Failed to persist raw-path runtime settlement", cause)

private class RawPathExecutionFailureException(
    result: RawPathExecutionResult,
) : IllegalStateException(
        result.executionFailure ?: "Raw-path execution ended as ${result.executionOutcome}",
    )

private data class RawPathRecovery(
    val result: RawPathExecutionResult,
    val finalizationResult: ScanFinalizationResult?,
)

private data class RawPathCancellationOutcome(
    val finalizationResult: ScanFinalizationResult?,
    val settlementPersisted: Boolean,
    val terminalBarrierIncomplete: Boolean,
    val failure: Throwable?,
    val externalCancellation: CancellationException?,
)
