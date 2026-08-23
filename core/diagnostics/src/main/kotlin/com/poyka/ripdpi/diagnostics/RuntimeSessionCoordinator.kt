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
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTerminalOutboxStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.data.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        private val terminalOutboxStore: DiagnosticsTerminalOutboxStore,
        private val rememberedNetworkPolicyRecordStore: RememberedNetworkPolicyRecordStore,
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
        private val terminalOutbox =
            RuntimeTerminalOutbox(
                usageHistoryStore = bypassUsageHistoryStore,
                outboxStore = terminalOutboxStore,
                policyRecordStore = rememberedNetworkPolicyRecordStore,
                artifactPersister = artifactPersister,
                rememberedPolicySessionTracker = rememberedPolicySessionTracker,
            )

        private var activeUsageSession: BypassUsageSessionEntity? = null
        private var pendingTerminalStart: TerminalOutboxStart? = null
        private var pendingTerminalSession: PendingTerminalSession? = null
        private var terminalAssessmentReconciliationComplete = false

        @Volatile
        private var networkTransitionFlush: (suspend (NetworkTransitionAdmission) -> Boolean)? = null
        private var samplingJob: Job? = null
        private var reconnectInProgress = false
        private var lastRecordedNetworkHandoverState: String? = null

        suspend fun handleStatusChange(
            status: AppStatus,
            mode: Mode,
        ) {
            if (status == AppStatus.Reconnecting) {
                stateMutex.withLock {
                    persistPendingTerminalSession()
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
                    persistPendingTerminalSession()
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
                persistPendingTerminalSession()
                deviceStateEventRecorder.recordStop()
                reconnectInProgress = false
                finalizeActiveUsageSession(serviceStateStore.telemetry.value)
            }
        }

        suspend fun handleTelemetryUpdate(telemetry: ServiceTelemetrySnapshot) {
            stateMutex.withLock {
                if (serviceStateStore.status.value.first == AppStatus.Running) {
                    persistPendingTerminalSession()
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
                artifactPersister.persistRuntimeEvents(
                    serviceTelemetry = telemetry,
                    connectionSessionId = activeUsageSession?.id,
                )
            }
        }

        suspend fun handleDeviceRuntimeEvidence(event: DeviceRuntimeEvidence) {
            deviceStateEventRecorder.recordRuntimeEvidence(event)
        }

        internal suspend fun handleNetworkTransition(event: NetworkTransitionEvent) {
            artifactPersister.persistNetworkTransition(event, event.connectionSessionId)
        }

        internal fun withNetworkTransitionAdmission(block: (NetworkTransitionAdmission?) -> Unit) {
            networkTransitionSessionGate.withAdmission(block)
        }

        internal fun registerNetworkTransitionFlush(flush: suspend (NetworkTransitionAdmission) -> Boolean) {
            networkTransitionFlush = flush
        }

        suspend fun handleFailure(
            sender: Sender,
            reason: FailureReason,
        ) {
            stopSampling()
            val timestamp = System.currentTimeMillis()
            val failureMessage = reason.displayMessage
            val telemetry = serviceStateStore.telemetry.value
            val snapshot = artifactPersister.captureSnapshotOrNull()
            stateMutex.withLock {
                persistPendingTerminalSession()
                val current = activeUsageSession
                val standaloneTerminalFailure = current == null
                val failedSession =
                    if (standaloneTerminalFailure) {
                        buildFailedUsageSession(
                            sender = sender,
                            failureMessage = failureMessage,
                            timestamp = timestamp,
                            telemetry = telemetry,
                            snapshot = snapshot,
                        )
                    } else {
                        RuntimeUsageSessionBuilder.updateFailedSession(
                            current = requireNotNull(current),
                            sender = sender,
                            failureMessage = failureMessage,
                            timestamp = timestamp,
                            telemetry = telemetry,
                            networkType = snapshot?.transport ?: current.networkType,
                            publicIp = snapshot?.publicIp ?: current.publicIp,
                        )
                    }

                artifactPersister.persistFailureArtifacts(
                    usageSession = failedSession,
                    sender = sender,
                    failureMessage = failureMessage,
                    snapshot = snapshot,
                    telemetry = telemetry,
                    createdAt = timestamp,
                    networkTypeFallback = failedSession.networkType,
                    publicIpFallback = failedSession.publicIp,
                )
                activeUsageSession = failedSession
                if (standaloneTerminalFailure) {
                    deviceStateEventRecorder.recordStandaloneFailure(
                        failedSession.id,
                        serviceStateStore.status.value.second,
                    )
                    artifactPersister.persistTerminalRootCauseAssessment(
                        connectionSessionId = failedSession.id,
                        createdAt = timestamp,
                        terminalEvidenceSealed = false,
                    )
                } else {
                    deviceStateEventRecorder.recordFailure()
                }
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

        private suspend fun startSampling() {
            // Serialize the check-and-launch so two concurrent service-running
            // events cannot both observe an inactive job and start duplicate
            // sampler loops.
            stateMutex.withLock {
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
                                    .coerceIn(
                                        MinDiagnosticsSampleIntervalSeconds,
                                        MaxDiagnosticsSampleIntervalSeconds,
                                    ) *
                                    MillisPerSecond,
                            )
                        }
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

        private suspend fun buildFailedUsageSession(
            sender: Sender,
            failureMessage: String,
            timestamp: Long,
            telemetry: ServiceTelemetrySnapshot,
            snapshot: NetworkSnapshotModel?,
        ): BypassUsageSessionEntity {
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
            return session
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
            val terminalAdmission = networkTransitionSessionGate.beginQuiescing()
            val finalizedAt = maxOf(System.currentTimeMillis(), telemetry.updatedAt)
            var quiescingFinished = terminalAdmission == null
            var transitionFlushCancellation: CancellationException? = null
            try {
                val transitionFlushSucceeded =
                    try {
                        terminalAdmission?.let { sealNetworkTransitions(it) } ?: false
                    } catch (failure: CancellationException) {
                        transitionFlushCancellation = failure
                        false
                    }
                val quiescingWasClean =
                    terminalAdmission?.let(networkTransitionSessionGate::finishQuiescing) ?: false
                quiescingFinished = true
                val terminalEvidenceSealed = transitionFlushSucceeded && quiescingWasClean
                val finishedSession =
                    RuntimeUsageSessionBuilder.finalizeSession(
                        current = current,
                        telemetry = telemetry,
                        finalizedAt = finalizedAt,
                    )
                withContext(NonCancellable) {
                    val start =
                        TerminalOutboxStart(
                            activeSession = current,
                            finishedSession = finishedSession,
                            telemetry = telemetry,
                            createdAt = finalizedAt,
                            terminalEvidenceSealed = terminalEvidenceSealed,
                        )
                    pendingTerminalStart = start
                    activeUsageSession = null
                    lastRecordedNetworkHandoverState = null
                    pendingTerminalSession = terminalOutbox.begin(start)
                    if (pendingTerminalStart === start) pendingTerminalStart = null
                }
                transitionFlushCancellation?.let { throw it }
                persistPendingTerminalSession()
            } finally {
                if (!quiescingFinished && terminalAdmission != null) {
                    networkTransitionSessionGate.finishQuiescing(terminalAdmission)
                }
            }
        }

        private suspend fun persistPendingTerminalSession() {
            pendingTerminalStart?.let { start ->
                pendingTerminalSession = terminalOutbox.begin(start)
                if (pendingTerminalStart === start) pendingTerminalStart = null
            }
            pendingTerminalSession?.let { pending ->
                terminalOutbox.persist(pending)
                if (pendingTerminalSession === pending) pendingTerminalSession = null
            }
            check(
                terminalOutbox.recoverAll { recovered ->
                    pendingTerminalSession = recovered
                    terminalOutbox.persist(recovered)
                    if (pendingTerminalSession === recovered) pendingTerminalSession = null
                },
            ) { "Terminal outbox recovery remains incomplete" }
            if (terminalAssessmentReconciliationComplete) return
            val finishedSessions =
                bypassUsageHistoryStore
                    .observeBypassUsageSessions(limit = MaxTerminalAssessmentReconciliationSessions)
                    .first()
                    .filter { session -> session.finishedAt != null }
            finishedSessions.forEach { session ->
                if (!artifactPersister.hasTerminalRootCauseAssessment(session.id)) {
                    artifactPersister.persistTerminalRootCauseAssessment(
                        connectionSessionId = session.id,
                        createdAt = requireNotNull(session.finishedAt),
                        terminalEvidenceSealed = false,
                    )
                }
            }
            terminalAssessmentReconciliationComplete = true
        }

        private suspend fun sealNetworkTransitions(admission: NetworkTransitionAdmission): Boolean {
            val result = runCatching { networkTransitionFlush?.invoke(admission) ?: false }
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
private const val MaxTerminalAssessmentReconciliationSessions = 64
