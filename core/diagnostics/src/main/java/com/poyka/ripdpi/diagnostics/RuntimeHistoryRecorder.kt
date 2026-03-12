package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofDurationMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofTransferBytes
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceManualSession
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.services.ActiveConnectionPolicyStore
import com.poyka.ripdpi.services.DefaultActiveConnectionPolicyStore
import com.poyka.ripdpi.services.FailureReason
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceTelemetrySnapshot
import com.poyka.ripdpi.services.displayMessage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

interface RuntimeHistoryRecorder {
    fun start()
}

@Singleton
class DefaultRuntimeHistoryRecorder
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val historyRepository: DiagnosticsHistoryRepository,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
            DefaultRememberedNetworkPolicyStore(historyRepository),
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val serviceStateStore: ServiceStateStore,
        private val activeConnectionPolicyStore: ActiveConnectionPolicyStore = DefaultActiveConnectionPolicyStore(),
    ) : RuntimeHistoryRecorder {
        private companion object {
            private const val MaxPersistedEventKeys = 512
        }

        private val json = Json { ignoreUnknownKeys = true }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val started = AtomicBoolean(false)
        private val stateMutex = Mutex()
        private val persistedEventKeys = LinkedHashSet<String>()

        private var activeUsageSession: BypassUsageSessionEntity? = null
        private var activeRememberedPolicySession: ActiveRememberedPolicySession? = null
        private var samplingJob: Job? = null

        override fun start() {
            if (!started.compareAndSet(false, true)) {
                return
            }

            scope.launch {
                serviceStateStore.status.collectLatest { (status, mode) ->
                    handleStatusChange(status = status, mode = mode)
                }
            }

            scope.launch {
                serviceStateStore.telemetry.collectLatest { telemetry ->
                    handleTelemetryUpdate(telemetry)
                }
            }

            scope.launch {
                serviceStateStore.events.collectLatest { event ->
                    when (event) {
                        is ServiceEvent.Failed -> handleFailure(event.sender, event.reason)
                    }
                }
            }
        }

        private suspend fun handleStatusChange(
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

        private suspend fun handleTelemetryUpdate(telemetry: ServiceTelemetrySnapshot) {
            stateMutex.withLock {
                if (serviceStateStore.status.value.first == AppStatus.Running) {
                    ensureActiveUsageSession(mode = serviceStateStore.status.value.second)
                    updateActiveUsageSession(
                        serviceMode = serviceStateStore.status.value.second,
                        serviceTelemetry = telemetry,
                        networkType = activeUsageSession?.networkType ?: "unknown",
                        publicIp = activeUsageSession?.publicIp,
                    )
                }

                persistRuntimeEvents(
                    serviceTelemetry = telemetry,
                    connectionSessionId = activeUsageSession?.id,
                )
            }
        }

        private suspend fun handleFailure(
            sender: Sender,
            reason: FailureReason,
        ) {
            val timestamp = System.currentTimeMillis()
            val failureMessage = reason.displayMessage
            val telemetry = serviceStateStore.telemetry.value
            val snapshot = runCatching { networkMetadataProvider.captureSnapshot() }.getOrNull()
            val connectionSessionId =
                stateMutex.withLock {
                    val current = activeUsageSession
                    if (current == null) {
                        createFailedUsageSession(
                            sender = sender,
                            failureMessage = failureMessage,
                            timestamp = timestamp,
                            serviceTelemetry = telemetry,
                            snapshot = snapshot,
                        )
                    } else {
                        val updated =
                            applyRuntimeFieldTelemetry(
                                current.copy(
                                    updatedAt = timestamp,
                                    connectionState = "Failed",
                                    health = "degraded",
                                    networkType = snapshot?.transport ?: current.networkType,
                                    publicIp = snapshot?.publicIp ?: current.publicIp,
                                    txBytes = telemetry.tunnelStats.txBytes,
                                    rxBytes = telemetry.tunnelStats.rxBytes,
                                    totalErrors = telemetry.proxyTelemetry.totalErrors + telemetry.tunnelTelemetry.totalErrors,
                                    routeChanges = telemetry.proxyTelemetry.routeChanges + telemetry.tunnelTelemetry.routeChanges,
                                    restartCount = telemetry.restartCount,
                                    endedReason = "failed:${sender.senderName.lowercase(Locale.US)}",
                                    failureMessage = failureMessage,
                                ),
                                telemetry.runtimeFieldTelemetry,
                            )
                        activeUsageSession = updated
                        historyRepository.upsertBypassUsageSession(updated)
                        updated.id
                    }
                }

            persistTerminalTelemetrySample(
                connectionSessionId = connectionSessionId,
                snapshot = snapshot,
                telemetry = telemetry,
                createdAt = timestamp,
            )

            persistRuntimeEvent(
                event =
                    NativeSessionEventEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = null,
                        connectionSessionId = connectionSessionId,
                        source = sender.senderName.lowercase(Locale.US),
                        level = "error",
                        message = failureMessage,
                        createdAt = timestamp,
                    ),
            )
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

                    if (settings.diagnosticsMonitorEnabled && serviceStateStore.status.value.first == AppStatus.Running) {
                        persistSample(currentSessionId)
                        historyRepository.trimOldData(settings.diagnosticsHistoryRetentionDays)
                    }

                    delay(settings.diagnosticsSampleIntervalSeconds.coerceIn(5, 300) * 1_000L)
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

        val settings = appSettingsRepository.snapshot()
        val profile =
            settings.diagnosticsActiveProfileId
                .takeIf { it.isNotBlank() }
                ?.let { historyRepository.getProfile(it) }
        val context = diagnosticsContextProvider.captureContext()
        val approach = createStoredApproachSnapshot(json, settings, profile, context)
        val snapshot = runCatching { networkMetadataProvider.captureSnapshot() }.getOrNull()
        val telemetry = serviceStateStore.telemetry.value
        val startedAt = maxOf(System.currentTimeMillis(), telemetry.updatedAt)
        val session =
            applyRuntimeFieldTelemetry(
                BypassUsageSessionEntity(
                    id = UUID.randomUUID().toString(),
                    startedAt = startedAt,
                    finishedAt = null,
                    updatedAt = startedAt,
                    serviceMode = mode.name,
                    connectionState = AppStatus.Running.name,
                    health = deriveConnectionHealth(telemetry),
                    approachProfileId = approach.profileId,
                    approachProfileName = approach.profileName,
                    strategyId = approach.strategyId,
                    strategyLabel = approach.strategyLabel,
                    strategyJson = approach.strategyJson,
                    networkType = snapshot?.transport ?: "unknown",
                    publicIp = snapshot?.publicIp,
                    txBytes = telemetry.tunnelStats.txBytes,
                    rxBytes = telemetry.tunnelStats.rxBytes,
                    totalErrors = telemetry.proxyTelemetry.totalErrors + telemetry.tunnelTelemetry.totalErrors,
                    routeChanges = telemetry.proxyTelemetry.routeChanges + telemetry.tunnelTelemetry.routeChanges,
                    restartCount = context.service.restartCount,
                    endedReason = null,
                    failureMessage = null,
                ),
                telemetry.runtimeFieldTelemetry,
            )
        activeUsageSession = session
        historyRepository.upsertBypassUsageSession(session)
        ensureActiveRememberedPolicySession(session)
    }

    private suspend fun createFailedUsageSession(
        sender: Sender,
        failureMessage: String,
        timestamp: Long,
        serviceTelemetry: ServiceTelemetrySnapshot,
        snapshot: NetworkSnapshotModel?,
    ): String {
        val settings = appSettingsRepository.snapshot()
        val profile =
            settings.diagnosticsActiveProfileId
                .takeIf { it.isNotBlank() }
                ?.let { historyRepository.getProfile(it) }
        val context = diagnosticsContextProvider.captureContext()
        val approach = createStoredApproachSnapshot(json, settings, profile, context)
        val session =
            applyRuntimeFieldTelemetry(
                BypassUsageSessionEntity(
                    id = UUID.randomUUID().toString(),
                    startedAt = timestamp,
                    finishedAt = timestamp,
                    updatedAt = timestamp,
                    serviceMode = serviceStateStore.status.value.second.name,
                    connectionState = "Failed",
                    health = "degraded",
                    approachProfileId = approach.profileId,
                    approachProfileName = approach.profileName,
                    strategyId = approach.strategyId,
                    strategyLabel = approach.strategyLabel,
                    strategyJson = approach.strategyJson,
                    networkType = snapshot?.transport ?: "unknown",
                    publicIp = snapshot?.publicIp,
                    txBytes = serviceTelemetry.tunnelStats.txBytes,
                    rxBytes = serviceTelemetry.tunnelStats.rxBytes,
                    totalErrors = serviceTelemetry.proxyTelemetry.totalErrors + serviceTelemetry.tunnelTelemetry.totalErrors,
                    routeChanges = serviceTelemetry.proxyTelemetry.routeChanges + serviceTelemetry.tunnelTelemetry.routeChanges,
                    restartCount = context.service.restartCount,
                    endedReason = "failed:${sender.senderName.lowercase(Locale.US)}",
                    failureMessage = failureMessage,
                ),
                serviceTelemetry.runtimeFieldTelemetry,
            )
        historyRepository.upsertBypassUsageSession(session)
        return session.id
    }

    private suspend fun updateActiveUsageSession(
        serviceMode: Mode,
        serviceTelemetry: ServiceTelemetrySnapshot,
        networkType: String,
        publicIp: String?,
    ) {
        val current = activeUsageSession ?: return
        val updated =
            applyRuntimeFieldTelemetry(
                current.copy(
                    updatedAt = maxOf(System.currentTimeMillis(), serviceTelemetry.updatedAt),
                    serviceMode = serviceMode.name,
                    connectionState = serviceTelemetry.status.name,
                    health = deriveConnectionHealth(serviceTelemetry),
                    networkType = networkType,
                    publicIp = publicIp ?: current.publicIp,
                    txBytes = serviceTelemetry.tunnelStats.txBytes,
                    rxBytes = serviceTelemetry.tunnelStats.rxBytes,
                    totalErrors = serviceTelemetry.proxyTelemetry.totalErrors + serviceTelemetry.tunnelTelemetry.totalErrors,
                    routeChanges = serviceTelemetry.proxyTelemetry.routeChanges + serviceTelemetry.tunnelTelemetry.routeChanges,
                    restartCount = serviceTelemetry.restartCount,
                ),
                serviceTelemetry.runtimeFieldTelemetry,
            )
        activeUsageSession = updated
        historyRepository.upsertBypassUsageSession(updated)
    }

    private suspend fun finalizeActiveUsageSession(serviceTelemetry: ServiceTelemetrySnapshot) {
        val current = activeUsageSession ?: return
        val finalizedAt = maxOf(System.currentTimeMillis(), serviceTelemetry.updatedAt)
        val finishedSession =
            applyRuntimeFieldTelemetry(
                current.copy(
                    finishedAt = finalizedAt,
                    updatedAt = finalizedAt,
                    connectionState =
                        if (current.failureMessage.isNullOrBlank()) {
                            "Stopped"
                        } else {
                            "Failed"
                        },
                    health =
                        if (current.failureMessage.isNullOrBlank()) {
                            current.health
                        } else {
                            "degraded"
                        },
                    txBytes = serviceTelemetry.tunnelStats.txBytes,
                    rxBytes = serviceTelemetry.tunnelStats.rxBytes,
                    totalErrors = serviceTelemetry.proxyTelemetry.totalErrors + serviceTelemetry.tunnelTelemetry.totalErrors,
                    routeChanges = serviceTelemetry.proxyTelemetry.routeChanges + serviceTelemetry.tunnelTelemetry.routeChanges,
                    restartCount = serviceTelemetry.restartCount,
                    endedReason =
                        current.endedReason
                            ?: serviceTelemetry.lastFailureSender
                                ?.senderName
                                ?.lowercase(Locale.US)
                                ?.let { "failed:$it" }
                            ?: "stopped",
                ),
                serviceTelemetry.runtimeFieldTelemetry,
            )
        maybeFinalizeRememberedPolicySession(finishedSession, finalizedAt)
        historyRepository.upsertBypassUsageSession(
            finishedSession,
        )
        activeUsageSession = null
        activeRememberedPolicySession = null
    }

    private suspend fun persistSample(connectionSessionId: String) {
        val snapshot = runCatching { networkMetadataProvider.captureSnapshot() }.getOrNull() ?: return
        val context = diagnosticsContextProvider.captureContext()
        val telemetry = serviceStateStore.telemetry.value

        historyRepository.upsertSnapshot(
            NetworkSnapshotEntity(
                id = UUID.randomUUID().toString(),
                sessionId = null,
                connectionSessionId = connectionSessionId,
                snapshotKind = "connection_sample",
                payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), snapshot),
                capturedAt = snapshot.capturedAt,
            ),
        )
        historyRepository.upsertContextSnapshot(
            DiagnosticContextEntity(
                id = UUID.randomUUID().toString(),
                sessionId = null,
                connectionSessionId = connectionSessionId,
                contextKind = "connection_sample",
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), context),
                capturedAt = snapshot.capturedAt,
            ),
        )
        historyRepository.insertTelemetrySample(
            buildTelemetrySampleEntity(
                connectionSessionId = connectionSessionId,
                networkType = snapshot.transport,
                publicIp = snapshot.publicIp,
                telemetry = telemetry,
                createdAt = snapshot.capturedAt,
            ),
        )

        stateMutex.withLock {
            updateActiveUsageSession(
                serviceMode = serviceStateStore.status.value.second,
                serviceTelemetry = telemetry,
                networkType = snapshot.transport,
                publicIp = snapshot.publicIp,
            )
        }
    }

    private suspend fun persistRuntimeEvents(
        serviceTelemetry: ServiceTelemetrySnapshot,
        connectionSessionId: String?,
    ) {
        (serviceTelemetry.proxyTelemetry.nativeEvents + serviceTelemetry.tunnelTelemetry.nativeEvents)
            .forEach { event ->
                persistRuntimeEvent(
                    event =
                        NativeSessionEventEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            connectionSessionId = connectionSessionId,
                            source = event.source,
                            level = event.level,
                            message = event.message,
                            createdAt = event.createdAt,
                        ),
                )
            }
    }

    private suspend fun persistRuntimeEvent(event: NativeSessionEventEntity) {
        val key = "${event.source}|${event.level}|${event.message}|${event.createdAt}"
        if (!persistedEventKeys.add(key)) {
            return
        }
        trimPersistedEventKeys()
        historyRepository.insertNativeSessionEvent(event)
    }

        private suspend fun persistTerminalTelemetrySample(
            connectionSessionId: String,
            snapshot: NetworkSnapshotModel?,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
        ) {
            val networkType = snapshot?.transport ?: activeUsageSession?.networkType ?: "unknown"
            val publicIp = snapshot?.publicIp ?: activeUsageSession?.publicIp
            historyRepository.insertTelemetrySample(
                buildTelemetrySampleEntity(
                    connectionSessionId = connectionSessionId,
                    networkType = networkType,
                    publicIp = publicIp,
                    telemetry = telemetry,
                    createdAt = createdAt,
                    connectionStateOverride = "Failed",
                ),
            )
        }

        private fun buildTelemetrySampleEntity(
            connectionSessionId: String,
            networkType: String,
            publicIp: String?,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            connectionStateOverride: String? = null,
        ): TelemetrySampleEntity =
            TelemetrySampleEntity(
                id = UUID.randomUUID().toString(),
                sessionId = null,
                connectionSessionId = connectionSessionId,
                activeMode = telemetry.mode?.name ?: serviceStateStore.status.value.second.name,
                connectionState = connectionStateOverride ?: telemetry.status.name,
                networkType = networkType,
                publicIp = publicIp,
                failureClass = telemetry.runtimeFieldTelemetry.failureClass?.wireValue,
                telemetryNetworkFingerprintHash = telemetry.runtimeFieldTelemetry.telemetryNetworkFingerprintHash,
                winningTcpStrategyFamily = telemetry.runtimeFieldTelemetry.winningTcpStrategyFamily,
                winningQuicStrategyFamily = telemetry.runtimeFieldTelemetry.winningQuicStrategyFamily,
                proxyRttBand = telemetry.runtimeFieldTelemetry.proxyRttBand.wireValue,
                resolverRttBand = telemetry.runtimeFieldTelemetry.resolverRttBand.wireValue,
                proxyRouteRetryCount = telemetry.runtimeFieldTelemetry.proxyRouteRetryCount,
                tunnelRecoveryRetryCount = telemetry.runtimeFieldTelemetry.tunnelRecoveryRetryCount,
                resolverId = telemetry.tunnelTelemetry.resolverId,
                resolverProtocol = telemetry.tunnelTelemetry.resolverProtocol,
                resolverEndpoint = telemetry.tunnelTelemetry.resolverEndpoint,
                resolverLatencyMs = telemetry.tunnelTelemetry.resolverLatencyMs,
                dnsFailuresTotal = telemetry.tunnelTelemetry.dnsFailuresTotal,
                resolverFallbackActive = telemetry.tunnelTelemetry.resolverFallbackActive,
                resolverFallbackReason = telemetry.tunnelTelemetry.resolverFallbackReason,
                networkHandoverClass = telemetry.tunnelTelemetry.networkHandoverClass,
                txPackets = telemetry.tunnelStats.txPackets,
                txBytes = telemetry.tunnelStats.txBytes,
                rxPackets = telemetry.tunnelStats.rxPackets,
                rxBytes = telemetry.tunnelStats.rxBytes,
                createdAt = createdAt,
            )

        private fun applyRuntimeFieldTelemetry(
            session: BypassUsageSessionEntity,
            runtimeFieldTelemetry: com.poyka.ripdpi.services.RuntimeFieldTelemetry,
        ): BypassUsageSessionEntity =
            session.copy(
                failureClass = runtimeFieldTelemetry.failureClass?.wireValue,
                telemetryNetworkFingerprintHash = runtimeFieldTelemetry.telemetryNetworkFingerprintHash,
                winningTcpStrategyFamily = runtimeFieldTelemetry.winningTcpStrategyFamily,
                winningQuicStrategyFamily = runtimeFieldTelemetry.winningQuicStrategyFamily,
                proxyRttBand = runtimeFieldTelemetry.proxyRttBand.wireValue,
                resolverRttBand = runtimeFieldTelemetry.resolverRttBand.wireValue,
                proxyRouteRetryCount = runtimeFieldTelemetry.proxyRouteRetryCount,
                tunnelRecoveryRetryCount = runtimeFieldTelemetry.tunnelRecoveryRetryCount,
            )

    private fun trimPersistedEventKeys() {
        while (persistedEventKeys.size > MaxPersistedEventKeys) {
            val iterator = persistedEventKeys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun deriveConnectionHealth(serviceTelemetry: ServiceTelemetrySnapshot): String {
        val healths =
            listOf(
                serviceTelemetry.proxyTelemetry.health.lowercase(Locale.US),
                serviceTelemetry.tunnelTelemetry.health.lowercase(Locale.US),
            )
        return when {
            serviceTelemetry.lastFailureSender != null -> "degraded"
            healths.any { it == "degraded" } -> "degraded"
            healths.any { it == "healthy" } -> "healthy"
            serviceTelemetry.status == AppStatus.Running -> "active"
            else -> "idle"
        }
    }

    private suspend fun ensureActiveRememberedPolicySession(session: BypassUsageSessionEntity) {
        val activePolicy = activeConnectionPolicyStore.activePolicy.value ?: run {
            activeRememberedPolicySession = null
            return
        }
        val entity =
            if (activePolicy.usedRememberedPolicy) {
                activePolicy.matchedPolicy?.let { rememberedNetworkPolicyStore.recordApplied(it, session.startedAt) }
                    ?: return
            } else {
                rememberedNetworkPolicyStore.upsertObservedPolicy(
                    policy = activePolicy.policy.copy(strategySignatureJson = session.strategyJson),
                    source = RememberedNetworkPolicySourceManualSession,
                    now = session.startedAt,
                )
            }
        activeRememberedPolicySession =
            ActiveRememberedPolicySession(
                entity = entity,
                usedRememberedPolicy = activePolicy.usedRememberedPolicy,
                startedAt = session.startedAt,
            )
    }

    private suspend fun maybeFinalizeRememberedPolicySession(
        session: BypassUsageSessionEntity,
        finalizedAt: Long,
    ) {
        val rememberedPolicySession = activeRememberedPolicySession ?: return
        val transferBytes = session.txBytes + session.rxBytes
        val durationMs = finalizedAt - rememberedPolicySession.startedAt
        val proved =
            session.failureMessage.isNullOrBlank() &&
                durationMs >= RememberedNetworkPolicyProofDurationMs &&
                transferBytes >= RememberedNetworkPolicyProofTransferBytes
        val failed =
            !session.failureMessage.isNullOrBlank() ||
                session.endedReason?.startsWith("failed:") == true

        when {
            rememberedPolicySession.usedRememberedPolicy && failed && !proved ->
                rememberedNetworkPolicyStore.recordFailure(
                    policy = rememberedPolicySession.entity,
                    failedAt = finalizedAt,
                    allowSuppression = true,
                )

            rememberedPolicySession.usedRememberedPolicy && proved ->
                rememberedNetworkPolicyStore.recordSuccess(
                    policy = rememberedPolicySession.entity,
                    validated = true,
                    strategySignatureJson = session.strategyJson,
                    completedAt = finalizedAt,
                )

            !rememberedPolicySession.usedRememberedPolicy && proved ->
                rememberedNetworkPolicyStore.recordSuccess(
                    policy = rememberedPolicySession.entity,
                    validated = true,
                    strategySignatureJson = session.strategyJson,
                    completedAt = finalizedAt,
                )
        }
    }

    private data class ActiveRememberedPolicySession(
        val entity: RememberedNetworkPolicyEntity,
        val usedRememberedPolicy: Boolean,
        val startedAt: Long,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeHistoryRecorderModule {
    @Binds
    @Singleton
    abstract fun bindRuntimeHistoryRecorder(
        recorder: DefaultRuntimeHistoryRecorder,
    ): RuntimeHistoryRecorder
}
