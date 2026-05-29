package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import com.poyka.ripdpi.diagnostics.shared.DnsClassification

/**
 * Classification of the observed blocking pattern, derived by combining the
 * DNS-classifier and transport-policy-classifier verdicts in Phase 1 + 2.
 *
 * Drives the per-class candidate-arm table in [armCandidatesFor].
 */
enum class DiagnosticClass {
    DnsBlock,
    SniTlsSuspect,
    QuicBlockSuspect,
    IpBlockSuspect,
    Unknown,
}

/**
 * Candidate diagnostic arm identifiers A0–A10.
 *
 * Only A1–A10 appear in the per-class tables; A0 is reserved for the
 * passive-observation phase (Phase 0) and is never dispatched by the
 * ranked-arm executor.
 */
enum class CandidateArm {
    A0,
    A1,
    A2,
    A3,
    A4,
    A5,
    A6,
    A7,
    A8,
    A9,
    A10,
}

/**
 * Hard limits for a single orchestrator run.
 *
 * All four limits are enforced independently; the first one hit stops the
 * Phase 4 arm-execution loop.
 *
 * @param maxActiveArms      maximum number of arms that may be launched.
 * @param maxElapsedMs       wall-clock budget in milliseconds.
 * @param maxProbeBytes      total byte budget across all probe attempts.
 * @param stopOnFirstStableSuccess  when true, Phase 4 exits immediately on
 *                           the first arm that reports a stable success.
 */
data class AttemptBudget(
    val maxActiveArms: Int = 5,
    val maxElapsedMs: Long = 6_000L,
    val maxProbeBytes: Long = 65_536L,
    val stopOnFirstStableSuccess: Boolean = true,
)

/**
 * The single result emitted by one orchestrator run.
 *
 * [verdict] is always present — one orchestrator run produces exactly one
 * [DirectModeVerdict] variant.  A run where no arm reached a stable success
 * (including a budget-exhausted run) yields a `NO_DIRECT_SOLUTION` verdict with
 * a structured reason code derived from the diagnostic class.
 *
 * [armsExecuted] is the ordered list of arms that were actually launched
 * (may be shorter than the ranked list when the budget cut the run short).
 *
 * [stableSuccessReached] is true when Phase 4 exited via the
 * [AttemptBudget.stopOnFirstStableSuccess] rule.
 */
data class OrchestratorResult(
    val diagnosticClass: DiagnosticClass,
    val rankedArms: List<CandidateArm>,
    val armsExecuted: List<CandidateArm>,
    val verdict: DirectModeVerdict,
    val stableSuccessReached: Boolean,
    val ownedStackPinConfirmed: Boolean,
)

/**
 * Maps a finished Phase 4 execution into the single [DirectModeVerdict] for the
 * run.  A transparent stable success wins over an owned-stack-only success;
 * when neither was reached the verdict is `NO_DIRECT_SOLUTION` with a reason
 * code derived from [diagnosticClass].
 *
 * This is pure outcome-to-verdict mapping — the dispatcher contains no
 * detection logic; the success flags are produced by the injected arm executor.
 */
internal fun deriveOrchestratorVerdict(
    diagnosticClass: DiagnosticClass,
    transparentSuccess: Boolean,
    ownedStackSuccess: Boolean,
): DirectModeVerdict {
    val transportClass = diagnosticClass.toDirectTransportClass()
    return when {
        transparentSuccess -> {
            DirectModeVerdict(
                result = DirectModeVerdictResult.TRANSPARENT_WORKS,
                transportClass = transportClass,
            )
        }

        ownedStackSuccess -> {
            DirectModeVerdict(
                result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                reasonCode = DirectModeReasonCode.OWNED_STACK_REQUIRED,
                transportClass = transportClass,
            )
        }

        else -> {
            DirectModeVerdict(
                result = DirectModeVerdictResult.NO_DIRECT_SOLUTION,
                reasonCode = diagnosticClass.noDirectSolutionReason(),
                transportClass = transportClass,
            )
        }
    }
}

private fun DiagnosticClass.toDirectTransportClass(): DirectTransportClass? =
    when (this) {
        DiagnosticClass.SniTlsSuspect -> DirectTransportClass.SNI_TLS_SUSPECT
        DiagnosticClass.QuicBlockSuspect -> DirectTransportClass.QUIC_BLOCK_SUSPECT
        DiagnosticClass.IpBlockSuspect -> DirectTransportClass.IP_BLOCK_SUSPECT
        DiagnosticClass.DnsBlock, DiagnosticClass.Unknown -> null
    }

private fun DiagnosticClass.noDirectSolutionReason(): DirectModeReasonCode =
    when (this) {
        DiagnosticClass.IpBlockSuspect -> DirectModeReasonCode.IP_BLOCKED
        DiagnosticClass.QuicBlockSuspect -> DirectModeReasonCode.QUIC_BLOCKED
        DiagnosticClass.SniTlsSuspect -> DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
        DiagnosticClass.DnsBlock, DiagnosticClass.Unknown -> DirectModeReasonCode.UNKNOWN_DIRECT_FAILURE
    }

/**
 * Constant per-class candidate-arm table.
 *
 * This is pure data — no control flow, no detection logic.  The table is the
 * single source of truth for which arms are eligible per blocking class.
 */
val ARM_CANDIDATES: Map<DiagnosticClass, List<CandidateArm>> =
    mapOf(
        DiagnosticClass.DnsBlock to
            listOf(
                CandidateArm.A1,
                CandidateArm.A3,
                CandidateArm.A4,
                CandidateArm.A5,
                CandidateArm.A6,
                CandidateArm.A10,
                CandidateArm.A9,
            ),
        DiagnosticClass.SniTlsSuspect to
            listOf(
                CandidateArm.A3,
                CandidateArm.A5,
                CandidateArm.A6,
                CandidateArm.A7,
                CandidateArm.A8,
                CandidateArm.A10,
                CandidateArm.A9,
            ),
        DiagnosticClass.QuicBlockSuspect to
            listOf(
                CandidateArm.A3,
                CandidateArm.A4,
                CandidateArm.A5,
                CandidateArm.A6,
                CandidateArm.A9,
            ),
        DiagnosticClass.IpBlockSuspect to
            listOf(
                CandidateArm.A10,
                CandidateArm.A9,
            ),
        DiagnosticClass.Unknown to
            listOf(
                CandidateArm.A1,
                CandidateArm.A3,
                CandidateArm.A4,
                CandidateArm.A5,
                CandidateArm.A9,
            ),
    )

/** Returns the ordered candidate-arm list for [cls] from the constant table. */
fun armCandidatesFor(cls: DiagnosticClass): List<CandidateArm> = ARM_CANDIDATES.getValue(cls)

/**
 * Combines the DNS-classifier output (Phase 1) and transport-class output
 * (Phase 2) into the single [DiagnosticClass] that drives arm selection.
 *
 * Rule: DNS poisoning takes precedence over all transport signals; otherwise
 * the transport class determines the diagnostic class.
 */
internal fun combineToDiagnosticClass(
    dns: DnsClassification,
    transport: TransportClass,
): DiagnosticClass =
    when {
        dns == DnsClassification.POISONED -> DiagnosticClass.DnsBlock
        transport == TransportClass.SniTlsSuspect -> DiagnosticClass.SniTlsSuspect
        transport == TransportClass.QuicBlockSuspect -> DiagnosticClass.QuicBlockSuspect
        transport == TransportClass.IpBlockSuspect -> DiagnosticClass.IpBlockSuspect
        else -> DiagnosticClass.Unknown
    }
