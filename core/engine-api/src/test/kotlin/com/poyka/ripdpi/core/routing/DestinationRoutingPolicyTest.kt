package com.poyka.ripdpi.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DestinationRoutingPolicyTest {
    @Test
    fun `snapshot defensively copies and prevents collection mutation`() {
        val sourceDomains =
            mutableListOf(
                DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, "example.com"),
            )
        val rule =
            DestinationRoutingRule(
                action = DestinationRoutingAction.DIRECT,
                network = DestinationRoutingNetwork.TCP,
                domains = sourceDomains,
            )
        val sourceRules = mutableListOf(rule)
        val policy = DestinationRoutingPolicy(sourceRules, canonicalDigest = "digest")

        sourceDomains.clear()
        sourceRules.clear()

        assertEquals(1, policy.rules.size)
        assertEquals(
            1,
            policy.rules
                .single()
                .domains.size,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (policy.rules as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (policy.rules.single().domains as MutableList).clear()
        }
    }
}
