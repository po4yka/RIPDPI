package com.poyka.ripdpi.diagnostics.orchestrator

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
 * [verdict] is null only when the budget was exhausted before any arm
 * produced a conclusive outcome.
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
    val verdict: DirectModeVerdict?,
    val stableSuccessReached: Boolean,
    val ownedStackPinConfirmed: Boolean,
)

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
