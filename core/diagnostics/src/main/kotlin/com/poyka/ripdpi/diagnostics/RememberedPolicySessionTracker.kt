package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofDurationMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofTransferBytes
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.RememberedNetworkPolicySuppressionDurationMs
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
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
                activePolicy ?: run {
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
            val rememberedPolicyAudit: RememberedPolicySessionAudit?
            val entity =
                if (policy.usedRememberedPolicy) {
                    val matchedPolicy = policy.matchedPolicy ?: return session
                    rememberedPolicyAudit =
                        RememberedPolicySessionAudit(
                            matchedFingerprintHash = matchedPolicy.fingerprintHash,
                            source = matchedPolicy.decodedSource(),
                            appliedByExactMatch =
                                policy.rememberedPolicyAppliedByExactMatch ?: policy.usedRememberedPolicy,
                            previousSuccessCount = matchedPolicy.successCount,
                            previousFailureCount = matchedPolicy.failureCount,
                            previousConsecutiveFailureCount = matchedPolicy.consecutiveFailureCount,
                        )
                    rememberedNetworkPolicyStore.recordApplied(matchedPolicy, policy.appliedAt)
                } else {
                    rememberedPolicyAudit = null
                    rememberedNetworkPolicyStore.upsertObservedPolicy(
                        policy = policy.policy.copy(strategySignatureJson = session.strategyJson),
                        source = RememberedNetworkPolicySource.MANUAL_SESSION,
                        observedAt = policy.appliedAt,
                    )
                }
            activeRememberedPolicySession =
                ActiveRememberedPolicySession(
                    entity = entity,
                    usedRememberedPolicy = policy.usedRememberedPolicy,
                    startedAt = policy.appliedAt,
                    fingerprintHash = policy.fingerprintHash,
                    policySignature = policy.policySignature,
                    mode = policy.mode,
                )
            return rememberedPolicyAudit?.let(session::withRememberedPolicyAudit) ?: session
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

            return when {
                rememberedPolicySession.usedRememberedPolicy && failed && !proved -> {
                    val nextConsecutiveFailures = rememberedPolicySession.entity.consecutiveFailureCount + 1
                    val shouldSuppress =
                        rememberedPolicySession.entity.status == RememberedNetworkPolicyStatusValidated &&
                            nextConsecutiveFailures >= 2
                    RememberedPolicyTerminalOutcome(
                        policyId = rememberedPolicySession.entity.id,
                        fingerprintHash = rememberedPolicySession.entity.fingerprintHash,
                        mode = rememberedPolicySession.entity.mode,
                        status =
                            if (shouldSuppress) {
                                RememberedNetworkPolicyStatusSuppressed
                            } else {
                                rememberedPolicySession.entity.status
                            },
                        successCount = rememberedPolicySession.entity.successCount,
                        failureCount = rememberedPolicySession.entity.failureCount + 1,
                        consecutiveFailureCount = nextConsecutiveFailures,
                        suppressedUntil =
                            if (shouldSuppress) {
                                finalizedAt + RememberedNetworkPolicySuppressionDurationMs
                            } else {
                                rememberedPolicySession.entity.suppressedUntil
                            },
                        lastValidatedAt = rememberedPolicySession.entity.lastValidatedAt,
                        updateStrategySignature = false,
                        updatedAt = finalizedAt,
                        failureHandover = rememberedPolicySession.toFailureHandover(session.id, finalizedAt),
                    )
                }

                proved -> {
                    RememberedPolicyTerminalOutcome(
                        policyId = rememberedPolicySession.entity.id,
                        fingerprintHash = rememberedPolicySession.entity.fingerprintHash,
                        mode = rememberedPolicySession.entity.mode,
                        status = RememberedNetworkPolicyStatusValidated,
                        successCount = rememberedPolicySession.entity.successCount + 1,
                        failureCount = rememberedPolicySession.entity.failureCount,
                        consecutiveFailureCount = 0,
                        suppressedUntil = null,
                        lastValidatedAt = finalizedAt,
                        updateStrategySignature = true,
                        updatedAt = finalizedAt,
                    )
                }

                else -> {
                    null
                }
            }
        }

        internal suspend fun publishTerminalOutcome(outcome: RememberedPolicyTerminalOutcome?) {
            val failureHandover = outcome?.failureHandover ?: return
            policyHandoverEventStore.publish(
                PolicyHandoverEvent(
                    deliveryId = failureHandover.deliveryId,
                    mode = failureHandover.mode,
                    currentFingerprintHash = failureHandover.fingerprintHash,
                    classification = AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE,
                    currentNetworkValidated = true,
                    currentCaptivePortalDetected = false,
                    usedRememberedPolicy = true,
                    occurredAt = failureHandover.occurredAt,
                ),
            )
        }

        fun clear() {
            activeRememberedPolicySession = null
        }

        private data class ActiveRememberedPolicySession(
            val entity: RememberedNetworkPolicyEntity,
            val usedRememberedPolicy: Boolean,
            val startedAt: Long,
            val fingerprintHash: String?,
            val policySignature: String,
            val mode: Mode,
        ) {
            fun matches(policy: ActiveConnectionPolicy): Boolean =
                usedRememberedPolicy == policy.usedRememberedPolicy &&
                    fingerprintHash == policy.fingerprintHash &&
                    policySignature == policy.policySignature

            fun toFailureHandover(
                connectionSessionId: String,
                failedAt: Long,
            ): RememberedPolicyFailureHandover? =
                fingerprintHash?.let { fingerprintHash ->
                    RememberedPolicyFailureHandover(
                        deliveryId = policyHandoverDeliveryId("runtime-terminal:$connectionSessionId"),
                        mode = mode,
                        fingerprintHash = fingerprintHash,
                        occurredAt = failedAt,
                    )
                }
        }
    }

@Serializable
internal data class RememberedPolicyTerminalOutcome(
    val policyId: Long = 0L,
    val fingerprintHash: String,
    val mode: String,
    val status: String,
    val successCount: Int,
    val failureCount: Int,
    val consecutiveFailureCount: Int,
    val suppressedUntil: Long? = null,
    val lastValidatedAt: Long? = null,
    val updateStrategySignature: Boolean,
    val updatedAt: Long,
    val failureHandover: RememberedPolicyFailureHandover? = null,
)

@Serializable
internal data class RememberedPolicyFailureHandover(
    val deliveryId: String,
    val mode: Mode,
    val fingerprintHash: String,
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
