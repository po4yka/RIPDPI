package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsFailureArtifactWriteStore
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
        private val failureArtifactWriteStore: DiagnosticsFailureArtifactWriteStore,
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
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Logger.w(failure) { "Failed to capture network snapshot" }
            }.getOrNull()

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
            serviceTelemetry
                .nativeRuntimeEventsForDiagnostics()
                .mapNotNull(NativeRuntimeEvent::toPrivacySafePersistedRuntimeEvent)
                .forEach { event ->
                    persistRuntimeEvent(
                        event.toSessionEvent(
                            id =
                                event.terminalDataPlaneEventId(connectionSessionId)
                                    ?: UUID.randomUUID().toString(),
                            connectionSessionId = connectionSessionId,
                        ),
                    )
                }
        }

        suspend fun persistTerminalRuntimeEvents(
            serviceTelemetry: ServiceTelemetrySnapshot,
            connectionSessionId: String,
        ) {
            persistTypedRuntimeHealthEvents(
                serviceTelemetry = serviceTelemetry,
                connectionSessionId = connectionSessionId,
            )
            serviceTelemetry
                .nativeRuntimeEventsForDiagnostics()
                .mapNotNull(NativeRuntimeEvent::toPrivacySafePersistedRuntimeEvent)
                .forEachIndexed { index, event ->
                    persistRuntimeEvent(
                        event.toSessionEvent(
                            id =
                                event.terminalDataPlaneEventId(connectionSessionId)
                                    ?: "$TerminalRuntimeEventIdPrefix:$connectionSessionId:$index",
                            connectionSessionId = connectionSessionId,
                        ),
                    )
                }
        }

        internal suspend fun prepareTerminalArtifactBatch(
            connectionSessionId: String,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            networkTypeFallback: String,
            includeTelemetrySample: Boolean,
        ): RuntimeTerminalArtifactBatch {
            val typedEvents =
                typedRuntimeHealthMutex.withLock {
                    val currentState =
                        typedRuntimeHealthByConnectionSessionId[connectionSessionId] ?: TypedRuntimeHealthState()
                    val transition = currentState.reduce(telemetry, connectionSessionId)
                    typedRuntimeHealthByConnectionSessionId[connectionSessionId] = transition.nextState
                    typedRuntimeHealthByConnectionSessionId.trimTrackedSessions()
                    transition.events
                }
            val telemetrySample =
                if (includeTelemetrySample) {
                    buildTelemetrySampleEntity(
                        id = "$TerminalTelemetrySampleIdPrefix:$connectionSessionId",
                        connectionSessionId = connectionSessionId,
                        networkType = networkTypeFallback,
                        publicIp = null,
                        telemetry = telemetry,
                        createdAt = createdAt,
                        connectionStateOverride = "Stopped",
                    )
                } else {
                    null
                }
            return buildPrivacySafeTerminalArtifactBatch(
                connectionSessionId = connectionSessionId,
                typedEvents = typedEvents,
                nativeEvents = telemetry.nativeRuntimeEventsForDiagnostics(),
                telemetrySample = telemetrySample,
            )
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
            usageSession: BypassUsageSessionEntity,
            sender: Sender,
            failureMessage: String,
            snapshot: NetworkSnapshotModel?,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            networkTypeFallback: String,
            publicIpFallback: String?,
        ) {
            val connectionSessionId = usageSession.id
            val context =
                runCatching { diagnosticsContextProvider.captureContext() }
                    .onFailure { failure ->
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        Logger.w(failure) { "Failed to capture diagnostic context" }
                    }.getOrNull()
            val event =
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
                )

            failureArtifactWriteStore.persistFailureArtifacts(
                usageSession = usageSession,
                snapshot =
                    snapshot?.let {
                        NetworkSnapshotEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            connectionSessionId = connectionSessionId,
                            snapshotKind = "failure",
                            payloadJson = RuntimeHistoryJson.encodeToString(NetworkSnapshotModel.serializer(), it),
                            capturedAt = createdAt,
                        )
                    },
                context =
                    context?.let {
                        DiagnosticContextEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            connectionSessionId = connectionSessionId,
                            contextKind = "failure",
                            payloadJson = RuntimeHistoryJson.encodeToString(DiagnosticContextModel.serializer(), it),
                            capturedAt = createdAt,
                        )
                    },
                telemetry =
                    buildTelemetrySampleEntity(
                        connectionSessionId = connectionSessionId,
                        networkType = snapshot?.transport ?: networkTypeFallback,
                        publicIp = snapshot?.publicIp ?: publicIpFallback,
                        telemetry = telemetry,
                        createdAt = createdAt,
                        connectionStateOverride = "Failed",
                    ),
                event = event,
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
                    id = "$TerminalTelemetrySampleIdPrefix:$connectionSessionId",
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
        ) {
            val assessment =
                prepareTerminalRootCauseAssessment(
                    connectionSessionId = connectionSessionId,
                    createdAt = createdAt,
                    terminalEvidenceSealed = terminalEvidenceSealed,
                    requireCanonicalDataPlaneFinal = requireCanonicalDataPlaneFinal,
                ) ?: return
            artifactWriteStore.insertNativeSessionEvent(assessment)
            markTerminalRootCauseAssessmentPersisted(connectionSessionId)
        }

        suspend fun prepareTerminalRootCauseAssessment(
            connectionSessionId: String,
            createdAt: Long,
            terminalEvidenceSealed: Boolean = false,
            requireCanonicalDataPlaneFinal: Boolean = true,
        ): NativeSessionEventEntity? =
            rootCauseAssessmentMutex.withLock {
                if (connectionSessionId in persistedRootCauseConnectionSessionIds) return@withLock null

                val persistedEvents =
                    artifactReadStore
                        .observeConnectionRootCauseEvents(
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
                NativeSessionEventEntity(
                    id = rootCauseAssessmentEventId(connectionSessionId),
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
                )
            }

        suspend fun markTerminalRootCauseAssessmentPersisted(connectionSessionId: String) =
            rootCauseAssessmentMutex.withLock {
                persistedRootCauseConnectionSessionIds.add(connectionSessionId)
                trimPersistedRootCauseSessionIds()
            }

        private suspend fun persistTypedRuntimeHealthEvents(
            serviceTelemetry: ServiceTelemetrySnapshot,
            connectionSessionId: String,
        ) = typedRuntimeHealthMutex.withLock {
            val currentState =
                typedRuntimeHealthByConnectionSessionId[connectionSessionId] ?: TypedRuntimeHealthState()
            val transition = currentState.reduce(serviceTelemetry, connectionSessionId)
            transition.events.forEach { event -> persistRuntimeEvent(event) }
            typedRuntimeHealthByConnectionSessionId[connectionSessionId] = transition.nextState
            typedRuntimeHealthByConnectionSessionId.trimTrackedSessions()
        }

        suspend fun hasTerminalRootCauseAssessment(connectionSessionId: String): Boolean =
            artifactReadStore.getNativeSessionEvent(rootCauseAssessmentEventId(connectionSessionId)) != null

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

                val persistenceFailure =
                    runCatching { artifactWriteStore.insertNativeSessionEvent(event) }
                        .exceptionOrNull()
                if (persistenceFailure == null) {
                    withContext(NonCancellable) {
                        eventKeysMutex.withLock {
                            persistedEventKeys.add(key)
                            persistedEventKeys.trimOldest(MaxPersistedEventKeys)
                            inFlightEventKeys.remove(key)?.complete(Unit)
                        }
                    }
                    recordRuntimeEvidenceEvent(event)
                    return
                } else {
                    withContext(NonCancellable) {
                        eventKeysMutex.withLock {
                            inFlightEventKeys.remove(key)?.complete(Unit)
                        }
                    }
                    throw persistenceFailure
                }
            }
        }

        private suspend fun recordRuntimeEvidenceEvent(event: NativeSessionEventEntity) {
            val connectionSessionId = event.connectionSessionId ?: return
            if (event.subsystem == RuntimeRootCauseAssessmentSubsystem) return
            runtimeEvidenceMutex.withLock {
                if (event.subsystem == "network_transition") {
                    val transitionEvents =
                        networkTransitionEventsByConnectionSessionId.getOrPut(connectionSessionId) {
                            ArrayDeque(MaxRuntimeRootCauseEventsPerSession)
                        }
                    if (transitionEvents.size >= MaxRuntimeRootCauseEventsPerSession) {
                        transitionEvents.removeFirst()
                    }
                    transitionEvents.removeAll { existing -> existing.id == event.id }
                    transitionEvents.addLast(event)
                    networkTransitionEventsByConnectionSessionId.trimTrackedSessions()
                    return@withLock
                }
                val events =
                    runtimeEventsByConnectionSessionId.getOrPut(connectionSessionId) {
                        ArrayDeque(MaxRuntimeRootCauseEventsPerSession)
                    }
                if (events.size >= MaxRuntimeRootCauseEventsPerSession) {
                    events.removeFirst()
                }
                events.removeAll { existing -> existing.id == event.id }
                events.addLast(event)
                runtimeEventsByConnectionSessionId.trimTrackedSessions()
            }
        }

        private fun buildTelemetrySampleEntity(
            id: String = UUID.randomUUID().toString(),
            connectionSessionId: String,
            networkType: String,
            publicIp: String?,
            telemetry: ServiceTelemetrySnapshot,
            createdAt: Long,
            connectionStateOverride: String? = null,
        ): TelemetrySampleEntity {
            val memory = nativeMemoryProbe.sample()
            return telemetry.toTelemetrySampleEntity(
                id = id,
                connectionSessionId = connectionSessionId,
                activeModeFallback = serviceStateStore.status.value.second.name,
                connectionStateOverride = connectionStateOverride,
                networkType = networkType,
                publicIp = publicIp,
                nativeHeapBytes = memory.nativeHeapBytes,
                processRssBytes = memory.processRssBytes,
                createdAt = createdAt,
            )
        }

        private fun trimPersistedRootCauseSessionIds() {
            persistedRootCauseConnectionSessionIds.trimOldest(MaxRuntimeRootCauseTrackedSessions)
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
        event.attemptId?.toString().orEmpty(),
        event.attemptSequence?.toString().orEmpty(),
        event.stage.orEmpty(),
        event.outcome.orEmpty(),
    ).joinToString(separator = "|")

private fun rootCauseAssessmentEventId(connectionSessionId: String): String =
    "$RuntimeRootCauseAssessmentSource:$connectionSessionId"

private fun NativeRuntimeEvent.terminalDataPlaneEventId(connectionSessionId: String?): String? =
    connectionSessionId
        ?.takeIf { kind == "data_plane_final" }
        ?.let(::terminalDataPlaneEventIdForSession)

private fun terminalDataPlaneEventIdForSession(connectionSessionId: String): String =
    "runtime_terminal_event:$connectionSessionId:data_plane_final"

private fun NativeRuntimeEvent.toSessionEvent(
    id: String,
    connectionSessionId: String?,
): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = id,
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = source,
        level = level,
        message = message.withPersistedEventKind(this),
        createdAt = createdAt,
        runtimeId = runtimeId,
        mode = mode,
        policySignature = policySignature,
        fingerprintHash = fingerprintHash,
        subsystem = subsystem,
        attemptId = attemptId,
        attemptSequence = attemptSequence,
        stage = stage,
        outcome = outcome,
        durationMs = durationMs,
        failureStage = failureStage,
        failureClass = failureClass,
        ioErrorKind = ioErrorKind,
        osErrorCode = osErrorCode,
        peerClosePhase = peerClosePhase,
        carrierDisposition = carrierDisposition,
    )

private fun <T> LinkedHashMap<String, T>.trimTrackedSessions() {
    while (size > MaxRuntimeRootCauseTrackedSessions) {
        val iterator = entries.iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

private fun <T> LinkedHashSet<T>.trimOldest(maxSize: Int) {
    while (size > maxSize) {
        val iterator = iterator()
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
private const val TerminalRuntimeEventIdPrefix = "runtime_terminal_event"
private const val TerminalTelemetrySampleIdPrefix = "runtime_terminal_sample"
private val ReservedEventKindToken = Regex("(?i)(^|[ ;])event_kind=[^ ;]*")
private val PersistedRuntimeEventKinds =
    setOf(
        "data_plane_correlation",
        "data_plane_counter_reset",
        "data_plane_final",
        "protect_failure",
        "runtime_ready",
        "runtime_stopped",
    )
