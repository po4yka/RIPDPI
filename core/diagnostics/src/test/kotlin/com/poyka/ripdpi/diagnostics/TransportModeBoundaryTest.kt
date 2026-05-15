package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.shared.ArmStats
import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import com.poyka.ripdpi.diagnostics.shared.TransportMode
import com.poyka.ripdpi.diagnostics.shared.TransportPolicyStub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the transparent-vs-owned-stack mode boundary defined in
 * docs/architecture/transparent-vs-owned-stack-boundary.md.
 *
 * Invariants under test:
 *  1. TransportMode is a sealed type with exactly Transparent and OwnedStack variants.
 *  2. When-exhaustiveness: no transparent-mode arm can be reached from owned-stack path.
 *  3. Shared neutral types (DnsClassification, TransportPolicyStub, ArmStats) live in
 *     com.poyka.ripdpi.diagnostics.shared and carry no Cronet or VpnService references.
 */
class TransportModeBoundaryTest {
    // ---- 1. Sealed-type coverage -----------------------------------------------

    @Test
    fun `TransportMode has Transparent variant`() {
        val mode: TransportMode = TransportMode.Transparent
        assertEquals(TransportMode.Transparent, mode)
    }

    @Test
    fun `TransportMode has OwnedStack variant`() {
        val mode: TransportMode = TransportMode.OwnedStack
        assertEquals(TransportMode.OwnedStack, mode)
    }

    @Test
    fun `when expression over TransportMode is exhaustive`() {
        // If a new variant is added the compiler will flag this as non-exhaustive.
        fun describeMode(mode: TransportMode): String =
            when (mode) {
                TransportMode.Transparent -> "transparent"
                TransportMode.OwnedStack -> "owned-stack"
            }

        assertEquals("transparent", describeMode(TransportMode.Transparent))
        assertEquals("owned-stack", describeMode(TransportMode.OwnedStack))
    }

    // ---- 2. Mode-selection invariant -------------------------------------------

    @Test
    fun `transparent arm cannot be invoked from owned-stack path`() {
        // The TransportPolicyStub.modeFor helper uses the sealed when-expression;
        // owned-stack input must never return Transparent.
        val policy = TransportPolicyStub(requiresEch = true, requiresTunProtect = false)
        val mode = policy.resolveMode()
        assertEquals(TransportMode.OwnedStack, mode)
        assertFalse(
            "owned-stack policy must not resolve to Transparent",
            mode == TransportMode.Transparent,
        )
    }

    @Test
    fun `transparent arm is reachable only when tun-protect flag is set`() {
        val policy = TransportPolicyStub(requiresEch = false, requiresTunProtect = true)
        val mode = policy.resolveMode()
        assertEquals(TransportMode.Transparent, mode)
        assertFalse(
            "transparent policy must not resolve to OwnedStack",
            mode == TransportMode.OwnedStack,
        )
    }

    @Test
    fun `policy with neither ECH nor tun-protect defaults to Transparent`() {
        val policy = TransportPolicyStub(requiresEch = false, requiresTunProtect = false)
        assertEquals(TransportMode.Transparent, policy.resolveMode())
    }

    // ---- 3. Shared neutral-type package invariants -----------------------------

    @Test
    fun `DnsClassification is in shared package`() {
        val pkg = DnsClassification::class.java.packageName
        assertTrue(
            "DnsClassification must be in *.diagnostics.shared, got: $pkg",
            pkg.endsWith("diagnostics.shared"),
        )
    }

    @Test
    fun `TransportPolicyStub is in shared package`() {
        val pkg = TransportPolicyStub::class.java.packageName
        assertTrue(
            "TransportPolicyStub must be in *.diagnostics.shared, got: $pkg",
            pkg.endsWith("diagnostics.shared"),
        )
    }

    @Test
    fun `ArmStats is in shared package`() {
        val pkg = ArmStats::class.java.packageName
        assertTrue(
            "ArmStats must be in *.diagnostics.shared, got: $pkg",
            pkg.endsWith("diagnostics.shared"),
        )
    }

    @Test
    fun `DnsClassification variants cover expected classifications`() {
        val names = DnsClassification.entries.map { it.name }
        assertTrue(names.contains("CLEAN"))
        assertTrue(names.contains("POISONED"))
        assertTrue(names.contains("ECH_CAPABLE"))
    }

    @Test
    fun `ArmStats accumulates success and failure counts`() {
        val stats = ArmStats(successCount = 3, failureCount = 1)
        assertEquals(3, stats.successCount)
        assertEquals(1, stats.failureCount)
        assertEquals(4, stats.totalAttempts)
        assertNotNull(stats.successRate)
        assertEquals(0.75, stats.successRate!!, 0.001)
    }

    @Test
    fun `ArmStats zero attempts has null success rate`() {
        val stats = ArmStats(successCount = 0, failureCount = 0)
        assertEquals(0, stats.totalAttempts)
        assertEquals(null, stats.successRate)
    }

    // ---- 4. Shared-type source has no Cronet or VpnService imports -------------

    @Test
    fun `shared package source files contain no Cronet or VpnService references`() {
        // Locate the shared source directory relative to the repo root.
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = java.io.File(userDir).absoluteFile
        while (!java.io.File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: error("Repo root not found from $userDir")
        }
        val sharedSrc =
            java.io.File(
                current,
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/shared",
            )
        assertTrue("shared source directory must exist: ${sharedSrc.path}", sharedSrc.exists())

        val violations =
            sharedSrc
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { lineIdx, line ->
                        // Only flag actual import statements, not KDoc comments that
                        // describe what is intentionally absent.
                        val trimmed = line.trimStart()
                        val low = trimmed.lowercase()
                        if (trimmed.startsWith("import ") &&
                            (low.contains("cronet") || low.contains("vpnservice"))
                        ) {
                            "${file.name}:${lineIdx + 1}: $line"
                        } else {
                            null
                        }
                    }
                }.toList()

        assertTrue(
            "shared/ must not import Cronet or VpnService:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
