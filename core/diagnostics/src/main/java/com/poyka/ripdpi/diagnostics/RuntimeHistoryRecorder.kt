package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
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
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val serviceStateStore: ServiceStateStore,
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
        val connectionSessionId =
            stateMutex.withLock {
                val current = activeUsageSession
                if (current == null) {
                    createFailedUsageSession(
                        sender = sender,
                        failureMessage = failureMessage,
                        timestamp = timestamp,
                    )
                } else {
                    val updated =
                        current.copy(
                            updatedAt = timestamp,
                            connectionState = "Failed",
                            health = "degraded",
                            endedReason = "failed:${sender.senderName.lowercase(Locale.US)}",
                            failureMessage = failureMessage,
                        )
                    activeUsageSession = updated
                    historyRepository.upsertBypassUsageSession(updated)
                    updated.id
                }
            }

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
            )
        activeUsageSession = session
        historyRepository.upsertBypassUsageSession(session)
    }

    private suspend fun createFailedUsageSession(
        sender: Sender,
        failureMessage: String,
        timestamp: Long,
    ): String {
        val settings = appSettingsRepository.snapshot()
        val profile =
            settings.diagnosticsActiveProfileId
                .takeIf { it.isNotBlank() }
                ?.let { historyRepository.getProfile(it) }
        val context = diagnosticsContextProvider.captureContext()
        val approach = createStoredApproachSnapshot(json, settings, profile, context)
        val snapshot = runCatching { networkMetadataProvider.captureSnapshot() }.getOrNull()
        val session =
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
                txBytes = serviceStateStore.telemetry.value.tunnelStats.txBytes,
                rxBytes = serviceStateStore.telemetry.value.tunnelStats.rxBytes,
                totalErrors = 0L,
                routeChanges = 0L,
                restartCount = context.service.restartCount,
                endedReason = "failed:${sender.senderName.lowercase(Locale.US)}",
                failureMessage = failureMessage,
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
            )
        activeUsageSession = updated
        historyRepository.upsertBypassUsageSession(updated)
    }

    private suspend fun finalizeActiveUsageSession(serviceTelemetry: ServiceTelemetrySnapshot) {
        val current = activeUsageSession ?: return
        val finalizedAt = maxOf(System.currentTimeMillis(), serviceTelemetry.updatedAt)
        historyRepository.upsertBypassUsageSession(
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
        )
        activeUsageSession = null
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
            TelemetrySampleEntity(
                id = UUID.randomUUID().toString(),
                sessionId = null,
                connectionSessionId = connectionSessionId,
                activeMode = serviceStateStore.status.value.second.name,
                connectionState = serviceStateStore.status.value.first.name,
                networkType = snapshot.transport,
                publicIp = snapshot.publicIp,
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
