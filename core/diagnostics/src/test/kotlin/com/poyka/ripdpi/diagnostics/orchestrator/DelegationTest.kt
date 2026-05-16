package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that DirectModeOrchestrator delegates to injected subsystems for
 * all detection work and contains none of that logic itself.
 *
 * Each test asserts that the relevant collaborator was called, and that the
 * orchestrator's own source file contains no detection keywords.
 */
class DelegationTest {
    @Test
    fun `dns classifier is called exactly once per run`() =
        runTest {
            var callCount = 0
            val dns = StubDnsClassifier(DnsClassification.CLEAN) { callCount++ }
            buildOrchestrator(dns = dns).run()
            assertEquals(1, callCount)
        }

    @Test
    fun `transport classifier is called exactly once per run`() =
        runTest {
            var callCount = 0
            val transport = StubTransportClassifier(TransportClass.Unknown) { callCount++ }
            buildOrchestrator(transport = transport).run()
            assertEquals(1, callCount)
        }

    @Test
    fun `arm ranker is called exactly once per run`() =
        runTest {
            var callCount = 0
            val ranker = StubArmRanker { callCount++ }
            buildOrchestrator(ranker = ranker, budget = AttemptBudget(maxActiveArms = 0)).run()
            assertEquals(1, callCount)
        }

    @Test
    fun `arm executor is called for each launched arm`() =
        runTest {
            val executor = StubArmExecutor(stableSuccess = false, probeBytes = 0L)
            buildOrchestrator(
                executor = executor,
                budget = AttemptBudget(maxActiveArms = 3, stopOnFirstStableSuccess = false),
            ).run()
            assertEquals(3, executor.callCount)
        }

    @Test
    fun `orchestrator source does not contain DNS detection keywords`() {
        val source = loadOrchestratorSource()
        val forbidden = listOf("POISONED", "ECH_CAPABLE", "DIVERGENT", "dnsAnswerClass", "dnsIntegrity")
        val found = forbidden.filter { source.contains(it) }
        assertTrue(
            "Orchestrator must not contain DNS detection logic, found: $found",
            found.isEmpty(),
        )
    }

    @Test
    fun `orchestrator source does not contain TLS or QUIC detection keywords`() {
        val source = loadOrchestratorSource()
        val forbidden =
            listOf(
                "tls_handshake_failed",
                "quic_error",
                "tls_ech_only",
                "strategy_quic",
                "strategy_https",
                "DnsIntegrityChecker",
                "QuicH3FingerprintProbe",
            )
        val found = forbidden.filter { source.contains(it) }
        assertTrue(
            "Orchestrator must not contain TLS/QUIC detection logic, found: $found",
            found.isEmpty(),
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildOrchestrator(
        dns: DnsClassifier = StubDnsClassifier(DnsClassification.CLEAN),
        transport: TransportPolicyClassifier = StubTransportClassifier(TransportClass.Unknown),
        ranker: ArmRanker = IdentityArmRanker(),
        executor: ArmExecutor = NeverSucceedExecutor(),
        budget: AttemptBudget = AttemptBudget(maxActiveArms = 1, stopOnFirstStableSuccess = false),
    ): DirectModeOrchestrator =
        DirectModeOrchestrator(
            dnsClassifier = dns,
            transportClassifier = transport,
            armRanker = ranker,
            armExecutor = executor,
            ownedStackPinGuard = ConfirmedPinGuard(),
            budget = budget,
        )

    private fun loadOrchestratorSource(): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = java.io.File(userDir).absoluteFile
        while (!java.io.File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: error("Repo root not found from $userDir")
        }
        val src =
            java.io.File(
                current,
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/orchestrator/DirectModeOrchestrator.kt",
            )
        assertTrue("Orchestrator source must exist: ${src.path}", src.exists())
        return src.readText()
    }
}
