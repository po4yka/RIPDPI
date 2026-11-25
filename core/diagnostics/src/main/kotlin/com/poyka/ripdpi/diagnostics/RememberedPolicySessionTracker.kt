package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofDurationMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofTransferBytes
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.TerminalPolicyDependencyDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.decodedSource
import com.poyka.ripdpi.data.policyHandoverDeliveryId
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RememberedPolicySessionTracker
    @Inject
    constructor(
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val policyHandoverEventStore: PolicyHandoverEventStore,
    ) {
        private var activeRememberedPolicySession: ActiveRememberedPolicySession? = null

        suspend fun sync(
            session: BypassUsageSessionEntity,
            activePolicy: ActiveConnectionPolicy?,
        ): BypassUsageSessionEntity {
            val policy =
                activePolicy?.takeIf { it.usedRememberedPolicy } ?: run {
                    clear()
                    return session
                }
            return if (activeRememberedPolicySession?.matches(policy) == true) {
                session
            } else {
                applyNewPolicy(session, policy)
            }
        }

        private suspend fun applyNewPolicy(
            session: BypassUsageSessionEntity,
            policy: ActiveConnectionPolicy,
        ): BypassUsageSessionEntity {
            val matchedPolicy = policy.matchedPolicy ?: return session
            val rememberedPolicyAudit =
                RememberedPolicySessionAudit(
                    matchedFingerprintHash = matchedPolicy.fingerprintHash,
                    source = matchedPolicy.decodedSource(),
                    appliedByExactMatch = policy.rememberedPolicyAppliedByExactMatch ?: true,
                    previousSuccessCount = matchedPolicy.successCount,
                    previousFailureCount = matchedPolicy.failureCount,
                    previousConsecutiveFailureCount = matchedPolicy.consecutiveFailureCount,
                )
            activeRememberedPolicySession =
                ActiveRememberedPolicySession(
                    entity = rememberedNetworkPolicyStore.recordApplied(matchedPolicy, policy.appliedAt),
                    startedAt = policy.appliedAt,
                    fingerprintHash = policy.fingerprintHash,
                    policySignature = policy.policySignature,
                    mode = policy.mode,
                )
            return session.withRememberedPolicyAudit(rememberedPolicyAudit)
        }

        internal fun prepareTerminalOutcome(
            session: BypassUsageSessionEntity,
            finalizedAt: Long,
        ): RememberedPolicyTerminalOutcome? {
            val rememberedPolicySession = activeRememberedPolicySession ?: return null
            val transferBytes = session.txBytes + session.rxBytes
            val durationMs = finalizedAt - rememberedPolicySession.startedAt
            val proved =
                session.failureMessage.isNullOrBlank() &&
                    durationMs >= RememberedNetworkPolicyProofDurationMs &&
                    transferBytes >= RememberedNetworkPolicyProofTransferBytes
            val failed =
                !session.failureMessage.isNullOrBlank() ||
                    session.endedReason?.startsWith("failed:") == true

            val entity = rememberedPolicySession.entity
            return when {
                failed && !proved -> {
                    RememberedPolicyTerminalOutcome(
                        policyId = entity.id,
                        mode = entity.mode,
                        updateStrategySignature = false,
                        updatedAt = finalizedAt,
                        failureHandover = rememberedPolicySession.toFailureHandover(session.id, finalizedAt),
                    )
                }

                proved -> {
                    RememberedPolicyTerminalOutcome(
                        policyId = entity.id,
                        mode = entity.mode,
                        updateStrategySignature = true,
                        updatedAt = finalizedAt,
                    )
                }

                else -> {
                    null
                }
            }
        }

        internal suspend fun publishTerminalOutcome(
            outcome: RememberedPolicyTerminalOutcome?,
            policy: RememberedNetworkPolicyEntity?,
        ) {
            val failureHandover = outcome?.failureHandover ?: return
            val resolvedPolicy = policy?.takeIf { it.id == outcome.policyId && it.mode == outcome.mode } ?: return
            policyHandoverEventStore.publish(
                PolicyHandoverEvent(
                    deliveryId = failureHandover.deliveryId,
                    mode = failureHandover.mode,
                    currentFingerprintHash = resolvedPolicy.fingerprintHash,
                    classification = AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE,
                    currentNetworkValidated = true,
                    currentCaptivePortalDetected = false,
                    usedRememberedPolicy = true,
                    occurredAt = failureHandover.occurredAt,
                    rememberedPolicyDependencyKey = failureHandover.policyDependencyKey,
                ),
            )
        }

        fun clear() {
            activeRememberedPolicySession = null
        }

        private data class ActiveRememberedPolicySession(
            val entity: RememberedNetworkPolicyEntity,
            val startedAt: Long,
            val fingerprintHash: String?,
            val policySignature: String,
            val mode: Mode,
        ) {
            fun matches(policy: ActiveConnectionPolicy): Boolean =
                fingerprintHash == policy.fingerprintHash &&
                    policySignature == policy.policySignature

            fun toFailureHandover(
                connectionSessionId: String,
                failedAt: Long,
            ): RememberedPolicyFailureHandover? =
                if (fingerprintHash != null) {
                    RememberedPolicyFailureHandover(
                        deliveryId = policyHandoverDeliveryId("runtime-terminal:$connectionSessionId"),
                        mode = mode,
                        policyDependencyKey =
                            "$TerminalPolicyDependencyDurableStatePrefix$connectionSessionId",
                        occurredAt = failedAt,
                    )
                } else {
                    null
                }
        }
    }

@Serializable
internal data class RememberedPolicyTerminalOutcome(
    val policyId: Long = 0L,
    val mode: String,
    val updateStrategySignature: Boolean,
    val updatedAt: Long,
    val failureHandover: RememberedPolicyFailureHandover? = null,
) {
    fun applyTo(
        current: RememberedNetworkPolicyEntity,
        strategySignatureJson: String,
    ): RememberedNetworkPolicyEntity? =
        when {
            current.mode != mode -> {
                null
            }

            updateStrategySignature -> {
                current.copy(
                    strategySignatureJson = strategySignatureJson,
                    successCount = current.successCount + 1,
                    consecutiveFailureCount = 0,
                    updatedAt = updatedAt,
                )
            }

            else -> {
                applyFailureTo(current)
            }
        }

    private fun applyFailureTo(current: RememberedNetworkPolicyEntity): RememberedNetworkPolicyEntity {
        val nextConsecutiveFailures = current.consecutiveFailureCount + 1
        val shouldSuppress =
            current.status == RememberedNetworkPolicyStatusValidated && nextConsecutiveFailures >= 2
        return current.copy(
            status = if (shouldSuppress) RememberedNetworkPolicyStatusSuppressed else current.status,
            failureCount = current.failureCount + 1,
            consecutiveFailureCount = nextConsecutiveFailures,
            updatedAt = updatedAt,
        )
    }
}

@Serializable
internal data class RememberedPolicyFailureHandover(
    val deliveryId: String,
    val mode: Mode,
    val policyDependencyKey: String,
    val occurredAt: Long,
)

private fun BypassUsageSessionEntity.withRememberedPolicyAudit(
    audit: RememberedPolicySessionAudit,
): BypassUsageSessionEntity =
    copy(
        rememberedPolicyMatchedFingerprintHash = audit.matchedFingerprintHash,
        rememberedPolicySource = audit.source.encodeStorageValue(),
        rememberedPolicyAppliedByExactMatch = audit.appliedByExactMatch,
        rememberedPolicyPreviousSuccessCount = audit.previousSuccessCount,
        rememberedPolicyPreviousFailureCount = audit.previousFailureCount,
        rememberedPolicyPreviousConsecutiveFailureCount = audit.previousConsecutiveFailureCount,
    )

private data class RememberedPolicySessionAudit(
    val matchedFingerprintHash: String,
    val source: RememberedNetworkPolicySource,
    val appliedByExactMatch: Boolean,
    val previousSuccessCount: Int,
    val previousFailureCount: Int,
    val previousConsecutiveFailureCount: Int,
)
