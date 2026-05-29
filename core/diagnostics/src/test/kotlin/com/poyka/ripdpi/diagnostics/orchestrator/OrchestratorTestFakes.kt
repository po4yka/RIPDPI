package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification

/** Shared test fakes for orchestrator tests. Avoids duplicate class names across files. */

internal class StubDnsClassifier(
    private val cls: DnsClassification,
    private val onClassify: () -> Unit = {},
) : DnsClassifier {
    /** The observation passed to the most recent [classify] call. */
    var lastObservation: PassiveObservation? = null
        private set

    override suspend fun classify(observation: PassiveObservation): DnsClassification {
        lastObservation = observation
        onClassify()
        return cls
    }
}

internal class StubTransportClassifier(
    private val cls: TransportClass,
    private val onClassify: () -> Unit = {},
) : TransportPolicyClassifier {
    /** The observation passed to the most recent [classify] call. */
    var lastObservation: PassiveObservation? = null
        private set

    override suspend fun classify(observation: PassiveObservation): TransportClass {
        lastObservation = observation
        onClassify()
        return cls
    }
}

internal class StubArmRanker(
    private val onRank: () -> Unit = {},
) : ArmRanker {
    override fun rank(arms: List<CandidateArm>): List<CandidateArm> {
        onRank()
        return arms
    }
}

internal class StubArmExecutor(
    private val stableSuccess: Boolean,
    private val probeBytes: Long,
    private val onExecute: () -> Unit = {},
) : ArmExecutor {
    var callCount = 0
        private set

    override suspend fun execute(
        arm: CandidateArm,
        probeBytesSoFar: Long,
    ): ArmExecutionResult {
        callCount++
        onExecute()
        return ArmExecutionResult(stableSuccess = stableSuccess, probeBytes = probeBytes)
    }
}

/** Executor that returns stable success only on the given zero-based call index. */
internal class SucceedAtIndexExecutor(
    private val successIndex: Int,
) : ArmExecutor {
    private var callIndex = 0

    override suspend fun execute(
        arm: CandidateArm,
        probeBytesSoFar: Long,
    ): ArmExecutionResult {
        val success = callIndex == successIndex
        callIndex++
        return ArmExecutionResult(stableSuccess = success, probeBytes = 100L)
    }
}

/** Executor that returns stable success for every arm. */
internal class AlwaysSucceedExecutor : ArmExecutor {
    override suspend fun execute(
        arm: CandidateArm,
        probeBytesSoFar: Long,
    ): ArmExecutionResult = ArmExecutionResult(stableSuccess = true, probeBytes = 0L)
}

/** Executor that always fails. */
internal class NeverSucceedExecutor : ArmExecutor {
    override suspend fun execute(
        arm: CandidateArm,
        probeBytesSoFar: Long,
    ): ArmExecutionResult = ArmExecutionResult(stableSuccess = false, probeBytes = 0L)
}

/**
 * Scripted executor for end-to-end result-class scenarios.
 *
 * [succeedingArms] report a stable success; [ownedStackArms] additionally set
 * `requiresOwnedStackPin` so the orchestrator applies confirm_once gating.
 * Records the arms it was asked to execute, in order.
 */
internal class ScriptedArmExecutor(
    private val succeedingArms: Set<CandidateArm>,
    private val ownedStackArms: Set<CandidateArm> = emptySet(),
    private val probeBytesPerArm: Long = 100L,
) : ArmExecutor {
    val executedArms = mutableListOf<CandidateArm>()

    override suspend fun execute(
        arm: CandidateArm,
        probeBytesSoFar: Long,
    ): ArmExecutionResult {
        executedArms += arm
        return ArmExecutionResult(
            stableSuccess = arm in succeedingArms,
            probeBytes = probeBytesPerArm,
            requiresOwnedStackPin = arm in ownedStackArms,
        )
    }
}

/** Arm ranker that passes arms through unchanged. */
internal class IdentityArmRanker : ArmRanker {
    override fun rank(arms: List<CandidateArm>): List<CandidateArm> = arms
}

/** Pin guard that always reports the pin as already confirmed. */
internal class ConfirmedPinGuard : OwnedStackPinGuard {
    override fun isConfirmed(): Boolean = true

    override fun confirm() = Unit
}

/** Pin guard with a fixed confirmation state (use false to exercise confirm_once gating). */
internal class FixedPinGuard(
    private val confirmed: Boolean,
) : OwnedStackPinGuard {
    override fun isConfirmed(): Boolean = confirmed

    override fun confirm() = Unit
}
