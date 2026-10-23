package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileQualityKey(
    val groupId: String,
    val profileId: String,
)

enum class ProfileQualityStatus { Verified, Partial, Unavailable }

enum class ProfileQualityReason {
    Verified,
    LoadNotOverlapped,
    BaselineUnavailable,
    LoadedUnavailable,
    TransferFailed,
    BudgetExhausted,
    BudgetUnavailable,
    ScopeChanged,
    CleanupPending,
}

data class ProfileQualityObservation(
    val key: ProfileQualityKey,
    val status: ProfileQualityStatus,
    val verifiedBytes: Long?,
    val bodyDurationNanos: Long?,
    val goodputBytesPerSecond: Long?,
    val baselineLatencyMillis: Long?,
    val loadedLatencyMillis: Long?,
    val loadedOverlap: Boolean,
    val reason: ProfileQualityReason,
    val observedAtElapsedMillis: Long,
)

data class ProfileQualityBudget(
    val groupId: String,
    val reservedPassBytes: Long,
    val consumedWindowBytes: Long,
    val maxWindowBytes: Long,
    val nextProbeAtElapsedMillis: Long?,
    val reason: ProfileQualityReason,
)

enum class ProfileQualityDecisionReason {
    CollectingEvidence,
    NoQualifiedImprovement,
    PromotedForGoodput,
    PromotedForLoadedLatency,
    Cooldown,
    DeferredBudget,
}

data class ProfileQualityDecision(
    val groupId: String,
    val selectedProfileId: String?,
    val challengerProfileId: String?,
    val reason: ProfileQualityDecisionReason,
    val observedAtElapsedMillis: Long,
)

data class ProfileQualitySnapshot(
    val observations: Map<ProfileQualityKey, ProfileQualityObservation> = emptyMap(),
    val budgets: Map<String, ProfileQualityBudget> = emptyMap(),
    val decisions: Map<String, ProfileQualityDecision> = emptyMap(),
)

@Singleton
class ProfileQualityStore
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow(ProfileQualitySnapshot())
        val state: StateFlow<ProfileQualitySnapshot> = mutableState.asStateFlow()

        private val generations = mutableMapOf<String, Long>()

        @Synchronized
        fun generation(groupId: String): Long = generations[groupId] ?: 0L

        @Synchronized
        fun observe(
            observations: List<ProfileQualityObservation>,
            expectedGeneration: Long? = null,
        ) {
            if (expectedGeneration != null &&
                observations.any { generation(it.key.groupId) != expectedGeneration }
            ) {
                return
            }
            mutableState.update {
                it.copy(
                    observations =
                        it.observations +
                            observations.associateBy { value ->
                                value.key
                            },
                )
            }
        }

        fun budget(value: ProfileQualityBudget) {
            mutableState.update { it.copy(budgets = it.budgets + (value.groupId to value)) }
        }

        @Synchronized
        fun decide(
            value: ProfileQualityDecision,
            expectedGeneration: Long? = null,
        ) {
            if (expectedGeneration != null && generation(value.groupId) != expectedGeneration) return
            mutableState.update { it.copy(decisions = it.decisions + (value.groupId to value)) }
        }

        @Synchronized
        fun invalidate(groupId: String) {
            generations[groupId] = Math.addExact(generation(groupId), 1L)
            mutableState.update {
                it.copy(
                    observations =
                        it.observations.mapValues { (key, value) ->
                            if (key.groupId != groupId) {
                                value
                            } else {
                                value.copy(
                                    status = ProfileQualityStatus.Unavailable,
                                    verifiedBytes = null,
                                    bodyDurationNanos = null,
                                    goodputBytesPerSecond = null,
                                    baselineLatencyMillis = null,
                                    loadedLatencyMillis = null,
                                    loadedOverlap = false,
                                    reason = ProfileQualityReason.ScopeChanged,
                                )
                            }
                        },
                    decisions = it.decisions - groupId,
                )
            }
        }
    }

/** Internal object identity stays with the selector; UI observations expose no origin or digest. */
data class ProfileQualityMeasurement(
    val probeIndex: Int,
    val verifiedBytes: Long,
    val bodyDurationNanos: Long,
    val baselineLatencyMillis: Long?,
    val loadedLatencyMillis: Long?,
    val loadedOverlap: Boolean,
    val reason: ProfileQualityReason,
) {
    val goodputBytesPerSecond: Long
        get() = (verifiedBytes.toDouble() * NanosPerSecond / bodyDurationNanos).toLong()
    val complete: Boolean
        get() =
            reason == ProfileQualityReason.Verified && loadedOverlap &&
                baselineLatencyMillis != null && loadedLatencyMillis != null && bodyDurationNanos > 0
}

private const val NanosPerSecond = 1_000_000_000L
