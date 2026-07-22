package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicyCompileResult
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicyCompiler
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionPolicyDestinationRoutingTest {
    @Test
    fun `resolver publishes canonical routing and only validated underlay dns candidates`() =
        runTest {
            val routingPolicy =
                (
                    DestinationRoutingPolicyCompiler.compile(
                        listOf(RuleEntity(id = 1, domains = "direct.example", outboundTag = OutboundTag.Bypass)),
                    ) as DestinationRoutingPolicyCompileResult.Success
                ).policy
            val source = DestinationRoutingPolicySource { DestinationRoutingPolicySnapshot.Available(routingPolicy) }

            val validated = resolver(sampleFingerprint(dnsServers = listOf("192.0.2.53")), source).resolve(Mode.VPN)
            val unvalidated =
                resolver(
                    sampleFingerprint(dnsServers = listOf("192.0.2.54")).copy(networkValidated = false),
                    source,
                ).resolve(Mode.VPN)
            val captive =
                resolver(
                    sampleFingerprint(dnsServers = listOf("192.0.2.55")).copy(captivePortalDetected = true),
                    source,
                ).resolve(Mode.VPN)

            assertEquals(
                routingPolicy.canonicalDigest,
                decodeRipDpiProxyUiPreferences(validated.proxyPreferences.toNativeConfigJson())
                    ?.destinationRouting
                    ?.canonicalDigest,
            )
            assertEquals(DnsResolverPlane.DIRECT, validated.splitStrictDnsPolicy?.decide("direct.example")?.plane)
            assertEquals(listOf("192.0.2.53"), validated.splitStrictDnsPolicy?.directResolverCandidates)
            assertEquals(
                "direct_resolver_unavailable",
                unvalidated.splitStrictDnsPolicy?.decide("direct.example")?.coverageReason,
            )
            assertEquals(
                "direct_resolver_unavailable",
                captive.splitStrictDnsPolicy?.decide("direct.example")?.coverageReason,
            )
        }

    private fun resolver(
        fingerprint: NetworkFingerprint,
        source: DestinationRoutingPolicySource,
    ) = DefaultConnectionPolicyResolver(
        context = RuntimeEnvironment.getApplication(),
        appSettingsRepository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
        networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
        networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
        networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
        antiCorrelationRoutingPolicy =
            object : AntiCorrelationRoutingPolicy {
                override fun apply(
                    settings: AppSettings,
                    preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
                ): Map<String, List<PreferredEdgeCandidate>> = preferredEdges
            },
        rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
        rootHelperManager = RootHelperManager(),
        environmentDetector = EnvironmentDetector(),
        serverCapabilityStore = TestServerCapabilityStore(),
        awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
        destinationRoutingPolicySource = source,
    )
}
