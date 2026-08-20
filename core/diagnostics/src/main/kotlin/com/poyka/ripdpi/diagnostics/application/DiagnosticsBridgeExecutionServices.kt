@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class BridgeExecutionService
    @Inject
    constructor(
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val activeScanRegistry: ActiveScanRegistry,
        private val retirementQueue: BridgeRetirementQueue,
    ) {
        internal suspend fun createHandle(
            sessionId: String,
            registerActiveBridge: Boolean,
        ): BridgeSessionHandle {
            val bridge = networkDiagnosticsBridgeFactory.create()
            val handle =
                BridgeSessionHandle(
                    bridge = bridge,
                    sessionId = sessionId,
                    registerActiveBridge = registerActiveBridge,
                )
            val registrationFailure =
                runCatching {
                    activeScanRegistry.registerBridge(bridge, sessionId, registerActiveBridge)
                }.exceptionOrNull()
            if (registrationFailure != null) {
                withContext(NonCancellable) {
                    runCatching { retireAfterStartupFailure(handle) }
                        .exceptionOrNull()
                        ?.takeIf { it !== registrationFailure }
                        ?.let(registrationFailure::addSuppressed)
                }
                throw registrationFailure
            }
            return handle
        }

        internal suspend fun start(
            handle: BridgeSessionHandle,
            requestJson: String,
        ) {
            val startFailure =
                runCatching {
                    handle.bridge.startScan(
                        requestJson = requestJson,
                        sessionId = handle.sessionId,
                    )
                }.exceptionOrNull()
            when (startFailure) {
                null -> Unit

                is CancellationException,
                is Error,
                -> throw startFailure

                else -> throw DiagnosticsBridgeStartException(startFailure)
            }
        }

        internal suspend fun retireAfterStartupFailure(handle: BridgeSessionHandle) {
            val reservation =
                checkNotNull(retirementQueue.tryReserve(handle.bridge)) {
                    "Diagnostics bridge retirement queue is closed"
                }
            val detachmentResult =
                runCatching {
                    withContext(NonCancellable) {
                        activeScanRegistry.detachBridge(
                            bridge = handle.bridge,
                            sessionId = handle.sessionId,
                            registerActiveBridge = handle.registerActiveBridge,
                        )
                    }
                }
            detachmentResult.exceptionOrNull()?.let { failure ->
                retirementQueue.abort(reservation)
                throw failure
            }
            val detachment = detachmentResult.getOrThrow()
            if (!detachment.confirmed) {
                retirementQueue.abort(reservation)
                error("Diagnostics bridge detachment was not confirmed")
            }
            retirementQueue.commit(reservation)
            detachment.publish()
        }

        internal suspend fun destroy(handle: BridgeSessionHandle) {
            try {
                handle.bridge.destroy()
            } finally {
                activeScanRegistry.clearBridge(
                    bridge = handle.bridge,
                    sessionId = handle.sessionId,
                    registerActiveBridge = handle.registerActiveBridge,
                )
            }
        }
    }

@Singleton
class PassiveEventPersistenceService
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun persist(
            sessionId: String,
            bridge: NetworkDiagnosticsBridge,
        ) {
            DiagnosticsReportPersister.persistNativeEvents(
                sessionId = sessionId,
                payload = bridge.pollPassiveEventsJson(),
                artifactWriteStore = artifactWriteStore,
                json = json,
            )
        }
    }

@Singleton
class BridgePollingService
    @Inject
    constructor(
        private val passiveEventPersistenceService: PassiveEventPersistenceService,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            private const val FinishedReportPollAttempts = 30
            private const val FinishedReportPollDelayMs = 250L
            private const val PollTimeoutNativeDeadlineGraceMs = 60_000L
            const val PollScanResultTimeoutMs = 360_000L + PollTimeoutNativeDeadlineGraceMs
            const val PollScanIntervalMs = 400L
        }

        internal suspend fun persistPassiveEvents(handle: BridgeSessionHandle) {
            passiveEventPersistenceService.persist(handle.sessionId, handle.bridge)
        }

        internal suspend fun pollProgress(handle: BridgeSessionHandle): ScanProgress? =
            handle.bridge
                .pollProgressJson()
                ?.let(json::decodeEngineProgressWire)
                ?.toScanProgress()

        internal suspend fun awaitFinishedReportJson(handle: BridgeSessionHandle): String? {
            repeat(FinishedReportPollAttempts) { attempt ->
                handle.bridge.takeReportJson()?.let { return it }
                if (attempt < FinishedReportPollAttempts - 1) {
                    delay(FinishedReportPollDelayMs)
                }
            }
            return null
        }

        private suspend fun claimCompletionOrCancel(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            activeScanRegistry: ActiveScanRegistry,
            reportJson: String,
        ) {
            if (!activeScanRegistry.claimCompletion(prepared.sessionId, handle.bridge, reportJson)) {
                throw CancellationException("Diagnostics scan cancellation owns the terminal report")
            }
        }

        internal suspend fun awaitCompletion(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            activeScanRegistry: ActiveScanRegistry,
            onFinishedReportJson: suspend (String) -> Unit,
        ) {
            try {
                withTimeout(PollScanResultTimeoutMs) {
                    while (true) {
                        persistPassiveEvents(handle)
                        handle.bridge.takeReportJson()?.let { reportJson ->
                            claimCompletionOrCancel(prepared, handle, activeScanRegistry, reportJson)
                            onFinishedReportJson(reportJson)
                            return@withTimeout
                        }
                        val progress = pollProgress(handle)
                        if (prepared.exposeProgress) {
                            activeScanRegistry.updateProgress(prepared.sessionId, progress)
                        }
                        if (progress?.isFinished == true) {
                            val reportJson =
                                checkNotNull(awaitFinishedReportJson(handle)) {
                                    "Diagnostics scan completed without a report"
                                }
                            claimCompletionOrCancel(prepared, handle, activeScanRegistry, reportJson)
                            onFinishedReportJson(reportJson)
                            break
                        }
                        delay(PollScanIntervalMs)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                awaitFinishedReportJson(handle)?.let { reportJson ->
                    claimCompletionOrCancel(prepared, handle, activeScanRegistry, reportJson)
                    onFinishedReportJson(reportJson)
                    return
                }
                throw IllegalStateException("Diagnostics scan timed out waiting for native completion", error)
            }
        }
    }
