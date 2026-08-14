package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class RelayCleanupReceipt {
    NotRequired,
    Pending,
    Completed,
    RollbackCompleted,
    InFlightUnconfirmed,
    CandidatesExhausted,
    SwitchSuppressed,
}

data class RelayHealthDecisionReceipt(
    val profileToken: String,
    val relayTransport: String,
    val targetCategory: RelayTargetCategory,
    val scope: RelayHealthScope,
    val runtimeId: String?,
    val decision: RelayHealthDecision,
    val cleanupReceipt: RelayCleanupReceipt,
)

fun interface RelayHealthDecisionRecorder {
    suspend fun record(receipt: RelayHealthDecisionReceipt)
}

@Singleton
class RoomRelayHealthDecisionRecorder
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) : RelayHealthDecisionRecorder {
        override suspend fun record(receipt: RelayHealthDecisionReceipt) {
            val decision = receipt.decision
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = receipt.stableEventId(),
                    sessionId = null,
                    connectionSessionId = null,
                    source = "app",
                    level = if (decision is RelayHealthDecision.ConfirmedFailed) "warn" else "info",
                    message = "relay_health_decision",
                    createdAt = decision.decidedAtMs,
                    runtimeId = receipt.runtimeId,
                    subsystem = "relay_health_decision",
                    failureStage =
                        (decision as? RelayHealthDecision.ConfirmedFailed)?.failureStage?.wireValue(),
                    healthAttemptId = decision.attemptId,
                    relayProfileToken = receipt.profileToken,
                    relayTransport = receipt.relayTransport.safeTokenOrUnavailable(),
                    relayTargetCategory = receipt.targetCategory.wireValue(),
                    positiveEvidenceWatermark = decision.positiveEvidenceWatermark,
                    relayHealthDecision = decision.wireValue(),
                    cooldownScope = receipt.cooldownScopeWireValue(),
                    cleanupReceipt = receipt.cleanupReceipt.wireValue(),
                ),
            )
        }
    }

private fun RelayHealthDecisionReceipt.stableEventId(): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${decision.attemptId}|$profileToken|${runtimeId.orEmpty()}".toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "relay-health-decision-${digest.take(StableEventIdHexLength)}"
}

private const val StableEventIdHexLength = 24

private fun RelayHealthDecisionReceipt.cooldownScopeWireValue(): String =
    if (decision !is RelayHealthDecision.ConfirmedFailed) {
        "not_applied"
    } else if (scope.persistentNetworkHash.isNullOrBlank()) {
        "session"
    } else {
        "persistent_network"
    }

private fun RelayHealthDecision.wireValue(): String =
    when (this) {
        is RelayHealthDecision.Healthy -> "healthy"
        is RelayHealthDecision.Inconclusive -> "inconclusive"
        is RelayHealthDecision.ConfirmedFailed -> "confirmed_failed"
    }

private fun RelayTargetCategory.wireValue(): String = name.toSnakeCase()

private fun RelayFailureStage.wireValue(): String = name.toSnakeCase()

private fun RelayCleanupReceipt.wireValue(): String = name.toSnakeCase()

private fun String.safeTokenOrUnavailable(): String =
    lowercase().takeIf { it.matches(Regex("[a-z0-9_]{1,48}")) } ?: "unavailable"

private fun String.toSnakeCase(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
