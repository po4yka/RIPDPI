package com.poyka.ripdpi.diagnostics.orchestrator

/**
 * Runs the direct-mode diagnostic sequence for a single session.
 *
 * Phase 0 – Passive observation of the last failed flow (delegates to
 *           [passiveObserver]; no-op when [lastFailedFlow] is null).
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
    private val passiveObserver: PassiveObserver = PassiveObserver(),
    private val lastFailedFlow: LastFailedFlow? = null,
) {
    /**
     * Executes Phases 0–4 and returns exactly one [OrchestratorResult].
     */
    suspend fun run(): OrchestratorResult {
        val startMs = clock()

        // Phase 0: Passive observation (free on success paths — no flow to observe).
        val observation = passiveObserver.observe(lastFailedFlow)

        // Phase 1: DNS classification, seeded by the passive observation
        val dnsClass = dnsClassifier.classify(observation)

        // Phase 2: Transport classification, seeded by the passive observation
        val transportClass = transportClassifier.classify(observation)

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
            verdict =
                deriveOrchestratorVerdict(
                    diagnosticClass = diagnosticClass,
                    transparentSuccess = state.transparentSuccess,
                    ownedStackSuccess = state.ownedStackSuccess,
                ),
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

    /** A stable success was reached by a transparent-mode arm (no owned-stack pin). */
    var transparentSuccess = false
        private set

    /** A stable success was reached by an owned-stack arm with a confirmed pin. */
    var ownedStackSuccess = false
        private set

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
            if (result.requiresOwnedStackPin) ownedStackSuccess = true else transparentSuccess = true
            earlyStopTriggered = budget.stopOnFirstStableSuccess
        }
    }
}
