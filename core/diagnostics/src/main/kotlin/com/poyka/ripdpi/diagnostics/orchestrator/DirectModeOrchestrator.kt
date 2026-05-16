package com.poyka.ripdpi.diagnostics.orchestrator

/**
 * Runs the four-phase direct-mode diagnostic sequence for a single session.
 *
 * Phase 1 – DNS classification (delegates to [dnsClassifier]).
 * Phase 2 – Transport classification (delegates to [transportClassifier]).
 * Phase 3 – Arm ranking (delegates to [armRanker]).
 * Phase 4 – Arm execution under [budget] with early-stop and confirm_once.
 *
 * The orchestrator is a pure dispatcher: it contains no DNS, TLS, QUIC, or
 * owned-stack detection logic.  All such work is performed by the injected
 * subsystem collaborators.
 */
class DirectModeOrchestrator(
    private val dnsClassifier: DnsClassifier,
    private val transportClassifier: TransportPolicyClassifier,
    private val armRanker: ArmRanker,
    private val armExecutor: ArmExecutor,
    private val ownedStackPinGuard: OwnedStackPinGuard,
    private val budget: AttemptBudget = AttemptBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Executes Phases 1–4 and returns exactly one [OrchestratorResult].
     */
    suspend fun run(): OrchestratorResult {
        val startMs = clock()

        // Phase 1: DNS classification
        val dnsClass = dnsClassifier.classify()

        // Phase 2: Transport classification
        val transportClass = transportClassifier.classify()

        // Combine Phase 1 + Phase 2 into a DiagnosticClass
        val diagnosticClass = combineToDiagnosticClass(dnsClass, transportClass)

        // Phase 3: Arm ranking
        val candidates = armCandidatesFor(diagnosticClass)
        val rankedArms = armRanker.rank(candidates)

        // Phase 4: Execute under budget
        return executeUnderBudget(startMs, diagnosticClass, rankedArms)
    }

    private suspend fun executeUnderBudget(
        startMs: Long,
        diagnosticClass: DiagnosticClass,
        rankedArms: List<CandidateArm>,
    ): OrchestratorResult {
        val state = ExecutionState()
        for (arm in rankedArms.asSequence().takeWhile { state.canContinue(budget, startMs, clock) }) {
            val result = armExecutor.execute(arm, state.totalBytes)
            state.record(arm, result, ownedStackPinGuard, budget)
        }
        return OrchestratorResult(
            diagnosticClass = diagnosticClass,
            rankedArms = rankedArms,
            armsExecuted = state.executed,
            verdict = null,
            stableSuccessReached = state.stableSuccessReached,
            ownedStackPinConfirmed = state.ownedStackPinConfirmed,
        )
    }
}

private class ExecutionState {
    val executed = mutableListOf<CandidateArm>()
    var totalBytes = 0L
    var stableSuccessReached = false
    var ownedStackPinConfirmed = false
    private var earlyStopTriggered = false

    /**
     * Returns true when the next arm may be launched.
     * Incorporates all three hard budget limits plus the early-stop flag so
     * the for-loop has a single termination point with no explicit break.
     */
    fun canContinue(
        budget: AttemptBudget,
        startMs: Long,
        clock: () -> Long,
    ): Boolean =
        !earlyStopTriggered &&
            executed.size < budget.maxActiveArms &&
            clock() - startMs < budget.maxElapsedMs &&
            totalBytes < budget.maxProbeBytes

    fun record(
        arm: CandidateArm,
        result: ArmExecutionResult,
        pinGuard: OwnedStackPinGuard,
        budget: AttemptBudget,
    ) {
        executed += arm
        totalBytes += result.probeBytes
        val pinConfirmed = result.requiresOwnedStackPin && pinGuard.isConfirmed()
        val pinBlocked = result.requiresOwnedStackPin && !pinGuard.isConfirmed()
        if (pinConfirmed) ownedStackPinConfirmed = true
        if (!pinBlocked && result.stableSuccess) {
            stableSuccessReached = true
            earlyStopTriggered = budget.stopOnFirstStableSuccess
        }
    }
}
