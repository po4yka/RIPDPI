package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.fleet.FleetCompatHarness
import com.poyka.ripdpi.data.fleet.FleetFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-file regression suite that locks the client <-> ripdpi-vpn-deploy
 * interface. Every fixture under `src/test/resources/fleet-fixtures/<scenario>/`
 * is a literal `emit-singbox.sh`-style bundle plus its `expected-*.json`
 * snapshots. The harness imports `bundle.json` through the phase-1 production
 * parsers and diffs the resulting profiles / group / routing against the
 * expectations with a readable structural diff.
 *
 * A deployer schema change that the parsers silently absorb makes this suite
 * go red.
 */
class FleetCompatGoldenFileTest {
    private val scenarios =
        listOf(
            "p0-only",
            "p1-only",
            "p2a-hysteria-only",
            "p2a-hysteria-port-hop",
            "multi-cohort-p0-p1-p2a",
            "multi-host-failover",
            "per-app-bypass-and-via-tun",
            "bootstrap-bundle",
        )

    @Test
    fun `every fleet fixture scenario directory loads`() {
        scenarios.forEach { scenario ->
            val fixture = FleetFixture.load(scenario)
            assertTrue(
                "fixture $scenario must carry a non-empty bundle.json",
                fixture.bundleJson.isNotBlank(),
            )
            assertTrue(
                "fixture $scenario must carry expected-profiles.json",
                fixture.expectedProfilesJson.isNotBlank(),
            )
        }
    }

    @Test
    fun `p0-only REALITY Vision bundle imports to the expected single VLESS profile`() {
        val result = FleetCompatHarness.run("p0-only")
        assertTrue(result.diff, result.matches)
        assertEquals(1, result.importedProfiles.size)
    }

    @Test
    fun `p1-only XHTTP plain TLS bundle imports to the expected VLESS profile`() {
        val result = FleetCompatHarness.run("p1-only")
        assertTrue(result.diff, result.matches)
    }

    @Test
    fun `p2a hysteria-only bundle imports to a Hysteria2 profile`() {
        val result = FleetCompatHarness.run("p2a-hysteria-only")
        assertTrue(result.diff, result.matches)
    }

    @Test
    fun `p2a hysteria port-hop bundle is rejected until native projection supports it`() {
        val result = FleetCompatHarness.run("p2a-hysteria-port-hop")
        assertTrue(result.diff, result.matches)
        assertTrue(result.importedProfiles.isEmpty())
    }

    @Test
    fun `multi-cohort p0 p1 p2a bundle imports three transports with a selector group`() {
        val result = FleetCompatHarness.run("multi-cohort-p0-p1-p2a")
        assertTrue(result.diff, result.matches)
        assertEquals(3, result.importedProfiles.size)
        assertTrue("a selector group is expected", result.importedGroup != null)
    }

    @Test
    fun `multi-host failover bundle imports two hosts worth of transports with selector and urltest`() {
        val result = FleetCompatHarness.run("multi-host-failover")
        assertTrue(result.diff, result.matches)
        assertTrue("a selector group is expected", result.importedGroup != null)
    }

    @Test
    fun `per-app bypass and via-tun bundle imports routing rules`() {
        val result = FleetCompatHarness.run("per-app-bypass-and-via-tun")
        assertTrue(result.diff, result.matches)
        assertTrue("routing rules are expected", result.importedRoutingTags.isNotEmpty())
    }

    @Test
    fun `bootstrap bundle served from a faked HTTP backend consumes one-shot`() {
        val result = FleetCompatHarness.run("bootstrap-bundle")
        assertTrue(result.diff, result.matches)
        // The bootstrap path is one-shot: a second consume of the same token fails.
        assertTrue("bootstrap token should be single-use", result.bootstrapConsumedOnce)
    }

    @Test
    fun `no fixture bundle contains a production-token shape`() {
        // Guard test: every bundle.json credential must be a frozen test value.
        scenarios.forEach { scenario ->
            val fixture = FleetFixture.load(scenario)
            val offenders = FleetCompatHarness.findProductionTokenShapes(fixture.bundleJson)
            assertEquals(
                "fixture $scenario leaks a production-token shape: $offenders",
                emptyList<String>(),
                offenders,
            )
        }
    }

    @Test
    fun `a deliberately broken parser input makes the harness report a readable diff`() {
        // Regression proof: feeding a structurally mutated bundle must surface a
        // mismatch with a non-empty, human-readable diff.
        val result =
            FleetCompatHarness.runWithMutation("p0-only") { bundle ->
                bundle.replace("\"server\"", "\"sErVeR_TYPO\"")
            }
        assertFalse("a mutated bundle must not silently match", result.matches)
        assertTrue("the diff must be readable", result.diff.isNotBlank())
    }

    @Test
    fun `round-trip re-export of an imported bundle stays within documented allowed deltas`() {
        scenarios.forEach { scenario ->
            val result = FleetCompatHarness.run(scenario)
            assertTrue(
                "round-trip mismatch for $scenario: ${result.roundTripDiff}",
                result.roundTripWithinAllowedDeltas,
            )
        }
    }
}
