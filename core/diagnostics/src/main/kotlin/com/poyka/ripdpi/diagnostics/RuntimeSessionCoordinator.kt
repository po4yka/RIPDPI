package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.ApplicationIoScope
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
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
    ) {
        private val stateMutex = Mutex()

        private var activeUsageSession: BypassUsageSessionEntity? = null
        private var samplingJob: Job? = null

        suspend fun handleStatusChange(
            status: AppStatus,
            mode: Mode,
        ) {
            if (status == AppStatus.Running) {
                stateMutex.withLock {
                    ensureActiveUsageSession(mode = mode)
                }
                startSampling()
                return
            }

            stopSampling()
            stateMutex.withLock {
                finalizeActiveUsageSession(serviceStateStore.telemetry.value)
            }
        }

        suspend fun handleTelemetryUpdate(telemetry: ServiceTelemetrySnapshot) {
            val connectionSessionId =
                stateMutex.withLock {
                    if (serviceStateStore.status.value.first == AppStatus.Running) {
                        ensureActiveUsageSession(mode = serviceStateStore.status.value.second)
                        updateActiveUsageSession(
                            serviceMode = serviceStateStore.status.value.second,
                            telemetry = telemetry,
                            networkType = activeUsageSession?.networkType ?: "unknown",
                            publicIp = activeUsageSession?.publicIp,
                        )
                    }
                    activeUsageSession?.id
                }

            artifactPersister.persistRuntimeEvents(
                serviceTelemetry = telemetry,
                connectionSessionId = connectionSessionId,
            )
        }

        suspend fun handleFailure(
            sender: Sender,
            reason: FailureReason,
        ) {
            val timestamp = System.currentTimeMillis()
            val failureMessage = reason.displayMessage
            val telemetry = serviceStateStore.telemetry.value
            val snapshot = artifactPersister.captureSnapshotOrNull()
            val connectionSessionId =
                stateMutex.withLock {
                    val current = activeUsageSession
                    if (current == null) {
                        createFailedUsageSession(
                            sender = sender,
                            failureMessage = failureMessage,
                            timestamp = timestamp,
                            telemetry = telemetry,
                            snapshot = snapshot,
                        )
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
        }

        suspend fun handleActiveConnectionPolicyChange(policy: ActiveConnectionPolicy?) {
            stateMutex.withLock {
                if (serviceStateStore.status.value.first != AppStatus.Running) {
                    return
                }
                val session = activeUsageSession ?: return
                rememberedPolicySessionTracker.sync(session = session, activePolicy = policy)
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
            bypassUsageHistoryStore.upsertBypassUsageSession(session)
            rememberedPolicySessionTracker.sync(
                session = session,
                activePolicy = activeConnectionPolicyStore.current(mode),
            )
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
            val finalizedAt = maxOf(System.currentTimeMillis(), telemetry.updatedAt)
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
            activeUsageSession = null
            rememberedPolicySessionTracker.clear()
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
