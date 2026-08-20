@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanRequestFactory
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
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
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
            startBridgeBeforeAwait: Boolean,
            ownerId: String?,
        ) {
            val outcome =
                runPrimaryScan(
                    prepared = prepared,
                    handle = handle,
                    rawPathRunner = rawPathRunner,
                    startBridgeBeforeAwait = startBridgeBeforeAwait,
                )
            cleanupPrimaryScan(
                prepared = prepared,
                handle = handle,
                failure = outcome.failure ?: outcome.externalCancellation,
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
            rawPathRunner: suspend (suspend () -> Unit) -> Unit,
            startBridgeBeforeAwait: Boolean,
        ): PrimaryScanOutcome {
            var finalizationResult: ScanFinalizationResult? = null
            var externalCancellation: CancellationException? = null
            val failure =
                try {
                    val scanBlock =
                        primaryScanBlock(
                            prepared = prepared,
                            handle = handle,
                            startBridgeBeforeAwait = startBridgeBeforeAwait,
                        ) { result -> finalizationResult = result }
                    when (prepared.pathMode) {
                        ScanPathMode.RAW_PATH -> rawPathRunner(scanBlock)
                        ScanPathMode.IN_PATH -> scanBlock()
                    }
                    null
                } catch (error: CancellationException) {
                    if (activeScanRegistry.cancellationSummaryFor(prepared.sessionId) != null) {
                        error
                    } else {
                        externalCancellation = error
                        null
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    error
                }
            return PrimaryScanOutcome(
                failure = failure,
                finalizationResult = finalizationResult,
                externalCancellation = externalCancellation,
            )
        }

        private fun primaryScanBlock(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            startBridgeBeforeAwait: Boolean,
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
        ) {
            withContext(NonCancellable) {
                try {
                    if (failure != null) {
                        persistPrimaryFailure(prepared, failure)
                    }
                } finally {
                    activeScanRegistry.removePreparedScan(prepared.sessionId)
                    if (prepared.exposeProgress) {
                        activeScanRegistry.updateProgress(prepared.sessionId, null)
                    }
                    runCatching { bridgeExecutionService.destroy(handle) }
                }
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
            val reprobeFailure =
                runCatching {
                    activeScanRegistry.rememberPreparedScan(preparedReprobe, ownerId)
                    scanRecordStore.upsertScanSession(preparedReprobe.initialSession)
                    waitForVpnServiceResume()
                    reprobe =
                        preparedReprobe.bindCurrentInPathRoute(
                            serviceStateStore = serviceStateStore,
                            runtimeCoordinator = runtimeCoordinator,
                            scanRequestFactory = scanRequestFactory,
                        )
                    reprobeHandle =
                        bridgeExecutionService.createHandle(
                            sessionId = reprobe.sessionId,
                            registerActiveBridge = false,
                        )
                    val reprobeExecutionJob = checkNotNull(currentCoroutineContext()[Job])
                    check(
                        activeScanRegistry.registerExecution(
                            sessionId = reprobe.sessionId,
                            job = reprobeExecutionJob,
                            registerActiveBridge = false,
                        ),
                    ) { "DNS-corrected re-probe was cancelled before startup" }
                    bridgeExecutionService.start(
                        handle = requireNotNull(reprobeHandle),
                        requestJson = reprobe.requestJson,
                    )
                    bridgePollingService.awaitCompletion(
                        prepared = reprobe,
                        handle = requireNotNull(reprobeHandle),
                        activeScanRegistry = activeScanRegistry,
                    ) { reportJson ->
                        scanFinalizationService.finalize(
                            prepared = reprobe,
                            reportJson = reportJson,
                        )
                        bridgePollingService.persistPassiveEvents(requireNotNull(reprobeHandle))
                    }
                }.exceptionOrNull()
            try {
                if (reprobeFailure != null && reprobeFailure !is CancellationException) {
                    DiagnosticsReportPersister.persistScanFailure(
                        reprobe.sessionId,
                        reprobeFailure.message ?: "DNS-corrected re-probe failed",
                        scanRecordStore,
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    if (
                        reprobeFailure is CancellationException &&
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
            }
            if (reprobeFailure is CancellationException) throw reprobeFailure
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
)
