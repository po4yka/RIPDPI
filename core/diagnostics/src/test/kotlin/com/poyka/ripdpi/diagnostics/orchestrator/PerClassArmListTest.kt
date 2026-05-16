package com.poyka.ripdpi.diagnostics.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that each DiagnosticClass maps to the exact candidate-arm list
 * specified in the plan.
 */
class PerClassArmListTest {
    @Test
    fun `DnsBlock arm list matches plan exactly`() {
        assertEquals(
            listOf(
                CandidateArm.A1,
                CandidateArm.A3,
                CandidateArm.A4,
                CandidateArm.A5,
                CandidateArm.A6,
                CandidateArm.A10,
                CandidateArm.A9,
            ),
            armCandidatesFor(DiagnosticClass.DnsBlock),
        )
    }

    @Test
    fun `SniTlsSuspect arm list matches plan exactly`() {
        assertEquals(
            listOf(
                CandidateArm.A3,
                CandidateArm.A5,
                CandidateArm.A6,
                CandidateArm.A7,
                CandidateArm.A8,
                CandidateArm.A10,
                CandidateArm.A9,
            ),
            armCandidatesFor(DiagnosticClass.SniTlsSuspect),
        )
    }

    @Test
    fun `QuicBlockSuspect arm list matches plan exactly`() {
        assertEquals(
            listOf(
                CandidateArm.A3,
                CandidateArm.A4,
                CandidateArm.A5,
                CandidateArm.A6,
                CandidateArm.A9,
            ),
            armCandidatesFor(DiagnosticClass.QuicBlockSuspect),
        )
    }

    @Test
    fun `IpBlockSuspect arm list matches plan exactly`() {
        assertEquals(
            listOf(
                CandidateArm.A10,
                CandidateArm.A9,
            ),
            armCandidatesFor(DiagnosticClass.IpBlockSuspect),
        )
    }

    @Test
    fun `Unknown arm list matches plan exactly`() {
        assertEquals(
            listOf(
                CandidateArm.A1,
                CandidateArm.A3,
                CandidateArm.A4,
                CandidateArm.A5,
                CandidateArm.A9,
            ),
            armCandidatesFor(DiagnosticClass.Unknown),
        )
    }

    @Test
    fun `all five DiagnosticClass variants have entries in the arm table`() {
        for (cls in DiagnosticClass.entries) {
            val arms = armCandidatesFor(cls)
            assert(arms.isNotEmpty()) {
                "DiagnosticClass.$cls must have at least one candidate arm"
            }
        }
    }
}
