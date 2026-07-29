package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRetentionStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.memory.NativeMemoryProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeArtifactPersister
    @Inject
    constructor(
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val historyRetentionStore: DiagnosticsHistoryRetentionStore,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val serviceStateStore: ServiceStateStore,
        private val nativeMemoryProbe: NativeMemoryProbe,
    ) {
        private val eventKeysMutex = Mutex()
        private val persistedEventKeys = LinkedHashSet<String>()
        private val inFlightEventKeys = LinkedHashMap<String, CompletableDeferred<Unit>>()
        private val runtimeEvidenceMutex = Mutex()
        private val rootCauseAssessmentMutex = Mutex()
        private val runtimeEventsByConnectionSessionId = LinkedHashMap<String, ArrayDeque<NativeSessionEventEntity>>()
        private val networkTransitionEventsByConnectionSessionId =
            LinkedHashMap<String, ArrayDeque<NativeSessionEventEntity>>()
        private val persistedRootCauseConnectionSessionIds = LinkedHashSet<String>()
        private val typedRuntimeHealthMutex = Mutex()
        private val typedRuntimeHealthByConnectionSessionId = LinkedHashMap<String, TypedRuntimeHealthState>()

        suspend fun captureSnapshotOrNull(): NetworkSnapshotModel? =
            runCatching {
                networkMetadataProvider.captureSnapshot()
            }.onFailure { Logger.w(it) { "Failed to capture network snapshot" } }.getOrNull()

        suspend fun persistConnectionSample(
            connectionSessionId: String,
            telemetry: ServiceTelemetrySnapshot,
        ): NetworkSnapshotModel? {
            val snapshot = captureSnapshotOrNull() ?: return null
            val context = diagnosticsContextProvider.captureContext()

            artifactWriteStore.upsertSnapshot(
                NetworkSnapshotEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    connectionSessionId = connectionSessionId,
                    snapshotKind = "connection_sample",
                    payloadJson = RuntimeHistoryJson.encodeToString(NetworkSnapshotModel.serializer(), snapshot),
                    capturedAt = snapshot.capturedAt,
                ),
            )
            artifactWriteStore.upsertContextSnapshot(
                DiagnosticContextEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    connectionSessionId = connectionSessionId,
                    contextKind = "connection_sample",
                    payloadJson = RuntimeHistoryJson.encodeToString(DiagnosticContextModel.serializer(), context),
                    capturedAt = snapshot.capturedAt,
                ),
            )
            artifactWriteStore.insertTelemetrySample(
                buildTelemetrySampleEntity(
                    connectionSessionId = connectionSessionId,
                    networkType = snapshot.transport,
                    publicIp = snapshot.publicIp,
                    telemetry = telemetry,
                    createdAt = snapshot.capturedAt,
                ),
            )
            return snapshot
        }

        suspend fun persistRuntimeEvents(
            serviceTelemetry: ServiceTelemetrySnapshot,
            connectionSessionId: String?,
        ) {
            connectionSessionId?.let { sessionId ->
                persistTypedRuntimeHealthEvents(
                    serviceTelemetry = serviceTelemetry,
                    connectionSessionId = sessionId,
                )
            }
            (serviceTelemetry.proxyTelemetry.nativeEvents + serviceTelemetry.tunnelTelemetry.nativeEvents)
                .forEach { event ->
                    persistRuntimeEvent(
                        NativeSessionEventEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            connectionSessionId = connectionSessionId,
                            source = event.source,
                            level = event.level,
                            message = event.message.withPersistedEventKind(event),
                            createdAt = event.createdAt,
                            runtimeId = event.runtimeId,
                            mode = event.mode,
                            policySignature = event.policySignature,
                            fingerprintHash = event.fingerprintHash,
                            subsystem = event.subsystem,
                        ),
                    )
                }
        }

        internal suspend fun persistNetworkTransition(
            event: NetworkTransitionEvent,
            connectionSessionId: String,
        ) {
            persistRuntimeEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    connectionSessionId = connectionSessionId,
                    source = "android_network_callback",
                    level = "info",
                    message = event.toRedactedMessage(),
                    createdAt = event.occurredAtEpochMs,
                    subsystem = "network_transition",
                ),
            )
        }

        suspend fun persistFailureArtifacts(
            connectionSessionId: String,
            sender: Sender,
            failureMessage: String,
            snapshot: NetworkSnapshotModel?,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            networkTypeFallback: String,
            publicIpFallback: String?,
        ) {
            artifactWriteStore.insertTelemetrySample(
                buildTelemetrySampleEntity(
                    connectionSessionId = connectionSessionId,
                    networkType = snapshot?.transport ?: networkTypeFallback,
                    publicIp = snapshot?.publicIp ?: publicIpFallback,
                    telemetry = telemetry,
                    createdAt = createdAt,
                    connectionStateOverride = "Failed",
                ),
            )

            persistRuntimeEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    connectionSessionId = connectionSessionId,
                    source = sender.senderName.lowercase(Locale.US),
                    level = "error",
                    message = failureMessage,
                    createdAt = createdAt,
                    mode = telemetry.mode?.name?.lowercase(Locale.US),
                    subsystem = "service",
                ),
            )
        }

        suspend fun persistTerminalTelemetrySample(
            connectionSessionId: String,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            networkTypeFallback: String,
            publicIpFallback: String?,
            connectionState: String,
        ) {
            artifactWriteStore.insertTelemetrySample(
                buildTelemetrySampleEntity(
                    connectionSessionId = connectionSessionId,
                    networkType = networkTypeFallback,
                    publicIp = publicIpFallback,
                    telemetry = telemetry,
                    createdAt = createdAt,
                    connectionStateOverride = connectionState,
                ),
            )
        }

        suspend fun persistTerminalRootCauseAssessment(
            connectionSessionId: String,
            createdAt: Long,
            terminalEvidenceSealed: Boolean = false,
            requireCanonicalDataPlaneFinal: Boolean = true,
        ) = rootCauseAssessmentMutex.withLock {
            if (connectionSessionId in persistedRootCauseConnectionSessionIds) return@withLock

            val persistedEvents =
                artifactReadStore
                    .observeConnectionNativeEvents(
                        connectionSessionId = connectionSessionId,
                        limit = MaxRuntimeRootCauseEventsPerSession,
                    ).first()
            val persistedNetworkTransitionEvents =
                artifactReadStore
                    .observeConnectionNetworkTransitionEvents(connectionSessionId)
                    .first()
            val fallbackEvents =
                runtimeEvidenceMutex.withLock {
                    runtimeEventsByConnectionSessionId[connectionSessionId]?.toList().orEmpty()
                }
            val fallbackNetworkTransitionEvents =
                runtimeEvidenceMutex.withLock {
                    networkTransitionEventsByConnectionSessionId[connectionSessionId]?.toList().orEmpty()
                }
            val assessment =
                RuntimeRootCauseClassifier.assess(
                    connectionSessionId = connectionSessionId,
                    events = persistedEvents.ifEmpty { fallbackEvents },
                    networkTransitionEvents =
                        persistedNetworkTransitionEvents.ifEmpty { fallbackNetworkTransitionEvents },
                    terminalAtMillis = createdAt,
                    terminalEvidenceSealed =
                        terminalEvidenceSealed &&
                            (
                                !requireCanonicalDataPlaneFinal ||
                                    persistedEvents
                                        .ifEmpty { fallbackEvents }
                                        .hasCanonicalDataPlaneFinalEvent()
                            ),
                )
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = "$RuntimeRootCauseAssessmentSource:$connectionSessionId",
                    sessionId = null,
                    connectionSessionId = connectionSessionId,
                    source = RuntimeRootCauseAssessmentSource,
                    level =
                        if (assessment.verdict == RuntimeRootCauseVerdict.INCONCLUSIVE) {
                            "info"
                        } else {
                            "warn"
                        },
                    message =
                        "runtime_root_cause_assessment " +
                            RuntimeHistoryJson.encodeToString(
                                RuntimeRootCauseAssessment.serializer(),
                                assessment,
                            ),
                    createdAt = createdAt,
                    subsystem = RuntimeRootCauseAssessmentSubsystem,
                ),
            )
            persistedRootCauseConnectionSessionIds.add(connectionSessionId)
            trimPersistedRootCauseSessionIds()
        }

        private suspend fun persistTypedRuntimeHealthEvents(
            serviceTelemetry: ServiceTelemetrySnapshot,
            connectionSessionId: String,
        ) {
            val events =
                typedRuntimeHealthMutex.withLock {
                    val state =
                        typedRuntimeHealthByConnectionSessionId.getOrPut(connectionSessionId) {
                            TypedRuntimeHealthState()
                        }
                    buildList {
                        val dnsEvent =
                            state.acceptDnsCounters(
                                counters = selectDnsCounterSource(serviceTelemetry),
                                connectionSessionId = connectionSessionId,
                                createdAt = serviceTelemetry.updatedAt,
                            )
                        if (dnsEvent != null) add(dnsEvent)
                        val relayEvent =
                            state.acceptRelayHealth(serviceTelemetry, connectionSessionId, serviceTelemetry.updatedAt)
                        if (relayEvent != null) add(relayEvent)
                    }.also {
                        trimTypedRuntimeHealthSessions()
                    }
                }
            events.forEach { event -> persistRuntimeEvent(event) }
        }

        suspend fun trimHistory(retentionDays: Int) {
            historyRetentionStore.trimOldData(retentionDays)
        }

        private suspend fun persistRuntimeEvent(event: NativeSessionEventEntity) {
            val key = runtimeEventDedupeKey(event)
            while (true) {
                val inFlight =
                    eventKeysMutex.withLock {
                        if (key in persistedEventKeys) return
                        val existing = inFlightEventKeys[key]
                        if (existing != null) {
                            existing
                        } else {
                            inFlightEventKeys[key] = CompletableDeferred()
                            null
                        }
                    }
                if (inFlight != null) {
                    inFlight.await()
                    continue
                }

                try {
                    artifactWriteStore.insertNativeSessionEvent(event)
                    withContext(NonCancellable) {
                        eventKeysMutex.withLock {
                            persistedEventKeys.add(key)
                            trimPersistedEventKeys()
                            inFlightEventKeys.remove(key)?.complete(Unit)
                        }
                    }
                    recordRuntimeEvidenceEvent(event)
                    return
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        eventKeysMutex.withLock {
                            inFlightEventKeys.remove(key)?.complete(Unit)
                        }
                    }
                    throw error
                }
            }
        }

        private fun runtimeEventDedupeKey(event: NativeSessionEventEntity): String =
            listOf(
                event.connectionSessionId.orEmpty(),
                event.sessionId.orEmpty(),
                event.source,
                event.level,
                event.subsystem.orEmpty(),
                event.runtimeId.orEmpty(),
                event.mode.orEmpty(),
                event.policySignature.orEmpty(),
                event.fingerprintHash.orEmpty(),
                event.message,
                event.createdAt.toString(),
            ).joinToString(separator = "|")

        private suspend fun recordRuntimeEvidenceEvent(event: NativeSessionEventEntity) {
            val connectionSessionId = event.connectionSessionId ?: return
            if (event.subsystem == RuntimeRootCauseAssessmentSubsystem) return
            runtimeEvidenceMutex.withLock {
                val events =
                    runtimeEventsByConnectionSessionId.getOrPut(connectionSessionId) {
                        ArrayDeque(MaxRuntimeRootCauseEventsPerSession)
                    }
                if (events.size >= MaxRuntimeRootCauseEventsPerSession) {
                    events.removeFirst()
                }
                events.removeAll { existing -> existing.id == event.id }
                events.addLast(event)
                if (event.subsystem == "network_transition") {
                    val transitionEvents =
                        networkTransitionEventsByConnectionSessionId.getOrPut(connectionSessionId) {
                            ArrayDeque(MaxRuntimeRootCauseEventsPerSession)
                        }
                    if (transitionEvents.size >= MaxRuntimeRootCauseEventsPerSession) {
                        transitionEvents.removeFirst()
                    }
                    transitionEvents.addLast(event)
                }
                runtimeEventsByConnectionSessionId.trimTrackedSessions()
                networkTransitionEventsByConnectionSessionId.trimTrackedSessions()
            }
        }

        private fun buildTelemetrySampleEntity(
            connectionSessionId: String,
            networkType: String,
            publicIp: String?,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            connectionStateOverride: String? = null,
        ): TelemetrySampleEntity {
            val memory = nativeMemoryProbe.sample()
            return TelemetrySampleEntity(
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
                networkHandoverState = telemetry.networkHandoverState,
                proxyTelemetryState = telemetry.proxyTelemetryStatus.state.wireValue,
                proxyTelemetryMessage = telemetry.proxyTelemetryStatus.message,
                relayTelemetryState = telemetry.relayTelemetryStatus.state.wireValue,
                relayTelemetryMessage = telemetry.relayTelemetryStatus.message,
                warpTelemetryState = telemetry.warpTelemetryStatus.state.wireValue,
                warpTelemetryMessage = telemetry.warpTelemetryStatus.message,
                tunnelTelemetryState = telemetry.tunnelTelemetryStatus.state.wireValue,
                tunnelTelemetryMessage = telemetry.tunnelTelemetryStatus.message,
                lastFailureClass = telemetry.proxyTelemetry.lastFailureClass,
                lastFallbackAction = telemetry.proxyTelemetry.lastFallbackAction,
                txPackets = telemetry.tunnelStats.txPackets,
                txBytes = telemetry.tunnelStats.txBytes,
                rxPackets = telemetry.tunnelStats.rxPackets,
                rxBytes = telemetry.tunnelStats.rxBytes,
                nativeHeapBytes = memory.nativeHeapBytes,
                processRssBytes = memory.processRssBytes,
                relayProtocolKind = telemetry.relayTelemetry.protocolKind,
                createdAt = createdAt,
            )
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

        private fun trimPersistedRootCauseSessionIds() {
            while (persistedRootCauseConnectionSessionIds.size > MaxRuntimeRootCauseTrackedSessions) {
                val iterator = persistedRootCauseConnectionSessionIds.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }

        private fun trimTypedRuntimeHealthSessions() {
            while (typedRuntimeHealthByConnectionSessionId.size > MaxRuntimeRootCauseTrackedSessions) {
                val iterator = typedRuntimeHealthByConnectionSessionId.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

private class TypedRuntimeHealthState {
    private var dnsBaseline: DnsCounterSnapshot? = null
    private var dnsFailureStreak = 0
    private var dnsFailureActive = false
    private var relayFailureActive = false

    fun acceptDnsCounters(
        counters: DnsCounterSnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity? {
        val baseline = dnsBaseline
        if (baseline == null) {
            dnsBaseline = counters
            dnsFailureStreak = 0
            dnsFailureActive = false
            return null
        }
        if (counters.hasDifferentProducerThan(baseline)) {
            val wasFailureActive = dnsFailureActive
            dnsBaseline = counters
            dnsFailureStreak = 0
            return recoverDns(connectionSessionId, createdAt).takeIf { wasFailureActive }
        }
        if (counters.hasRollbackFrom(baseline)) {
            val wasFailureActive = dnsFailureActive
            dnsBaseline = counters
            dnsFailureStreak = 0
            return recoverDns(connectionSessionId, createdAt).takeIf { wasFailureActive }
        }

        val failureDelta = (counters.failuresTotal - baseline.failuresTotal).coerceAtLeast(0)
        val successDelta = (counters.queriesTotal - baseline.queriesTotal - failureDelta).coerceAtLeast(0)
        dnsBaseline = counters
        return when {
            successDelta > 0 -> {
                val wasFailureActive = dnsFailureActive
                dnsFailureStreak = 0
                recoverDns(connectionSessionId, createdAt).takeIf { wasFailureActive }
            }

            failureDelta > 0 -> {
                dnsFailureStreak += 1
                if (dnsFailureStreak >= DnsRuntimeFailureThreshold) {
                    dnsFailureActive = true
                    dnsRuntimeStateEvent(
                        connectionSessionId = connectionSessionId,
                        createdAt = createdAt,
                        state = "failure_threshold",
                        level = "warn",
                    )
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
    }

    fun acceptRelayHealth(
        serviceTelemetry: ServiceTelemetrySnapshot,
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity? {
        val relayFailed = serviceTelemetry.hasRelayRuntimeFailure()
        return when {
            relayFailed -> {
                relayFailureActive = true
                relayRuntimeStateEvent(
                    connectionSessionId = connectionSessionId,
                    createdAt = createdAt,
                    relaySnapshot = serviceTelemetry.relayTelemetry,
                    relayFailed = true,
                    level = "warn",
                )
            }

            relayFailureActive -> {
                relayFailureActive = false
                relayRuntimeStateEvent(
                    connectionSessionId = connectionSessionId,
                    createdAt = createdAt,
                    relaySnapshot = serviceTelemetry.relayTelemetry,
                    relayFailed = false,
                    level = "info",
                )
            }

            else -> {
                null
            }
        }
    }

    private fun recoverDns(
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity {
        dnsFailureActive = false
        return dnsRuntimeStateEvent(
            connectionSessionId = connectionSessionId,
            createdAt = createdAt,
            state = "recovered",
            level = "info",
        )
    }
}

private data class DnsCounterSnapshot(
    val producer: DnsCounterProducer,
    val queriesTotal: Long,
    val failuresTotal: Long,
) {
    fun hasDifferentProducerThan(previous: DnsCounterSnapshot): Boolean = producer != previous.producer

    fun hasRollbackFrom(previous: DnsCounterSnapshot): Boolean =
        queriesTotal < previous.queriesTotal || failuresTotal < previous.failuresTotal
}

private data class DnsCounterProducer(
    val source: String,
    val serviceStartedAt: Long?,
    val restartCount: Int,
)

private fun selectDnsCounterSource(telemetry: ServiceTelemetrySnapshot): DnsCounterSnapshot {
    val proxy = telemetry.proxyTelemetry
    val tunnel = telemetry.tunnelTelemetry
    val source = if (tunnel.dnsQueriesTotal >= proxy.dnsQueriesTotal) tunnel else proxy
    return DnsCounterSnapshot(
        producer =
            DnsCounterProducer(
                source = source.source,
                serviceStartedAt = telemetry.serviceStartedAt,
                restartCount = telemetry.restartCount,
            ),
        queriesTotal = source.dnsQueriesTotal.coerceAtLeast(0),
        failuresTotal = source.dnsFailuresTotal.coerceAtLeast(0),
    )
}

private fun dnsRuntimeStateEvent(
    connectionSessionId: String,
    createdAt: Long,
    state: String,
    level: String,
): NativeSessionEventEntity =
    typedRuntimeStateEvent(
        id = "typed_runtime_state:dns:$connectionSessionId",
        connectionSessionId = connectionSessionId,
        level = level,
        message = "event=dns_runtime_state evidence=dns_counter_transition_v1 state=$state",
        createdAt = createdAt,
        subsystem = "dns",
    )

private fun relayRuntimeStateEvent(
    connectionSessionId: String,
    createdAt: Long,
    relaySnapshot: NativeRuntimeSnapshot,
    relayFailed: Boolean,
    level: String,
): NativeSessionEventEntity =
    typedRuntimeStateEvent(
        id = "typed_runtime_state:relay:$connectionSessionId",
        connectionSessionId = connectionSessionId,
        level = level,
        message =
            "event=relay_runtime_state evidence=relay_health_transition_v1 " +
                "state=${relaySnapshot.state.toRelayRuntimeCategory(RelayRuntimeStates)} " +
                "health=${relaySnapshot.health.toRelayRuntimeCategory(RelayRuntimeHealthValues)} " +
                "relay_failed=$relayFailed",
        createdAt = createdAt,
        subsystem = "relay",
    )

private fun typedRuntimeStateEvent(
    id: String,
    connectionSessionId: String,
    level: String,
    message: String,
    createdAt: Long,
    subsystem: String,
): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = "service_telemetry_state",
        level = level,
        message = message,
        createdAt = createdAt,
        subsystem = subsystem,
    )

private fun ServiceTelemetrySnapshot.hasRelayRuntimeFailure(): Boolean =
    relayTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
        relayTelemetry.state.lowercase(Locale.US) in RelayRuntimeFailureStates ||
        relayTelemetry.health.lowercase(Locale.US) in RelayRuntimeFailureHealthValues

private fun String.toRelayRuntimeCategory(allowedValues: Set<String>): String {
    val normalized = lowercase(Locale.US).replace('-', '_')
    return normalized.takeIf(allowedValues::contains) ?: "unknown"
}

private fun <T> LinkedHashMap<String, T>.trimTrackedSessions() {
    while (size > MaxRuntimeRootCauseTrackedSessions) {
        val iterator = entries.iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

private fun String.withPersistedEventKind(event: NativeRuntimeEvent): String {
    val sanitizedMessage = ReservedEventKindToken.replace(this) { match -> match.groupValues[1] }.trim()
    val kind = event.kind?.takeIf(PersistedRuntimeEventKinds::contains) ?: return sanitizedMessage
    return "$sanitizedMessage event_kind=$kind"
}

private const val MaxPersistedEventKeys = 512
private const val MaxRuntimeRootCauseEventsPerSession = 64
private const val MaxRuntimeRootCauseTrackedSessions = 64
private const val DnsRuntimeFailureThreshold = 2
private val ReservedEventKindToken = Regex("(?i)(^|[ ;])event_kind=[^ ;]*")
private val PersistedRuntimeEventKinds =
    setOf(
        "data_plane_correlation",
        "data_plane_counter_reset",
        "data_plane_final",
        "protect_failure",
    )
private val RelayRuntimeStates =
    setOf("idle", "starting", "running", "stopping", "stopped", "degraded", "failed", "error", "unknown")
private val RelayRuntimeHealthValues =
    setOf("idle", "ok", "healthy", "degraded", "failed", "error", "unknown")
private val RelayRuntimeFailureStates = setOf("failed", "error")
private val RelayRuntimeFailureHealthValues = setOf("failed", "error")
