package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTerminalOutboxStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.TerminalOutboxDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.TerminalPolicyDependencyDurableStatePrefix
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class RuntimeTerminalOutbox(
    private val usageHistoryStore: BypassUsageHistoryStore,
    private val outboxStore: DiagnosticsTerminalOutboxStore,
    private val policyRecordStore: RememberedNetworkPolicyRecordStore,
    private val artifactPersister: RuntimeArtifactPersister,
    private val rememberedPolicySessionTracker: RememberedPolicySessionTracker,
) {
    suspend fun begin(start: TerminalOutboxStart): PendingTerminalSession {
        val policyOutcome =
            rememberedPolicySessionTracker.prepareTerminalOutcome(start.finishedSession, start.createdAt)
        val artifactBatch =
            start.artifactBatch ?: artifactPersister
                .prepareTerminalArtifactBatch(
                    connectionSessionId = start.activeSession.id,
                    telemetry = start.telemetry,
                    createdAt = start.createdAt,
                    networkTypeFallback = start.activeSession.networkType,
                    includeTelemetrySample = start.activeSession.failureMessage.isNullOrBlank(),
                ).also { prepared -> start.artifactBatch = prepared }
        val pending =
            PendingTerminalSession(
                activeSession = start.activeSession,
                finishedSession = start.finishedSession,
                telemetry = start.telemetry,
                createdAt = start.createdAt,
                terminalEvidenceSealed = start.terminalEvidenceSealed,
                policyOutcome = policyOutcome,
                artifactBatch = artifactBatch,
            )
        val marker =
            outboxStore.beginTerminalOutbox(
                finishedSession = start.finishedSession,
                marker = pending.toMarker(PendingTerminalPhase.RUNTIME_EVENTS),
                policyDependency = pending.toPolicyDependency(),
            )
        return if (marker.value == pending.toMarker(PendingTerminalPhase.RUNTIME_EVENTS).value) {
            pending.apply { currentMarker = marker }
        } else {
            try {
                recover(marker)
            } catch (_: TerminalOutboxRecoveryException) {
                outboxStore.completeTerminalOutbox(marker)
                begin(start)
            }
        }
    }

    suspend fun recoverAll(onRecovered: suspend (PendingTerminalSession) -> Unit) {
        repeat(MaxTerminalOutboxRecoveryBatches) {
            val markers = outboxStore.getPendingTerminalOutboxes(TerminalOutboxRecoveryBatchSize)
            if (markers.isEmpty()) return
            markers.forEach { marker ->
                try {
                    onRecovered(recover(marker))
                } catch (_: TerminalOutboxRecoveryException) {
                    outboxStore.completeTerminalOutbox(marker)
                }
            }
            val madeProgress =
                markers.any { marker ->
                    outboxStore.getTerminalOutbox(marker.key)?.value != marker.value
                }
            if (!madeProgress) return
        }
    }

    private suspend fun recover(marker: DiagnosticsDurableStateEntity): PendingTerminalSession {
        val outbox = decodeTerminalOutboxMarker(marker.value)
        requireTerminalOutboxState(
            marker.key != "$TerminalOutboxDurableStatePrefix${outbox.connectionSessionId}" ||
                marker.updatedAt != outbox.createdAt,
        )
        val finishedSession =
            requireTerminalOutboxValue(usageHistoryStore.getBypassUsageSession(outbox.connectionSessionId))
        requireTerminalOutboxState(finishedSession.finishedAt != outbox.createdAt)
        return PendingTerminalSession(
            activeSession = finishedSession,
            finishedSession = finishedSession,
            telemetry = null,
            createdAt = outbox.createdAt,
            terminalEvidenceSealed = outbox.terminalEvidenceSealed,
            hasTransientState = true,
            policyOutcome = outbox.policyOutcome,
            policyEvidenceComplete = outbox.policyEvidenceComplete,
            artifactBatch = outbox.artifactBatch,
            currentMarker = marker,
            phase = outbox.phase,
        )
    }

    suspend fun persist(pending: PendingTerminalSession) {
        while (pending.phase != PendingTerminalPhase.COMPLETE) {
            when (pending.phase) {
                PendingTerminalPhase.RUNTIME_EVENTS -> persistRuntimeEvents(pending)
                PendingTerminalPhase.TERMINAL_SAMPLE -> persistTerminalSample(pending)
                PendingTerminalPhase.POLICY_FINALIZATION -> finalizeRememberedPolicy(pending)
                PendingTerminalPhase.SESSION_UPSERT -> persistFinishedSession(pending)
                PendingTerminalPhase.ROOT_CAUSE_ASSESSMENT -> persistRootCauseAssessment(pending)
                PendingTerminalPhase.COMPLETE -> continue
            }
        }
    }

    private suspend fun persistRuntimeEvents(pending: PendingTerminalSession) {
        checkpointArtifacts(
            pending = pending,
            events = pending.artifactBatch.events,
            telemetrySample = null,
            nextPhase = PendingTerminalPhase.TERMINAL_SAMPLE,
        )
    }

    private suspend fun persistTerminalSample(pending: PendingTerminalSession) {
        checkpointArtifacts(
            pending = pending,
            events = emptyList(),
            telemetrySample = pending.artifactBatch.telemetrySample,
            nextPhase = PendingTerminalPhase.POLICY_FINALIZATION,
        )
    }

    private suspend fun finalizeRememberedPolicy(pending: PendingTerminalSession) {
        val nextPhase = PendingTerminalPhase.SESSION_UPSERT
        val policy = pending.policyOutcome?.let { outcome -> reconstructPolicy(outcome, pending.finishedSession) }
        if (pending.policyOutcome != null && policy == null) {
            pending.policyEvidenceComplete = false
        }
        val policyCheckpoint = pending.toMarker(PendingTerminalPhase.POLICY_FINALIZATION)
        reconcileCheckpoint(
            pending = pending,
            intendedMarker = policyCheckpoint,
            checkpointSucceeded =
                outboxStore.checkpointTerminalPolicy(
                    policy = policy,
                    expectedMarker = pending.currentMarker,
                    replacementMarker = policyCheckpoint,
                ),
        )
        if (pending.phase != PendingTerminalPhase.POLICY_FINALIZATION) {
            rememberedPolicySessionTracker.clear()
            return
        }
        rememberedPolicySessionTracker.publishTerminalOutcome(pending.policyOutcome, policy)
        val replacement = pending.toMarker(nextPhase)
        reconcileCheckpoint(
            pending = pending,
            intendedMarker = replacement,
            checkpointSucceeded =
                outboxStore.checkpointTerminalOutbox(
                    expectedMarker = pending.currentMarker,
                    replacementMarker = replacement,
                ),
        )
        rememberedPolicySessionTracker.clear()
    }

    private suspend fun reconstructPolicy(
        outcome: RememberedPolicyTerminalOutcome,
        finishedSession: BypassUsageSessionEntity,
    ): RememberedNetworkPolicyEntity? =
        policyRecordStore
            .getRememberedNetworkPolicyById(outcome.policyId)
            ?.takeIf { policy -> policy.mode == outcome.mode }
            ?.let { current ->
                current.copy(
                    status = outcome.status,
                    strategySignatureJson =
                        if (outcome.updateStrategySignature) {
                            finishedSession.strategyJson
                        } else {
                            current.strategySignatureJson
                        },
                    successCount = outcome.successCount,
                    failureCount = outcome.failureCount,
                    consecutiveFailureCount = outcome.consecutiveFailureCount,
                    suppressedUntil = outcome.suppressedUntil,
                    lastValidatedAt = outcome.lastValidatedAt,
                    updatedAt = outcome.updatedAt,
                )
            }

    private suspend fun persistFinishedSession(pending: PendingTerminalSession) {
        val nextPhase = PendingTerminalPhase.ROOT_CAUSE_ASSESSMENT
        val replacement = pending.toMarker(nextPhase)
        reconcileCheckpoint(
            pending = pending,
            intendedMarker = replacement,
            checkpointSucceeded =
                outboxStore.checkpointTerminalSession(
                    finishedSession = pending.finishedSession,
                    expectedMarker = pending.currentMarker,
                    replacementMarker = replacement,
                ),
        )
    }

    private suspend fun persistRootCauseAssessment(pending: PendingTerminalSession) {
        val assessment =
            artifactPersister.prepareTerminalRootCauseAssessment(
                connectionSessionId = pending.activeSession.id,
                createdAt = pending.createdAt,
                terminalEvidenceSealed =
                    pending.terminalEvidenceSealed &&
                        pending.hasTransientState &&
                        pending.policyEvidenceComplete,
            )
        val completed =
            if (assessment == null) {
                outboxStore.completeTerminalOutbox(
                    pending.currentMarker,
                    retainPolicyDependency = pending.shouldRetainPolicyDependency(),
                )
            } else {
                outboxStore.completeTerminalOutboxWithAssessment(
                    assessment,
                    pending.currentMarker,
                    retainPolicyDependency = pending.shouldRetainPolicyDependency(),
                )
            }
        if (!completed && outboxStore.getTerminalOutbox(pending.currentMarker.key) != null) {
            throw TerminalOutboxRecoveryException()
        }
        if (assessment != null) {
            artifactPersister.markTerminalRootCauseAssessmentPersisted(pending.activeSession.id)
        }
        pending.phase = PendingTerminalPhase.COMPLETE
    }

    private suspend fun checkpointArtifacts(
        pending: PendingTerminalSession,
        events: List<NativeSessionEventEntity>,
        telemetrySample: TelemetrySampleEntity?,
        nextPhase: PendingTerminalPhase,
    ) {
        val replacement = pending.toMarker(nextPhase)
        reconcileCheckpoint(
            pending = pending,
            intendedMarker = replacement,
            checkpointSucceeded =
                outboxStore.checkpointTerminalArtifacts(
                    events = events,
                    telemetrySample = telemetrySample,
                    expectedMarker = pending.currentMarker,
                    replacementMarker = replacement,
                ),
        )
    }

    private suspend fun reconcileCheckpoint(
        pending: PendingTerminalSession,
        intendedMarker: DiagnosticsDurableStateEntity,
        checkpointSucceeded: Boolean,
    ) {
        val previousPhase = pending.phase
        val durableMarker =
            if (checkpointSucceeded) {
                intendedMarker
            } else {
                outboxStore.getTerminalOutbox(pending.currentMarker.key)
                    ?: throw TerminalOutboxRecoveryException()
            }
        val outbox = decodeTerminalOutboxMarker(durableMarker.value)
        requireTerminalOutboxState(!durableMarker.matchesCheckpoint(pending, outbox, previousPhase))
        pending.currentMarker = durableMarker
        pending.policyEvidenceComplete = outbox.policyEvidenceComplete
        pending.phase = outbox.phase
    }
}

private fun PendingTerminalSession.shouldRetainPolicyDependency(): Boolean =
    policyEvidenceComplete && policyOutcome?.failureHandover != null

internal data class TerminalOutboxStart(
    val activeSession: BypassUsageSessionEntity,
    val finishedSession: BypassUsageSessionEntity,
    val telemetry: ServiceTelemetrySnapshot,
    val createdAt: Long,
    val terminalEvidenceSealed: Boolean,
) {
    var artifactBatch: RuntimeTerminalArtifactBatch? = null
}

internal data class PendingTerminalSession(
    val activeSession: BypassUsageSessionEntity,
    val finishedSession: BypassUsageSessionEntity,
    val telemetry: ServiceTelemetrySnapshot?,
    val createdAt: Long,
    val terminalEvidenceSealed: Boolean,
    val hasTransientState: Boolean = true,
    val policyOutcome: RememberedPolicyTerminalOutcome?,
    var policyEvidenceComplete: Boolean = true,
    val artifactBatch: RuntimeTerminalArtifactBatch,
    var currentMarker: DiagnosticsDurableStateEntity = terminalOutboxMarker(activeSession.id, createdAt, ""),
    var phase: PendingTerminalPhase = PendingTerminalPhase.RUNTIME_EVENTS,
) {
    fun toMarker(markerPhase: PendingTerminalPhase): DiagnosticsDurableStateEntity =
        terminalOutboxMarker(
            connectionSessionId = activeSession.id,
            updatedAt = createdAt,
            value =
                RipDpiContractJson.encodeToString(
                    TerminalOutboxMarker(
                        connectionSessionId = activeSession.id,
                        createdAt = createdAt,
                        terminalEvidenceSealed = terminalEvidenceSealed,
                        policyOutcome = policyOutcome,
                        policyEvidenceComplete = policyEvidenceComplete,
                        artifactBatch = artifactBatch,
                        phase = markerPhase,
                    ),
                ),
        )

    fun toPolicyDependency(): DiagnosticsDurableStateEntity? =
        policyOutcome?.policyId?.takeIf { it > 0L }?.let { policyId ->
            DiagnosticsDurableStateEntity(
                key = "$TerminalPolicyDependencyDurableStatePrefix${activeSession.id}",
                value = policyId.toString(),
                updatedAt = createdAt,
            )
        }
}

@Serializable
internal data class TerminalOutboxMarker(
    val schemaVersion: Int = TerminalOutboxSchemaVersion,
    val connectionSessionId: String,
    val createdAt: Long,
    val terminalEvidenceSealed: Boolean,
    val policyOutcome: RememberedPolicyTerminalOutcome? = null,
    val policyEvidenceComplete: Boolean = true,
    val artifactBatch: RuntimeTerminalArtifactBatch,
    val phase: PendingTerminalPhase,
)

@Serializable
private data class TerminalOutboxMarkerV1(
    val connectionSessionId: String,
    val createdAt: Long,
    val terminalEvidenceSealed: Boolean,
    val policyOutcome: RememberedPolicyTerminalOutcome? = null,
    val phase: PendingTerminalPhase,
)

@Serializable
private data class TerminalOutboxEnvelope(
    val schemaVersion: Int = 1,
)

@Serializable
internal enum class PendingTerminalPhase {
    RUNTIME_EVENTS,
    TERMINAL_SAMPLE,
    POLICY_FINALIZATION,
    SESSION_UPSERT,
    ROOT_CAUSE_ASSESSMENT,
    COMPLETE,
}

private const val TerminalOutboxSchemaVersion = 2
private const val TerminalOutboxRecoveryBatchSize = 64
private const val MaxTerminalOutboxRecoveryBatches = 256

private fun decodeTerminalOutboxMarker(value: String): TerminalOutboxMarker {
    val schemaVersion =
        decodeTerminalOutbox<TerminalOutboxEnvelope>(value).schemaVersion
    return when (schemaVersion) {
        1 -> {
            migrateV1TerminalOutbox(value)
        }

        TerminalOutboxSchemaVersion -> {
            decodeTerminalOutbox<TerminalOutboxMarker>(value)
        }

        else -> {
            invalidTerminalOutbox()
        }
    }
}

private fun migrateV1TerminalOutbox(value: String): TerminalOutboxMarker {
    val marker = decodeTerminalOutbox<TerminalOutboxMarkerV1>(value)
    return TerminalOutboxMarker(
        connectionSessionId = marker.connectionSessionId,
        createdAt = marker.createdAt,
        terminalEvidenceSealed = false,
        policyOutcome = marker.policyOutcome,
        policyEvidenceComplete = false,
        artifactBatch = RuntimeTerminalArtifactBatch(events = emptyList()),
        phase = marker.phase,
    )
}

private class TerminalOutboxRecoveryException : IllegalStateException("Invalid terminal outbox state")

private inline fun <reified T> decodeTerminalOutbox(value: String): T =
    runCatching { RuntimeHistoryJson.decodeFromString<T>(value) }
        .getOrElse { invalidTerminalOutbox() }

private fun invalidTerminalOutbox(): Nothing = throw TerminalOutboxRecoveryException()

private fun requireTerminalOutboxState(invalid: Boolean) {
    if (invalid) invalidTerminalOutbox()
}

private fun <T> requireTerminalOutboxValue(value: T?): T = value ?: invalidTerminalOutbox()

private fun DiagnosticsDurableStateEntity.matchesCheckpoint(
    pending: PendingTerminalSession,
    outbox: TerminalOutboxMarker,
    previousPhase: PendingTerminalPhase,
): Boolean {
    val matchesMarker =
        key == "$TerminalOutboxDurableStatePrefix${pending.activeSession.id}" &&
            updatedAt == pending.createdAt
    val matchesOutbox =
        outbox.connectionSessionId == pending.activeSession.id &&
            outbox.createdAt == pending.createdAt
    return matchesMarker && matchesOutbox && outbox.phase.ordinal >= previousPhase.ordinal
}

private fun terminalOutboxMarker(
    connectionSessionId: String,
    updatedAt: Long,
    value: String,
): DiagnosticsDurableStateEntity =
    DiagnosticsDurableStateEntity(
        key = "$TerminalOutboxDurableStatePrefix$connectionSessionId",
        value = value,
        updatedAt = updatedAt,
    )
