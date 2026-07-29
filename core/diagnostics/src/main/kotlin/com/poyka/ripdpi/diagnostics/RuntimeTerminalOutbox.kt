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
            )
        return if (marker.value == pending.toMarker(PendingTerminalPhase.RUNTIME_EVENTS).value) {
            pending.apply { currentMarker = marker }
        } else {
            recover(marker)
        }
    }

    suspend fun recover(): List<PendingTerminalSession> =
        outboxStore.getPendingTerminalOutboxes().map { marker -> recover(marker) }

    private suspend fun recover(marker: DiagnosticsDurableStateEntity): PendingTerminalSession {
        val outbox = decodeTerminalOutboxMarker(marker.value)
        val finishedSession =
            requireNotNull(usageHistoryStore.getBypassUsageSession(outbox.connectionSessionId)) {
                "Terminal outbox ${marker.key} has no durable usage session"
            }
        return PendingTerminalSession(
            activeSession = finishedSession,
            finishedSession = finishedSession,
            telemetry = null,
            createdAt = outbox.createdAt,
            terminalEvidenceSealed = outbox.terminalEvidenceSealed,
            hasTransientState = true,
            policyOutcome = outbox.policyOutcome,
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
        val replacement = pending.toMarker(nextPhase)
        check(
            outboxStore.checkpointTerminalPolicy(
                policy = pending.policyOutcome?.let { outcome -> reconstructPolicy(outcome, pending.finishedSession) },
                expectedMarker = pending.currentMarker,
                replacementMarker = replacement,
            ),
        ) { "Terminal outbox policy checkpoint lost ownership" }
        runCatching { rememberedPolicySessionTracker.publishTerminalOutcome(pending.policyOutcome) }
        rememberedPolicySessionTracker.clear()
        pending.currentMarker = replacement
        pending.phase = nextPhase
    }

    private suspend fun reconstructPolicy(
        outcome: RememberedPolicyTerminalOutcome,
        finishedSession: BypassUsageSessionEntity,
    ): RememberedNetworkPolicyEntity {
        val current =
            requireNotNull(
                policyRecordStore.getRememberedNetworkPolicy(
                    fingerprintHash = outcome.fingerprintHash,
                    mode = outcome.mode,
                ),
            ) {
                "Terminal policy outcome has no durable remembered policy"
            }
        return current.copy(
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
        check(
            outboxStore.checkpointTerminalSession(
                finishedSession = pending.finishedSession,
                expectedMarker = pending.currentMarker,
                replacementMarker = replacement,
            ),
        ) { "Terminal outbox session checkpoint lost ownership" }
        pending.currentMarker = replacement
        pending.phase = nextPhase
    }

    private suspend fun persistRootCauseAssessment(pending: PendingTerminalSession) {
        if (!artifactPersister.hasTerminalRootCauseAssessment(pending.activeSession.id)) {
            artifactPersister.persistTerminalRootCauseAssessment(
                connectionSessionId = pending.activeSession.id,
                createdAt = pending.createdAt,
                terminalEvidenceSealed = pending.terminalEvidenceSealed && pending.hasTransientState,
            )
        }
        check(outboxStore.completeTerminalOutbox(pending.currentMarker)) {
            "Terminal outbox completion lost ownership"
        }
        pending.phase = PendingTerminalPhase.COMPLETE
    }

    private suspend fun checkpoint(
        pending: PendingTerminalSession,
        nextPhase: PendingTerminalPhase,
    ) {
        val replacement = pending.toMarker(nextPhase)
        check(outboxStore.checkpointTerminalOutbox(pending.currentMarker, replacement)) {
            "Terminal outbox checkpoint lost ownership"
        }
        pending.currentMarker = replacement
        pending.phase = nextPhase
    }

    private suspend fun checkpointArtifacts(
        pending: PendingTerminalSession,
        events: List<NativeSessionEventEntity>,
        telemetrySample: TelemetrySampleEntity?,
        nextPhase: PendingTerminalPhase,
    ) {
        val replacement = pending.toMarker(nextPhase)
        check(
            outboxStore.checkpointTerminalArtifacts(
                events = events,
                telemetrySample = telemetrySample,
                expectedMarker = pending.currentMarker,
                replacementMarker = replacement,
            ),
        ) { "Terminal outbox artifact checkpoint lost ownership" }
        pending.currentMarker = replacement
        pending.phase = nextPhase
    }
}

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
    val artifactBatch: RuntimeTerminalArtifactBatch,
    var currentMarker: DiagnosticsDurableStateEntity = terminalOutboxMarker(activeSession.id, createdAt, ""),
    var phase: PendingTerminalPhase = PendingTerminalPhase.RUNTIME_EVENTS,
) {
    fun toMarker(markerPhase: PendingTerminalPhase): DiagnosticsDurableStateEntity =
        terminalOutboxMarker(
            connectionSessionId = activeSession.id,
            updatedAt = createdAt,
            value =
                RuntimeHistoryJson.encodeToString(
                    TerminalOutboxMarker(
                        connectionSessionId = activeSession.id,
                        createdAt = createdAt,
                        terminalEvidenceSealed = terminalEvidenceSealed,
                        policyOutcome = policyOutcome,
                        artifactBatch = artifactBatch,
                        phase = markerPhase,
                    ),
                ),
        )
}

@Serializable
internal data class TerminalOutboxMarker(
    val schemaVersion: Int = TerminalOutboxSchemaVersion,
    val connectionSessionId: String,
    val createdAt: Long,
    val terminalEvidenceSealed: Boolean,
    val policyOutcome: RememberedPolicyTerminalOutcome? = null,
    val artifactBatch: RuntimeTerminalArtifactBatch,
    val phase: PendingTerminalPhase,
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

private fun decodeTerminalOutboxMarker(value: String): TerminalOutboxMarker {
    val marker =
        runCatching { RuntimeHistoryJson.decodeFromString<TerminalOutboxMarker>(value) }
            .getOrElse { throw IllegalStateException("Invalid terminal outbox state") }
    if (marker.schemaVersion != TerminalOutboxSchemaVersion) {
        throw IllegalStateException("Unsupported terminal outbox state")
    }
    return marker
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
