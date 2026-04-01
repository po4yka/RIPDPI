package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofDurationMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofTransferBytes
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.decodedSource
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

        suspend fun finalize(
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
                rememberedPolicySession.usedRememberedPolicy && failed && !proved -> {
                    rememberedNetworkPolicyStore.recordFailure(
                        policy = rememberedPolicySession.entity,
                        failedAt = finalizedAt,
                        allowSuppression = true,
                    )
                    publishStrategyFailureEvent(rememberedPolicySession, finalizedAt)
                }

                rememberedPolicySession.usedRememberedPolicy && proved -> {
                    rememberedNetworkPolicyStore.recordSuccess(
                        policy = rememberedPolicySession.entity,
                        validated = true,
                        strategySignatureJson = session.strategyJson,
                        completedAt = finalizedAt,
                    )
                }

                !rememberedPolicySession.usedRememberedPolicy && proved -> {
                    rememberedNetworkPolicyStore.recordSuccess(
                        policy = rememberedPolicySession.entity,
                        validated = true,
                        strategySignatureJson = session.strategyJson,
                        completedAt = finalizedAt,
                    )
                }
            }
            clear()
        }

        private fun publishStrategyFailureEvent(
            session: ActiveRememberedPolicySession,
            failedAt: Long,
        ) {
            val fingerprintHash = session.fingerprintHash ?: return
            policyHandoverEventStore.publish(
                PolicyHandoverEvent(
                    mode = session.mode,
                    currentFingerprintHash = fingerprintHash,
                    classification = AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE,
                    currentNetworkValidated = true,
                    currentCaptivePortalDetected = false,
                    usedRememberedPolicy = true,
                    policySignature = session.policySignature,
                    occurredAt = failedAt,
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
        }
    }

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
