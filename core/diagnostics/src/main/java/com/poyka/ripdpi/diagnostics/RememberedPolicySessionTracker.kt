@file:Suppress("ComplexCondition", "ReturnCount")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.RememberedNetworkPolicyProofDurationMs
import com.poyka.ripdpi.data.RememberedNetworkPolicyProofTransferBytes
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceManualSession
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RememberedPolicySessionTracker
    @Inject
    constructor(
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    ) {
        private var activeRememberedPolicySession: ActiveRememberedPolicySession? = null

        suspend fun sync(
            session: BypassUsageSessionEntity,
            activePolicy: ActiveConnectionPolicy?,
        ) {
            val policy =
                activePolicy ?: run {
                    clear()
                    return
                }
            val existing = activeRememberedPolicySession
            if (
                existing != null &&
                existing.usedRememberedPolicy == policy.usedRememberedPolicy &&
                existing.fingerprintHash == policy.fingerprintHash &&
                existing.policySignature == policy.policySignature
            ) {
                return
            }
            val entity =
                if (policy.usedRememberedPolicy) {
                    policy.matchedPolicy?.let { rememberedNetworkPolicyStore.recordApplied(it, policy.appliedAt) }
                        ?: return
                } else {
                    rememberedNetworkPolicyStore.upsertObservedPolicy(
                        policy = policy.policy.copy(strategySignatureJson = session.strategyJson),
                        source = RememberedNetworkPolicySourceManualSession,
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
                )
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

        fun clear() {
            activeRememberedPolicySession = null
        }

        private data class ActiveRememberedPolicySession(
            val entity: RememberedNetworkPolicyEntity,
            val usedRememberedPolicy: Boolean,
            val startedAt: Long,
            val fingerprintHash: String?,
            val policySignature: String,
        )
    }
