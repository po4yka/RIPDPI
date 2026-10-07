package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.routing.DestinationRoutingRule
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitStrictDnsRuntimePolicyTest {
    @Test
    fun `policies without direct dns capability discard unused underlay inputs`() {
        val policies =
            listOf(
                emptyList(),
                listOf(rule(DestinationRoutingAction.TUNNELED, exact("example.org"))),
                listOf(rule(DestinationRoutingAction.BLOCK, exact("example.org"))),
                listOf(rule(DestinationRoutingAction.DIRECT, exact("example.org"), DestinationRoutingNetwork.TCP)),
                listOf(rule(DestinationRoutingAction.DIRECT, exact("example.org"), DestinationRoutingNetwork.UDP)),
                listOf(
                    rule(
                        DestinationRoutingAction.DIRECT,
                        exact("example.org"),
                        ports = listOf(DestinationPortRange(53, 53)),
                    ),
                ),
                listOf(
                    rule(
                        DestinationRoutingAction.DIRECT,
                        exact("example.org"),
                        ipRanges = listOf(DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24")),
                    ),
                ),
            )

        policies.forEach { rules ->
            val initial = plan(rules, underlay = emptyList())
            val first = plan(rules, underlay = listOf("192.0.2.53"), underlayLeaseGeneration = 17L)
            val warm = plan(rules, underlay = listOf("192.0.2.54"), underlayLeaseGeneration = 18L)
            val invalid = plan(rules, underlay = listOf("underlay.example"), underlayLeaseGeneration = 19L)
            val oversized = plan(rules, underlay = List(17) { "192.0.2.${it + 1}" }, underlayLeaseGeneration = 20L)

            assertEquals(initial.canonicalDigest, first.canonicalDigest)
            assertEquals(first.canonicalDigest, warm.canonicalDigest)
            listOf(initial, first, warm, invalid, oversized).forEach { policy ->
                assertEquals(initial.canonicalDigest, policy.canonicalDigest)
                assertEquals(initial.policyCoverageReason, policy.policyCoverageReason)
                assertTrue(policy.directResolverCandidates.isEmpty())
                assertNull(policy.underlayLeaseGeneration)
                assertEquals(listOf("94.140.14.14"), policy.bootstrapPins)
                assertEquals(rules, policy.destinationRouting.rules)
                assertEquals(initial.decide("example.org"), policy.decide("example.org"))
                assertEquals(initial.decide("unmatched.example"), policy.decide("unmatched.example"))
            }
            val expectedPlane =
                if (rules.firstOrNull()?.action == DestinationRoutingAction.BLOCK) {
                    DnsResolverPlane.BLOCK
                } else {
                    DnsResolverPlane.PROXY
                }
            assertEquals(expectedPlane, warm.decide("example.org").plane)
        }
    }

    @Test
    fun `domainless direct rule retains candidates and underlay generation`() {
        val rules =
            listOf(
                DestinationRoutingRule(
                    action = DestinationRoutingAction.DIRECT,
                    network = DestinationRoutingNetwork.BOTH,
                ),
            )
        val policy = plan(rules, underlay = listOf("192.0.2.53"), underlayLeaseGeneration = 17L)

        assertEquals(listOf("192.0.2.53"), policy.directResolverCandidates)
        assertEquals(17L, policy.underlayLeaseGeneration)
        assertEquals(listOf("94.140.14.14"), policy.bootstrapPins)
        assertEquals(DnsResolverPlane.DIRECT, policy.decide("any.example").plane)
        assertEquals(policy.directResolverCandidates, policy.decide("any.example").resolverCandidates)
    }

    @Test
    fun `projects only domain-only both-network rules in first-match order`() {
        val plan =
            plan(
                rules =
                    listOf(
                        rule(DestinationRoutingAction.TUNNELED, exact("api.example.com")),
                        rule(DestinationRoutingAction.DIRECT, suffix("example.com")),
                        rule(DestinationRoutingAction.BLOCK, exact("blocked.example")),
                    ),
            )

        assertEquals(DnsResolverPlane.PROXY, plan.decide("API.EXAMPLE.COM.").plane)
        assertEquals(DnsResolverPlane.DIRECT, plan.decide("www.example.com").plane)
        assertEquals(listOf("192.0.2.53", "2001:db8:0:0:0:0:0:53"), plan.decide("www.example.com").resolverCandidates)
        assertEquals(DnsResolverPlane.BLOCK, plan.decide("blocked.example").plane)
        assertEquals(DnsResolverPlane.PROXY, plan.decide("notexample.com").plane)
    }

    @Test
    fun `normalizes idna while preserving dns label boundaries`() {
        val plan = plan(rules = listOf(rule(DestinationRoutingAction.DIRECT, suffix("xn--e1afmkfd.xn--p1ai"))))

        assertEquals(DnsResolverPlane.DIRECT, plan.decide("WWW.пример.рф.").plane)
        assertEquals(DnsResolverPlane.PROXY, plan.decide("badпример.рф").plane)
        assertEquals(DnsResolverPlane.PROXY, plan.decide("example..com").plane)
    }

    @Test
    fun `unsupported or mixed matching rules stop conservatively at proxy`() {
        val mixed =
            listOf(
                rule(DestinationRoutingAction.DIRECT, exact("tcp.example"), network = DestinationRoutingNetwork.TCP),
                rule(
                    DestinationRoutingAction.DIRECT,
                    exact("port.example"),
                    ports = listOf(DestinationPortRange(53, 53)),
                ),
                rule(
                    DestinationRoutingAction.DIRECT,
                    exact("cidr.example"),
                    ipRanges = listOf(DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24")),
                ),
            )
        val plan = plan(rules = mixed)

        mixed.map { it.domains.single().value }.forEach { name ->
            assertEquals("mixed_route_constraints", plan.decide(name).coverageReason)
        }

        val geositePlan =
            plan(
                rules =
                    listOf(
                        rule(
                            DestinationRoutingAction.DIRECT,
                            DestinationDomainMatcher(DestinationDomainMatcherKind.GEOSITE, "category-ru"),
                        ),
                    ),
            )
        assertEquals("unsupported_domain_matcher", geositePlan.decide("example.org").coverageReason)

        val domainlessCidrBeforeDirect =
            plan(
                rules =
                    listOf(
                        DestinationRoutingRule(
                            action = DestinationRoutingAction.BLOCK,
                            network = DestinationRoutingNetwork.BOTH,
                            ipRanges = listOf(DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24")),
                        ),
                        rule(DestinationRoutingAction.DIRECT, exact("direct.example")),
                    ),
            )
        assertEquals("mixed_route_constraints", domainlessCidrBeforeDirect.decide("direct.example").coverageReason)

        val domainlessPortBeforeDirect =
            plan(
                rules =
                    listOf(
                        DestinationRoutingRule(
                            action = DestinationRoutingAction.BLOCK,
                            network = DestinationRoutingNetwork.BOTH,
                            destinationPorts = listOf(DestinationPortRange(53, 53)),
                        ),
                        rule(DestinationRoutingAction.DIRECT, exact("direct.example")),
                    ),
            )
        assertEquals("mixed_route_constraints", domainlessPortBeforeDirect.decide("direct.example").coverageReason)
    }

    @Test
    fun `direct rule without validated underlay resolver remains proxy`() {
        val plan =
            plan(rules = listOf(rule(DestinationRoutingAction.DIRECT, exact("direct.example"))), underlay = emptyList())

        val decision = plan.decide("direct.example")

        assertEquals(DnsResolverPlane.PROXY, decision.plane)
        assertEquals("direct_resolver_unavailable", decision.coverageReason)
    }

    @Test
    fun `eligible direct rejects invalid underlay with validation reason and no bootstrap fallback`() {
        val rules = listOf(rule(DestinationRoutingAction.DIRECT, exact("direct.example")))
        val invalidUnderlays =
            listOf(
                listOf("192.0.2.53", "underlay.example") to "direct_resolver_invalid",
                List(17) { "192.0.2.${it + 1}" } to "direct_resolver_list_too_large",
            )

        invalidUnderlays.forEach { (underlay, reason) ->
            val policy = plan(rules, underlay = underlay, underlayLeaseGeneration = 17L)
            val decision = policy.decide("direct.example")

            assertEquals(reason, policy.policyCoverageReason)
            assertEquals(reason, policy.decide("unmatched.example").coverageReason)
            assertTrue(policy.directResolverCandidates.isEmpty())
            assertEquals(listOf("94.140.14.14"), policy.bootstrapPins)
            assertEquals(17L, policy.underlayLeaseGeneration)
            assertEquals(DnsResolverPlane.PROXY, decision.plane)
            assertEquals("direct_resolver_unavailable", decision.coverageReason)
            assertTrue(decision.resolverCandidates.isEmpty())
        }
    }

    @Test
    fun `invalid observed and bootstrap resolver values are rejected without using dnsIp`() {
        val activeDns =
            AppSettingsSerializer.defaultValue.activeDnsSettings().copy(
                dnsIp = "203.0.113.99",
                encryptedDnsBootstrapIps = listOf("resolver.example"),
            )
        val plan =
            ValidatedSplitStrictDnsPolicy.build(
                activeDns = activeDns,
                routingSnapshot =
                    availablePolicy(
                        listOf(rule(DestinationRoutingAction.DIRECT, exact("direct.example"))),
                    ),
                underlayDnsServers = listOf("underlay.example"),
            )

        assertTrue(plan.bootstrapPins.isEmpty())
        assertTrue(plan.directResolverCandidates.isEmpty())
        assertEquals("bootstrap_resolver_invalid", plan.policyCoverageReason)
        assertEquals(DnsResolverPlane.PROXY, plan.decide("direct.example").plane)
    }

    @Test
    fun `plan digest changes with route underlay and bootstrap snapshots`() {
        val routeA = listOf(rule(DestinationRoutingAction.DIRECT, exact("a.example")))
        val routeB = listOf(rule(DestinationRoutingAction.BLOCK, exact("a.example")))
        val first = plan(routeA)

        assertNotEquals(first.canonicalDigest, plan(routeB).canonicalDigest)
        assertNotEquals(first.canonicalDigest, plan(routeA, underlay = listOf("192.0.2.54")).canonicalDigest)
        assertNotEquals(
            first.canonicalDigest,
            plan(routeA, bootstrap = listOf("94.140.14.15")).canonicalDigest,
        )
    }

    @Test
    fun `unavailable route snapshot defaults to proxy with explicit reason`() {
        val plan =
            ValidatedSplitStrictDnsPolicy.build(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                routingSnapshot = DestinationRoutingPolicySnapshot.Unavailable("duplicate_order:7"),
                underlayDnsServers = listOf("192.0.2.53"),
            )

        assertEquals(DnsResolverPlane.PROXY, plan.decide("example.org").plane)
        assertEquals("route_policy_unavailable:duplicate_order:7", plan.policyCoverageReason)
    }

    @Test
    fun `oversized qname and resolver literals are rejected before parsing`() {
        val plan =
            ValidatedSplitStrictDnsPolicy.build(
                activeDns =
                    AppSettingsSerializer.defaultValue.activeDnsSettings().copy(
                        encryptedDnsBootstrapIps = listOf("1".repeat(65)),
                    ),
                routingSnapshot =
                    availablePolicy(
                        listOf(rule(DestinationRoutingAction.DIRECT, exact("direct.example"))),
                    ),
                underlayDnsServers = listOf("2".repeat(65)),
            )

        assertEquals("bootstrap_resolver_invalid", plan.policyCoverageReason)
        assertEquals("invalid_qname", plan.decide("a".repeat(1025)).coverageReason)
        assertTrue(plan.bootstrapPins.isEmpty())
        assertTrue(plan.directResolverCandidates.isEmpty())
    }

    private fun plan(
        rules: List<DestinationRoutingRule>,
        underlay: List<String> = listOf("192.0.2.53", "2001:db8::53"),
        bootstrap: List<String> = listOf("94.140.14.14"),
        underlayLeaseGeneration: Long? = null,
    ): ValidatedSplitStrictDnsPolicy =
        ValidatedSplitStrictDnsPolicy.build(
            activeDns =
                AppSettingsSerializer.defaultValue.activeDnsSettings().copy(
                    encryptedDnsBootstrapIps = bootstrap,
                ),
            routingSnapshot = availablePolicy(rules),
            underlayDnsServers = underlay,
            underlayLeaseGeneration = underlayLeaseGeneration,
        )

    private fun availablePolicy(rules: List<DestinationRoutingRule>) =
        DestinationRoutingPolicySnapshot.Available(
            DestinationRoutingPolicy(
                rules = rules,
                canonicalDigest = rules.joinToString(";") { it.toString() },
            ),
        )

    private fun rule(
        action: DestinationRoutingAction,
        domain: DestinationDomainMatcher,
        network: DestinationRoutingNetwork = DestinationRoutingNetwork.BOTH,
        ports: List<DestinationPortRange> = emptyList(),
        ipRanges: List<DestinationIpMatcher> = emptyList(),
    ) = DestinationRoutingRule(
        action = action,
        network = network,
        domains = listOf(domain),
        destinationPorts = ports,
        ipRanges = ipRanges,
    )

    private fun exact(value: String) = DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, value)

    private fun suffix(value: String) = DestinationDomainMatcher(DestinationDomainMatcherKind.SUFFIX, value)
}
