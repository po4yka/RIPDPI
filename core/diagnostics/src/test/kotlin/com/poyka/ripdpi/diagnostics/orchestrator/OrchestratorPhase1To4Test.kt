package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies that DirectModeOrchestrator runs Phase 1 → 2 → 3 → 4 in order
 * and emits exactly one OrchestratorResult per invocation.
 */
class OrchestratorPhase1To4Test {
    @Test
    fun `run executes all four phases and returns one result`() =
        runTest {
            val callOrder = mutableListOf<String>()

            val dns = StubDnsClassifier(DnsClassification.CLEAN) { callOrder += "phase1-dns" }
            val transport = StubTransportClassifier(TransportClass.SniTlsSuspect) { callOrder += "phase2-transport" }
            val ranker = StubArmRanker { callOrder += "phase3-rank" }
            val executor = StubArmExecutor(stableSuccess = true, probeBytes = 100L) { callOrder += "phase4-execute" }

            val orchestrator =
                DirectModeOrchestrator(
                    dnsClassifier = dns,
                    transportClassifier = transport,
                    armRanker = ranker,
                    armExecutor = executor,
                    ownedStackPinGuard = ConfirmedPinGuard(),
                    budget = AttemptBudget(maxActiveArms = 1, stopOnFirstStableSuccess = true),
                )

            val result = orchestrator.run()

            assertNotNull("run must return a non-null OrchestratorResult", result)
            assertEquals(DiagnosticClass.SniTlsSuspect, result.diagnosticClass)

            val phaseOrder = callOrder.filter { it.startsWith("phase") }
            assertEquals("phase1-dns", phaseOrder[0])
            assertEquals("phase2-transport", phaseOrder[1])
            assertEquals("phase3-rank", phaseOrder[2])
            assertEquals("phase4-execute", phaseOrder[3])
        }

    @Test
    fun `dns poisoning overrides transport class to DnsBlock`() =
        runTest {
            val orchestrator =
                DirectModeOrchestrator(
                    dnsClassifier = StubDnsClassifier(DnsClassification.POISONED),
                    transportClassifier = StubTransportClassifier(TransportClass.SniTlsSuspect),
                    armRanker = IdentityArmRanker(),
                    armExecutor = NeverSucceedExecutor(),
                    ownedStackPinGuard = ConfirmedPinGuard(),
                    budget = AttemptBudget(maxActiveArms = 1),
                )

            val result = orchestrator.run()
            assertEquals(DiagnosticClass.DnsBlock, result.diagnosticClass)
        }

    @Test
    fun `clean dns with transparent transport produces Unknown class`() =
        runTest {
            val orchestrator =
                DirectModeOrchestrator(
                    dnsClassifier = StubDnsClassifier(DnsClassification.CLEAN),
                    transportClassifier = StubTransportClassifier(TransportClass.Transparent),
                    armRanker = IdentityArmRanker(),
                    armExecutor = NeverSucceedExecutor(),
                    ownedStackPinGuard = ConfirmedPinGuard(),
                    budget = AttemptBudget(maxActiveArms = 1),
                )

            val result = orchestrator.run()
            assertEquals(DiagnosticClass.Unknown, result.diagnosticClass)
        }

    @Test
    fun `result contains ranked arms from phase 3`() =
        runTest {
            val orchestrator =
                DirectModeOrchestrator(
                    dnsClassifier = StubDnsClassifier(DnsClassification.CLEAN),
                    transportClassifier = StubTransportClassifier(TransportClass.QuicBlockSuspect),
                    armRanker = IdentityArmRanker(),
                    armExecutor = NeverSucceedExecutor(),
                    ownedStackPinGuard = ConfirmedPinGuard(),
                    budget = AttemptBudget(maxActiveArms = 0),
                )

            val result = orchestrator.run()
            assertEquals(
                armCandidatesFor(DiagnosticClass.QuicBlockSuspect),
                result.rankedArms,
            )
        }
}
