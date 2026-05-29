package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration tests driving [DirectModeOrchestrator] through Phases
 * 1–4 to each [DirectModeVerdictResult] variant and each transport class.
 *
 * Deterministic by construction: a frozen clock (so the wall-clock budget never
 * trips on timing jitter) and a scripted arm executor (no real network). One
 * scenario per result class, plus per-transport-class coverage and the
 * confirm_once owned-stack gate.
 */
class DiagnosticResultClassIntegrationTest {
    private val frozenClock: () -> Long = { 0L }

    private fun orchestrator(
        dns: DnsClassification,
        transport: TransportClass,
        executor: ArmExecutor,
        pinGuard: OwnedStackPinGuard = ConfirmedPinGuard(),
        budget: AttemptBudget = AttemptBudget(),
    ): DirectModeOrchestrator =
        DirectModeOrchestrator(
            dnsClassifier = StubDnsClassifier(dns),
            transportClassifier = StubTransportClassifier(transport),
            armRanker = IdentityArmRanker(),
            armExecutor = executor,
            ownedStackPinGuard = pinGuard,
            budget = budget,
            clock = frozenClock,
        )

    // ---- TRANSPARENT_WORKS, one per transparent-resolvable class ----

    @Test
    fun `dns block resolved by a transparent arm yields TRANSPARENT_WORKS`() =
        runTest {
            // DnsBlock candidate list starts with A1; succeed on it transparently.
            val executor = ScriptedArmExecutor(succeedingArms = setOf(CandidateArm.A1))
            val result =
                orchestrator(
                    dns = DnsClassification.POISONED,
                    transport = TransportClass.Unknown,
                    executor = executor,
                ).run()

            assertEquals(DiagnosticClass.DnsBlock, result.diagnosticClass)
            assertEquals(DirectModeVerdictResult.TRANSPARENT_WORKS, result.verdict.result)
            assertNull(result.verdict.reasonCode)
            assertTrue(result.stableSuccessReached)
            assertEquals(listOf(CandidateArm.A1), executor.executedArms)
        }

    @Test
    fun `sni tls suspect resolved by a transparent arm yields TRANSPARENT_WORKS`() =
        runTest {
            val executor = ScriptedArmExecutor(succeedingArms = setOf(CandidateArm.A3))
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.SniTlsSuspect,
                    executor = executor,
                ).run()

            assertEquals(DiagnosticClass.SniTlsSuspect, result.diagnosticClass)
            assertEquals(DirectModeVerdictResult.TRANSPARENT_WORKS, result.verdict.result)
            assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, result.verdict.transportClass)
        }

    @Test
    fun `quic block suspect resolved by a transparent arm yields TRANSPARENT_WORKS`() =
        runTest {
            val executor = ScriptedArmExecutor(succeedingArms = setOf(CandidateArm.A4))
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.QuicBlockSuspect,
                    executor = executor,
                ).run()

            assertEquals(DiagnosticClass.QuicBlockSuspect, result.diagnosticClass)
            assertEquals(DirectModeVerdictResult.TRANSPARENT_WORKS, result.verdict.result)
            assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, result.verdict.transportClass)
        }

    // ---- OWNED_STACK_ONLY ----

    @Test
    fun `ip block suspect resolved only by an owned-stack arm yields OWNED_STACK_ONLY`() =
        runTest {
            // IpBlockSuspect candidate list is [A10, A9], both owned-stack.
            val executor =
                ScriptedArmExecutor(
                    succeedingArms = setOf(CandidateArm.A10),
                    ownedStackArms = setOf(CandidateArm.A9, CandidateArm.A10),
                )
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.IpBlockSuspect,
                    executor = executor,
                ).run()

            assertEquals(DiagnosticClass.IpBlockSuspect, result.diagnosticClass)
            assertEquals(DirectModeVerdictResult.OWNED_STACK_ONLY, result.verdict.result)
            assertEquals(DirectModeReasonCode.OWNED_STACK_REQUIRED, result.verdict.reasonCode)
            assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, result.verdict.transportClass)
            assertTrue(result.ownedStackPinConfirmed)
        }

    @Test
    fun `sni transparent arms fail and an owned-stack arm wins yields OWNED_STACK_ONLY`() =
        runTest {
            // SniTlsSuspect: [A3, A5, A6, A7, A8, A10, A9]; only A10 (owned-stack) succeeds.
            // Needs a budget large enough to reach A10 at index 5.
            val executor =
                ScriptedArmExecutor(
                    succeedingArms = setOf(CandidateArm.A10),
                    ownedStackArms = setOf(CandidateArm.A9, CandidateArm.A10),
                )
            val budget = AttemptBudget(maxActiveArms = 7)
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.SniTlsSuspect,
                    executor = executor,
                    budget = budget,
                ).run()

            assertEquals(DirectModeVerdictResult.OWNED_STACK_ONLY, result.verdict.result)
            // All five transparent arms ran and failed before the owned-stack arm won.
            assertTrue(executor.executedArms.containsAll(listOf(CandidateArm.A3, CandidateArm.A8)))
            assertTrue(result.armsExecuted.size <= budget.maxActiveArms)
        }

    // ---- NO_DIRECT_SOLUTION ----

    @Test
    fun `all arms failing within budget yields NO_DIRECT_SOLUTION with class reason`() =
        runTest {
            val executor = ScriptedArmExecutor(succeedingArms = emptySet())
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.QuicBlockSuspect,
                    executor = executor,
                ).run()

            assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, result.verdict.result)
            assertEquals(DirectModeReasonCode.QUIC_BLOCKED, result.verdict.reasonCode)
            assertFalse(result.stableSuccessReached)
        }

    @Test
    fun `ip block with no winning arm yields NO_DIRECT_SOLUTION IP_BLOCKED`() =
        runTest {
            val executor = ScriptedArmExecutor(succeedingArms = emptySet())
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.IpBlockSuspect,
                    executor = executor,
                ).run()

            assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, result.verdict.result)
            assertEquals(DirectModeReasonCode.IP_BLOCKED, result.verdict.reasonCode)
        }

    @Test
    fun `unconfirmed owned-stack pin gates the win and yields NO_DIRECT_SOLUTION`() =
        runTest {
            // A10 would succeed but requires an owned-stack pin the user has not confirmed.
            val executor =
                ScriptedArmExecutor(
                    succeedingArms = setOf(CandidateArm.A10),
                    ownedStackArms = setOf(CandidateArm.A9, CandidateArm.A10),
                )
            val result =
                orchestrator(
                    dns = DnsClassification.CLEAN,
                    transport = TransportClass.IpBlockSuspect,
                    executor = executor,
                    pinGuard = FixedPinGuard(confirmed = false),
                ).run()

            assertEquals(DirectModeVerdictResult.NO_DIRECT_SOLUTION, result.verdict.result)
            assertEquals(DirectModeReasonCode.IP_BLOCKED, result.verdict.reasonCode)
        }

    // ---- Budget enforcement holds across every scenario ----

    @Test
    fun `no scenario exceeds the configured arm cap`() =
        runTest {
            val cap = AttemptBudget(maxActiveArms = 3, stopOnFirstStableSuccess = false)
            val executor = ScriptedArmExecutor(succeedingArms = emptySet())
            val result =
                orchestrator(
                    dns = DnsClassification.POISONED,
                    transport = TransportClass.Unknown,
                    executor = executor,
                    budget = cap,
                ).run()

            assertEquals(3, result.armsExecuted.size)
            assertEquals(3, executor.executedArms.size)
        }
}
