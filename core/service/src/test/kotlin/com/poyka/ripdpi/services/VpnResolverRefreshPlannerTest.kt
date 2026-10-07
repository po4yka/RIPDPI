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
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnResolverRefreshPlannerTest {
    @Test
    fun binderGenerationAppearanceRequestsOneTunnelRebuild() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val activeDns = settings.activeDnsSettings()
            val policy =
                ValidatedSplitStrictDnsPolicy.build(
                    activeDns = activeDns,
                    routingSnapshot =
                        DestinationRoutingPolicySnapshot.Available(
                            DestinationRoutingPolicy(
                                rules =
                                    listOf(
                                        DestinationRoutingRule(
                                            action = DestinationRoutingAction.DIRECT,
                                            network = DestinationRoutingNetwork.BOTH,
                                            domains =
                                                listOf(
                                                    DestinationDomainMatcher(
                                                        DestinationDomainMatcherKind.EXACT,
                                                        "direct.example",
                                                    ),
                                                ),
                                        ),
                                    ),
                                canonicalDigest = "route",
                            ),
                        ),
                    underlayDnsServers = listOf("1.1.1.1"),
                    underlayLeaseGeneration = 17L,
                )
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(
                            sampleResolution(mode = Mode.VPN, settings = settings).copy(splitStrictDnsPolicy = policy),
                        ),
                    resolverOverrideStore = TestResolverOverrideStore(),
                )
            val previous = dnsSignature(activeDns, null, policy.canonicalDigest, underlayLeaseGeneration = null)

            val first = planner.plan(previous, tunnelRunning = true)
            val stable = planner.plan(first.signature, tunnelRunning = true)

            assertTrue(first.requiresTunnelRebuild)
            assertFalse(stable.requiresTunnelRebuild)
        }

    @Test
    fun `unused underlay appearance and warm changes preserve effective signature and split policy`() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val activeDns = settings.activeDnsSettings()
            val policies =
                listOf(
                    emptyList(),
                    listOf(DestinationRoutingRule(DestinationRoutingAction.TUNNELED, DestinationRoutingNetwork.BOTH)),
                    listOf(DestinationRoutingRule(DestinationRoutingAction.BLOCK, DestinationRoutingNetwork.BOTH)),
                    listOf(DestinationRoutingRule(DestinationRoutingAction.DIRECT, DestinationRoutingNetwork.TCP)),
                    listOf(DestinationRoutingRule(DestinationRoutingAction.DIRECT, DestinationRoutingNetwork.UDP)),
                    listOf(
                        DestinationRoutingRule(
                            DestinationRoutingAction.DIRECT,
                            DestinationRoutingNetwork.BOTH,
                            destinationPorts = listOf(DestinationPortRange(53, 53)),
                        ),
                    ),
                    listOf(
                        DestinationRoutingRule(
                            DestinationRoutingAction.DIRECT,
                            DestinationRoutingNetwork.BOTH,
                            ipRanges = listOf(DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24")),
                        ),
                    ),
                )

            policies.forEach { rules ->
                val snapshot =
                    DestinationRoutingPolicySnapshot.Available(
                        DestinationRoutingPolicy(rules = rules, canonicalDigest = rules.toString()),
                    )
                val initial = ValidatedSplitStrictDnsPolicy.build(activeDns, snapshot, emptyList())
                val first = ValidatedSplitStrictDnsPolicy.build(activeDns, snapshot, listOf("1.1.1.1"), 17L)
                val warm = ValidatedSplitStrictDnsPolicy.build(activeDns, snapshot, listOf("8.8.8.8"), 18L)
                val invalid = ValidatedSplitStrictDnsPolicy.build(activeDns, snapshot, listOf("underlay.example"), 19L)
                val oversized =
                    ValidatedSplitStrictDnsPolicy.build(
                        activeDns,
                        snapshot,
                        List(17) { "192.0.2.${it + 1}" },
                        20L,
                    )
                val resolution = sampleResolution(mode = Mode.VPN, settings = settings)
                val resolver = TestConnectionPolicyResolver(resolution)
                resolver.enqueue(
                    resolution.copy(splitStrictDnsPolicy = first),
                    resolution.copy(splitStrictDnsPolicy = warm),
                    resolution.copy(splitStrictDnsPolicy = warm),
                    resolution.copy(splitStrictDnsPolicy = invalid),
                    resolution.copy(splitStrictDnsPolicy = oversized),
                    resolution.copy(splitStrictDnsPolicy = oversized),
                )
                val planner = VpnResolverRefreshPlanner(resolver, TestResolverOverrideStore())
                val previous = dnsSignature(activeDns, null, initial.canonicalDigest, initial.underlayLeaseGeneration)
                val appeared = planner.plan(previous, tunnelRunning = true)
                val changed = planner.plan(appeared.signature, tunnelRunning = true)
                val warmStable = planner.plan(changed.signature, tunnelRunning = true)
                val invalidRefresh = planner.plan(warmStable.signature, tunnelRunning = true)
                val oversizedRefresh = planner.plan(invalidRefresh.signature, tunnelRunning = true)
                val stable = planner.plan(oversizedRefresh.signature, tunnelRunning = true)

                listOf(appeared, changed, warmStable, invalidRefresh, oversizedRefresh, stable).forEach { refresh ->
                    assertEquals(previous, refresh.signature)
                    assertFalse(refresh.requiresTunnelRebuild)
                    assertFalse(refresh.requiresRuntimeRecompose)
                    val policy = requireNotNull(refresh.connectionPolicy?.splitStrictDnsPolicy)
                    assertEquals(initial.canonicalDigest, policy.canonicalDigest)
                    assertEquals(initial.policyCoverageReason, policy.policyCoverageReason)
                    assertEquals(initial.decide("example.org"), policy.decide("example.org"))
                    assertEquals(initial.decide("unmatched.example"), policy.decide("unmatched.example"))
                    assertTrue(policy.directResolverCandidates.isEmpty())
                    assertNull(policy.underlayLeaseGeneration)
                    assertEquals(activeDns.encryptedDnsBootstrapIps, policy.bootstrapPins)
                    assertEquals(rules, policy.destinationRouting.rules)
                    assertEquals(activeDns, refresh.connectionPolicy?.activeDns)
                }
            }
        }

    @Test
    fun `eligible direct underlay epoch change requests one rebuild with unchanged candidates`() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val activeDns = settings.activeDnsSettings()
            val snapshot =
                DestinationRoutingPolicySnapshot.Available(
                    DestinationRoutingPolicy(
                        rules =
                            listOf(
                                DestinationRoutingRule(
                                    action = DestinationRoutingAction.DIRECT,
                                    network = DestinationRoutingNetwork.BOTH,
                                ),
                            ),
                        canonicalDigest = "direct",
                    ),
                )
            val previousPolicy = ValidatedSplitStrictDnsPolicy.build(activeDns, snapshot, listOf("1.1.1.1"), 17L)
            val nextPolicy = ValidatedSplitStrictDnsPolicy.build(activeDns, snapshot, listOf("1.1.1.1"), 18L)
            val resolution =
                sampleResolution(mode = Mode.VPN, settings = settings).copy(splitStrictDnsPolicy = nextPolicy)
            val planner =
                VpnResolverRefreshPlanner(TestConnectionPolicyResolver(resolution), TestResolverOverrideStore())
            val previous =
                dnsSignature(activeDns, null, previousPolicy.canonicalDigest, previousPolicy.underlayLeaseGeneration)

            val changed = planner.plan(previous, tunnelRunning = true)
            val stable = planner.plan(changed.signature, tunnelRunning = true)

            assertEquals(previousPolicy.canonicalDigest, nextPolicy.canonicalDigest)
            assertEquals(listOf("1.1.1.1"), nextPolicy.decide("any.example").resolverCandidates)
            assertEquals(DnsResolverPlane.DIRECT, nextPolicy.decide("any.example").plane)
            assertEquals(18L, nextPolicy.underlayLeaseGeneration)
            assertTrue(changed.requiresTunnelRebuild)
            assertFalse(stable.requiresTunnelRebuild)
        }

    @Test
    fun routePolicyMutationRequestsRuntimeRecompose() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val resolution =
                sampleResolution(
                    mode = Mode.VPN,
                    settings = settings,
                ).copy(destinationRoutingDigest = "b".repeat(64))
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = TestConnectionPolicyResolver(resolution),
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature =
                        dnsSignature(activeDns = settings.activeDnsSettings(), overrideReason = null),
                    currentDestinationRoutingDigest = "a".repeat(64),
                    tunnelRunning = true,
                )

            assertTrue(plan.requiresRuntimeRecompose)
            assertFalse(plan.requiresTunnelRebuild)
        }

    @Test
    fun unchangedSignatureDoesNotRequestTunnelRebuild() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val resolver = TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN, settings = settings))
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = resolver,
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature = dnsSignature(settings.activeDnsSettings(), overrideReason = null),
                    tunnelRunning = true,
                )

            assertFalse(plan.requiresTunnelRebuild)
            assertFalse(plan.requiresRuntimeRecompose)
            assertNotNull(plan.connectionPolicy)
        }

    @Test
    fun changedSignatureRequestsTunnelRebuild() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("8.8.8.8")
                    .build()
            val resolver =
                TestConnectionPolicyResolver(
                    sampleResolution(
                        mode = Mode.VPN,
                        settings = settings,
                        activeDns = settings.activeDnsSettings(),
                    ),
                )
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = resolver,
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature =
                        dnsSignature(AppSettingsSerializer.defaultValue.activeDnsSettings(), overrideReason = null),
                    tunnelRunning = true,
                )

            assertTrue(plan.requiresTunnelRebuild)
            assertFalse(plan.requiresRuntimeRecompose)
            assertEquals("8.8.8.8", plan.connectionPolicy?.activeDns?.dnsIp)
        }

    @Test
    fun resolvedPreferredDnsPathDoesNotTriggerRebuildLoop() =
        runTest {
            val persistedSettings = AppSettingsSerializer.defaultValue
            val preferredSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModeEncrypted)
                    .setDnsProviderId(DnsProviderGoogle)
                    .setDnsIp("8.8.8.8")
                    .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                    .setEncryptedDnsHost("dns.google")
                    .setEncryptedDnsPort(443)
                    .setEncryptedDnsTlsServerName("dns.google")
                    .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8", "8.8.4.4"))
                    .setEncryptedDnsDohUrl("https://dns.google/dns-query")
                    .build()
            val planner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(
                            sampleResolution(
                                mode = Mode.VPN,
                                settings = persistedSettings,
                                activeDns = preferredSettings.activeDnsSettings(),
                            ),
                        ),
                    resolverOverrideStore = TestResolverOverrideStore(),
                )

            val plan =
                planner.plan(
                    currentSignature =
                        dnsSignature(
                            activeDns = preferredSettings.activeDnsSettings(),
                            overrideReason = null,
                        ),
                    tunnelRunning = true,
                )

            assertFalse(plan.requiresTunnelRebuild)
            assertEquals(DnsProviderGoogle, plan.connectionPolicy?.activeDns?.providerId)
            assertEquals(DnsProviderAdGuard, plan.resolution.activeDns.providerId)
        }

    @Test
    fun overrideIsClearedOnlyWhenPersistedDnsAlreadyMatchesEffectiveDns() =
        runTest {
            val matchingSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModeEncrypted)
                    .setDnsProviderId(DnsProviderCloudflare)
                    .setDnsIp("1.1.1.1")
                    .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                    .setEncryptedDnsHost("cloudflare-dns.com")
                    .setEncryptedDnsPort(443)
                    .setEncryptedDnsTlsServerName("cloudflare-dns.com")
                    .addAllEncryptedDnsBootstrapIps(listOf("1.1.1.1", "1.0.0.1"))
                    .setEncryptedDnsDohUrl("https://cloudflare-dns.com/dns-query")
                    .build()
            val matchingOverride =
                TemporaryResolverOverride(
                    resolverId = DnsProviderCloudflare,
                    protocol = EncryptedDnsProtocolDoh,
                    host = "cloudflare-dns.com",
                    port = 443,
                    tlsServerName = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                    dnscryptProviderName = "",
                    dnscryptPublicKey = "",
                    reason = "temporary override",
                    appliedAt = 10L,
                )
            val matchingStore = TestResolverOverrideStore(matchingOverride)
            val matchingPlanner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN, settings = matchingSettings)),
                    resolverOverrideStore = matchingStore,
                )

            matchingPlanner.plan(currentSignature = null, tunnelRunning = true)

            assertNull(matchingStore.override.value)

            val nonMatchingSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("9.9.9.9")
                    .build()
            val nonMatchingStore = TestResolverOverrideStore(matchingOverride)
            val nonMatchingPlanner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver =
                        TestConnectionPolicyResolver(sampleResolution(mode = Mode.VPN, settings = nonMatchingSettings)),
                    resolverOverrideStore = nonMatchingStore,
                )

            nonMatchingPlanner.plan(currentSignature = null, tunnelRunning = true)

            assertNotNull(nonMatchingStore.override.value)
        }
}
