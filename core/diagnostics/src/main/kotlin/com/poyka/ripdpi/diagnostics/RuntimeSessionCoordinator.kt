package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeSessionCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val serviceStateStore: ServiceStateStore,
        private val activeConnectionPolicyStore: ActiveConnectionPolicyStore,
        private val rememberedPolicySessionTracker: RememberedPolicySessionTracker,
        private val artifactPersister: RuntimeArtifactPersister,
        private val deviceStateEventRecorder: DeviceStateEventRecorder,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) {
        private val stateMutex = Mutex()
        private val networkTransitionSessionGate = NetworkTransitionSessionGate()

        private var activeUsageSession: BypassUsageSessionEntity? = null

        @Volatile
        private var networkTransitionFlush: (suspend () -> Boolean)? = null
        private var samplingJob: Job? = null
        private var reconnectInProgress = false
        private var lastRecordedNetworkHandoverState: String? = null

        suspend fun handleStatusChange(
            status: AppStatus,
            mode: Mode,
        ) {
            if (status == AppStatus.Reconnecting) {
                stateMutex.withLock {
                    val hadActiveSession = activeUsageSession != null
                    if (hadActiveSession) {
                        deviceStateEventRecorder.recordReconnectStart()
                        finalizeActiveUsageSession(serviceStateStore.telemetry.value)
                    }
                    reconnectInProgress = true
                    deviceStateEventRecorder.beginServiceStart(mode)
                    if (!hadActiveSession) {
                        deviceStateEventRecorder.recordReconnectStart()
                    }
                }
                return
            }

            if (status == AppStatus.Running) {
                stateMutex.withLock {
                    ensureRecorderAttachedUsageSession(mode)
                    if (reconnectInProgress) {
                        deviceStateEventRecorder.recordRecovery()
                    }
                    reconnectInProgress = false
                }
                startSampling()
                return
            }

            stopSampling()
            stateMutex.withLock {
                deviceStateEventRecorder.recordStop()
                reconnectInProgress = false
                finalizeActiveUsageSession(serviceStateStore.telemetry.value)
            }
        }

        suspend fun handleTelemetryUpdate(telemetry: ServiceTelemetrySnapshot) {
            val connectionSessionId =
                stateMutex.withLock {
                    if (serviceStateStore.status.value.first == AppStatus.Running) {
                        val mode = serviceStateStore.status.value.second
                        ensureRecorderAttachedUsageSession(mode)
                        updateActiveUsageSession(
                            serviceMode = mode,
                            telemetry = telemetry,
                            networkType = activeUsageSession?.networkType ?: "unknown",
                            publicIp = activeUsageSession?.publicIp,
                        )
                        val handoverState = telemetry.networkHandoverState?.takeIf(String::isNotBlank)
                        if (handoverState != null && handoverState != lastRecordedNetworkHandoverState) {
                            deviceStateEventRecorder.recordHandover()
                            lastRecordedNetworkHandoverState = handoverState
                        }
                    }
                    activeUsageSession?.id
                }

            artifactPersister.persistRuntimeEvents(
                serviceTelemetry = telemetry,
                connectionSessionId = connectionSessionId,
            )
        }

        suspend fun handleDeviceRuntimeEvidence(event: DeviceRuntimeEvidence) {
            deviceStateEventRecorder.recordRuntimeEvidence(event)
        }

        internal suspend fun handleNetworkTransition(event: NetworkTransitionEvent) {
            artifactPersister.persistNetworkTransition(event, event.connectionSessionId)
        }

        internal fun captureNetworkTransition(enqueue: (String) -> Boolean): Boolean? =
            networkTransitionSessionGate.capture(enqueue)

        internal fun registerNetworkTransitionFlush(flush: suspend () -> Boolean) {
            networkTransitionFlush = flush
        }

        suspend fun handleFailure(
            sender: Sender,
            reason: FailureReason,
        ) {
            val timestamp = System.currentTimeMillis()
            val failureMessage = reason.displayMessage
            val telemetry = serviceStateStore.telemetry.value
            val snapshot = artifactPersister.captureSnapshotOrNull()
            var standaloneTerminalFailure = false
            val connectionSessionId =
                stateMutex.withLock {
                    val current = activeUsageSession
                    if (current == null) {
                        standaloneTerminalFailure = true
                        val connectionSessionId =
                            createFailedUsageSession(
                                sender = sender,
                                failureMessage = failureMessage,
                                timestamp = timestamp,
                                telemetry = telemetry,
                                snapshot = snapshot,
                            )
                        deviceStateEventRecorder.recordStandaloneFailure(
                            connectionSessionId,
                            serviceStateStore.status.value.second,
                        )
                        connectionSessionId
                    } else {
                        val updated =
                            RuntimeUsageSessionBuilder.updateFailedSession(
                                current = current,
                                sender = sender,
                                failureMessage = failureMessage,
                                timestamp = timestamp,
                                telemetry = telemetry,
                                networkType = snapshot?.transport ?: current.networkType,
                                publicIp = snapshot?.publicIp ?: current.publicIp,
                            )
                        activeUsageSession = updated
                        bypassUsageHistoryStore.upsertBypassUsageSession(updated)
                        deviceStateEventRecorder.recordFailure()
                        updated.id
                    }
                }

            artifactPersister.persistFailureArtifacts(
                connectionSessionId = connectionSessionId,
                sender = sender,
                failureMessage = failureMessage,
                snapshot = snapshot,
                telemetry = telemetry,
                createdAt = timestamp,
                networkTypeFallback = activeUsageSession?.networkType ?: "unknown",
                publicIpFallback = activeUsageSession?.publicIp,
            )
            if (standaloneTerminalFailure) {
                artifactPersister.persistTerminalRootCauseAssessment(
                    connectionSessionId = connectionSessionId,
                    createdAt = timestamp,
                    terminalEvidenceSealed = false,
                )
            }
        }

        suspend fun handleActiveConnectionPolicyChange(policy: ActiveConnectionPolicy?) {
            stateMutex.withLock {
                if (serviceStateStore.status.value.first != AppStatus.Running) {
                    return
                }
                val session = activeUsageSession ?: return
                val updatedSession =
                    rememberedPolicySessionTracker.sync(
                        session = session,
                        activePolicy = policy,
                    )
                if (updatedSession != session) {
                    activeUsageSession = updatedSession
                    bypassUsageHistoryStore.upsertBypassUsageSession(updatedSession)
                }
            }
        }

        private fun startSampling() {
            if (samplingJob?.isActive == true) {
                return
            }

            samplingJob =
                scope.launch {
                    while (true) {
                        val settings = appSettingsRepository.snapshot()
                        val currentSessionId =
                            stateMutex.withLock {
                                activeUsageSession?.id
                            } ?: break

                        if (settings.diagnosticsMonitorEnabled &&
                            serviceStateStore.status.value.first == AppStatus.Running
                        ) {
                            persistSample(currentSessionId)
                            artifactPersister.trimHistory(settings.diagnosticsHistoryRetentionDays)
                        }

                        delay(
                            settings
                                .diagnosticsSampleIntervalSeconds
                                .coerceIn(MinDiagnosticsSampleIntervalSeconds, MaxDiagnosticsSampleIntervalSeconds) *
                                MillisPerSecond,
                        )
                    }
                }
        }

        private fun stopSampling() {
            samplingJob?.cancel()
            samplingJob = null
        }

        private suspend fun ensureActiveUsageSession(mode: Mode) {
            val current = activeUsageSession
            if (current != null && current.serviceMode == mode.name && current.finishedAt == null) {
                return
            }

            if (current != null) {
                finalizeActiveUsageSession(serviceStateStore.telemetry.value)
            }

            val seed = captureSessionSeed()
            val snapshot = artifactPersister.captureSnapshotOrNull()
            val telemetry = serviceStateStore.telemetry.value
            val startedAt = maxOf(System.currentTimeMillis(), telemetry.updatedAt)
            val session =
                RuntimeUsageSessionBuilder.createActiveSession(
                    sessionId = UUID.randomUUID().toString(),
                    mode = mode,
                    startedAt = startedAt,
                    networkType = snapshot?.transport ?: "unknown",
                    publicIp = snapshot?.publicIp,
                    telemetry = telemetry,
                    seed = seed,
                )
            activeUsageSession = session
            networkTransitionSessionGate.activate(session.id)
            lastRecordedNetworkHandoverState = telemetry.networkHandoverState?.takeIf(String::isNotBlank)
            bypassUsageHistoryStore.upsertBypassUsageSession(session)
            val updatedSession =
                rememberedPolicySessionTracker.sync(
                    session = session,
                    activePolicy = activeConnectionPolicyStore.current(mode),
                )
            if (updatedSession != session) {
                activeUsageSession = updatedSession
                bypassUsageHistoryStore.upsertBypassUsageSession(updatedSession)
            }
        }

        private suspend fun ensureRecorderAttachedUsageSession(mode: Mode): String {
            val current = activeUsageSession
            val requiresNewSession = current == null || current.serviceMode != mode.name || current.finishedAt != null
            if (requiresNewSession) {
                deviceStateEventRecorder.beginServiceStart(mode)
            }
            ensureActiveUsageSession(mode)
            val connectionSessionId = checkNotNull(activeUsageSession).id
            if (requiresNewSession) {
                deviceStateEventRecorder.attachRunningSession(connectionSessionId, mode)
            }
            return connectionSessionId
        }

        private suspend fun createFailedUsageSession(
            sender: Sender,
            failureMessage: String,
            timestamp: Long,
            telemetry: ServiceTelemetrySnapshot,
            snapshot: NetworkSnapshotModel?,
        ): String {
            val seed = captureSessionSeed()
            val session =
                RuntimeUsageSessionBuilder.createFailedSession(
                    sessionId = UUID.randomUUID().toString(),
                    mode = serviceStateStore.status.value.second,
                    sender = sender,
                    failureMessage = failureMessage,
                    timestamp = timestamp,
                    networkType = snapshot?.transport ?: "unknown",
                    publicIp = snapshot?.publicIp,
                    telemetry = telemetry,
                    seed = seed,
                )
            bypassUsageHistoryStore.upsertBypassUsageSession(session)
            return session.id
        }

        private suspend fun updateActiveUsageSession(
            serviceMode: Mode,
            telemetry: ServiceTelemetrySnapshot,
            networkType: String,
            publicIp: String?,
        ) {
            val current = activeUsageSession ?: return
            val updated =
                RuntimeUsageSessionBuilder.updateRunningSession(
                    current = current,
                    serviceMode = serviceMode,
                    telemetry = telemetry,
                    timestamp = maxOf(System.currentTimeMillis(), telemetry.updatedAt),
                    networkType = networkType,
                    publicIp = publicIp,
                )
            activeUsageSession = updated
            bypassUsageHistoryStore.upsertBypassUsageSession(updated)
        }

        private suspend fun finalizeActiveUsageSession(telemetry: ServiceTelemetrySnapshot) {
            val current = activeUsageSession ?: return
            networkTransitionSessionGate.deactivate()
            val finalizedAt = maxOf(System.currentTimeMillis(), telemetry.updatedAt)
            var finalized = false
            try {
                val terminalEvidenceSealed = sealNetworkTransitions()
                artifactPersister.persistRuntimeEvents(
                    serviceTelemetry = telemetry,
                    connectionSessionId = current.id,
                )
                if (current.failureMessage.isNullOrBlank()) {
                    artifactPersister.persistTerminalTelemetrySample(
                        connectionSessionId = current.id,
                        telemetry = telemetry,
                        createdAt = finalizedAt,
                        networkTypeFallback = current.networkType,
                        publicIpFallback = current.publicIp,
                        connectionState = "Stopped",
                    )
                }
                val finishedSession =
                    RuntimeUsageSessionBuilder.finalizeSession(
                        current = current,
                        telemetry = telemetry,
                        finalizedAt = finalizedAt,
                    )
                rememberedPolicySessionTracker.finalize(finishedSession, finalizedAt)
                bypassUsageHistoryStore.upsertBypassUsageSession(finishedSession)
                artifactPersister.persistTerminalRootCauseAssessment(
                    connectionSessionId = current.id,
                    createdAt = finalizedAt,
                    terminalEvidenceSealed = terminalEvidenceSealed,
                )
                activeUsageSession = null
                lastRecordedNetworkHandoverState = null
                rememberedPolicySessionTracker.clear()
                finalized = true
            } finally {
                if (!finalized) {
                    networkTransitionSessionGate.activate(current.id)
                }
            }
        }

        private suspend fun sealNetworkTransitions(): Boolean {
            val result = runCatching { networkTransitionFlush?.invoke() ?: false }
            val failure = result.exceptionOrNull() ?: return result.getOrThrow()
            if (failure is CancellationException) throw failure
            if (failure is Exception) return false
            throw failure
        }

        private suspend fun persistSample(connectionSessionId: String) {
            val telemetry = serviceStateStore.telemetry.value
            val snapshot =
                artifactPersister.persistConnectionSample(
                    connectionSessionId = connectionSessionId,
                    telemetry = telemetry,
                ) ?: return

            stateMutex.withLock {
                updateActiveUsageSession(
                    serviceMode = serviceStateStore.status.value.second,
                    telemetry = telemetry,
                    networkType = snapshot.transport,
                    publicIp = snapshot.publicIp,
                )
            }
        }

        private suspend fun captureSessionSeed(): RuntimeSessionSeed {
            val settings = appSettingsRepository.snapshot()
            val profile =
                settings.diagnosticsActiveProfileId
                    .takeIf { it.isNotBlank() }
                    ?.let { profileCatalog.getProfile(it) }
            val context = diagnosticsContextProvider.captureContext()
            return RuntimeSessionSeed(
                approach = createStoredApproachSnapshot(RuntimeHistoryJson, settings, profile, context),
                restartCount = context.service.restartCount,
            )
        }
    }

private const val MinDiagnosticsSampleIntervalSeconds = 5
private const val MaxDiagnosticsSampleIntervalSeconds = 300
private const val MillisPerSecond = 1_000L
