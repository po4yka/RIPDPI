package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val runtimeEvidenceMutex = Mutex()
        private val runtimeEventsByConnectionSessionId = LinkedHashMap<String, ArrayDeque<NativeSessionEventEntity>>()
        private val persistedRootCauseConnectionSessionIds = LinkedHashSet<String>()

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
            (serviceTelemetry.proxyTelemetry.nativeEvents + serviceTelemetry.tunnelTelemetry.nativeEvents)
                .forEach { event ->
                    persistRuntimeEvent(
                        NativeSessionEventEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            connectionSessionId = connectionSessionId,
                            source = event.source,
                            level = event.level,
                            message = event.message,
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
        ) {
            runtimeEvidenceMutex.withLock {
                if (!persistedRootCauseConnectionSessionIds.add(connectionSessionId)) {
                    return
                }
                trimPersistedRootCauseSessionIds()
            }
            val persistedEvents =
                artifactReadStore
                    .observeConnectionNativeEvents(
                        connectionSessionId = connectionSessionId,
                        limit = MaxRuntimeRootCauseEventsPerSession,
                    ).first()
            val fallbackEvents =
                runtimeEvidenceMutex.withLock {
                    runtimeEventsByConnectionSessionId[connectionSessionId]?.toList().orEmpty()
                }
            val assessment =
                RuntimeRootCauseClassifier.assess(
                    connectionSessionId = connectionSessionId,
                    events = persistedEvents.ifEmpty { fallbackEvents },
                    terminalAtMillis = createdAt,
                )
            persistRuntimeEvent(
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
                            RuntimeHistoryJson.encodeToString(RuntimeRootCauseAssessment.serializer(), assessment),
                    createdAt = createdAt,
                    subsystem = RuntimeRootCauseAssessmentSubsystem,
                ),
            )
        }

        suspend fun trimHistory(retentionDays: Int) {
            historyRetentionStore.trimOldData(retentionDays)
        }

        private suspend fun persistRuntimeEvent(event: NativeSessionEventEntity) {
            val key = "${event.source}|${event.level}|${event.message}|${event.createdAt}"
            eventKeysMutex.withLock {
                if (!persistedEventKeys.add(key)) {
                    return
                }
                trimPersistedEventKeys()
            }
            artifactWriteStore.insertNativeSessionEvent(event)
            recordRuntimeEvidenceEvent(event)
        }

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
                events.addLast(event)
                while (runtimeEventsByConnectionSessionId.size > MaxRuntimeRootCauseTrackedSessions) {
                    val iterator = runtimeEventsByConnectionSessionId.entries.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
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
    }

private const val MaxPersistedEventKeys = 512
private const val MaxRuntimeRootCauseEventsPerSession = 64
private const val MaxRuntimeRootCauseTrackedSessions = 64
