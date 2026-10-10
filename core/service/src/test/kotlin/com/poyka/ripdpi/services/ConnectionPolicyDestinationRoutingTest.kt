package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.RootSettingsSection
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicyCompileResult
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicyCompiler
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionPolicyDestinationRoutingTest {
    @Test
    fun `command-line mode preserves canonical destination routing overlay`() =
        runTest {
            val routingPolicy =
                (
                    DestinationRoutingPolicyCompiler.compile(
                        listOf(RuleEntity(id = 1, domains = "direct.example", outboundTag = OutboundTag.Bypass)),
                    ) as DestinationRoutingPolicyCompileResult.Success
                ).policy
            val source = DestinationRoutingPolicySource { DestinationRoutingPolicySnapshot.Available(routingPolicy) }
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setEnableCmdSettings(true)
                    .build()

            val resolved = resolver(sampleFingerprint(), source, settings).resolve(Mode.Proxy)

            assertEquals(
                routingPolicy.canonicalDigest,
                (resolved.proxyPreferences as RipDpiProxyCmdPreferences).destinationRouting.canonicalDigest,
            )
            val commandLine = resolved.proxyPreferences
            assertTrue(commandLine.geoipDbPath?.endsWith("geoip.db") == true)
            assertTrue(commandLine.geositeDbPath?.endsWith("geosite.db") == true)
        }

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
            val ui = decodeRipDpiProxyUiPreferences(validated.proxyPreferences.toNativeConfigJson())
            assertTrue(ui?.geoipDbPath?.endsWith("geoip.db") == true)
            assertTrue(ui?.geositeDbPath?.endsWith("geosite.db") == true)
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

    @Test
    fun `VPN uses the current owned endpoint and rejects a withdrawn endpoint`() =
        runTest {
            val provider = ActiveProtectSocketPathProvider()
            val lease = provider.set("/session-specific-protect") { true }
            val policy = resolver(sampleFingerprint(), EmptyDestinationRoutingPolicySource, protection = provider)
            val vpn = policy.resolve(Mode.VPN)
            assertEquals(
                "/session-specific-protect",
                decodeRipDpiProxyUiPreferences(vpn.proxyPreferences.toNativeConfigJson())?.runtimeContext?.protectPath,
            )
            val replacementLease = provider.set("/replacement-session-protect") { true }
            val replacement = policy.resolve(Mode.VPN)
            assertEquals(vpn.policySignature, replacement.policySignature)
            provider.clear(lease)
            assertEquals("/replacement-session-protect", provider.current())
            provider.clear(replacementLease)
            val failure = runCatching { policy.resolve(Mode.VPN) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("VPN protect endpoint is not active", failure?.message)
            val proxy = policy.resolve(Mode.Proxy)
            assertNull(
                decodeRipDpiProxyUiPreferences(
                    proxy.proxyPreferences.toNativeConfigJson(),
                )?.runtimeContext?.protectPath,
            )
        }

    @Test
    fun `default VPN permission preflight does not require or activate a live endpoint`() =
        runTest {
            val helperCalls = mutableListOf<String>()
            val rootHelper =
                object : RootHelperManager() {
                    override suspend fun syncRootMode(
                        context: Context,
                        root: RootSettingsSection,
                    ): String? {
                        helperCalls += "root"
                        return null
                    }

                    override suspend fun syncNfqws(
                        context: Context,
                        settings: AppSettings,
                    ) {
                        helperCalls += "nfqws"
                    }
                }
            val provider = ActiveProtectSocketPathProvider()
            val policy =
                resolver(
                    sampleFingerprint(),
                    EmptyDestinationRoutingPolicySource,
                    protection = provider,
                    rootHelper = rootHelper,
                )
            val preflight =
                DefaultServiceStartLocalNetworkPreflight(
                    resolvePolicy = policy::resolveForPreflight,
                    resolveRelay = { _, _, _ -> error("No relay is configured") },
                    planInitialRace = { _, _, _ -> error("No relay is configured") },
                )

            preflight.requireAccess(Mode.VPN)

            assertNull(provider.current())
            assertEquals(emptyList<String>(), helperCalls)
            val activationFailure = runCatching { policy.resolve(Mode.VPN) }.exceptionOrNull()
            assertEquals("VPN protect endpoint is not active", activationFailure?.message)
        }

    @Test
    @Config(sdk = [37])
    fun `default VPN preflight reports LAN permission before service activation`() =
        runTest {
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(LocalNetworkPermission)
            val provider = ActiveProtectSocketPathProvider()
            val policy =
                resolver(
                    sampleFingerprint(),
                    EmptyDestinationRoutingPolicySource,
                    settings =
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setProxyIp("192.168.1.1")
                            .build(),
                    protection = provider,
                )
            val preflight =
                DefaultServiceStartLocalNetworkPreflight(
                    resolvePolicy = policy::resolveForPreflight,
                    resolveRelay = { _, _, _ -> error("No relay is configured") },
                    planInitialRace = { _, _, _ -> error("No relay is configured") },
                )
            val failure = runCatching { preflight.requireAccess(Mode.VPN) }.exceptionOrNull()
            assertTrue(
                "Actual permission denial must reach the preflight caller: $failure",
                failure is LocalNetworkAccessRequiredException,
            )
            assertNull(provider.current())
        }

    private fun resolver(
        fingerprint: NetworkFingerprint,
        source: DestinationRoutingPolicySource,
        settings: AppSettings = AppSettingsSerializer.defaultValue,
        protection: ActiveProtectSocketPathProvider = testActiveProtectSocketPathProvider(),
        rootHelper: RootHelperManager = RootHelperManager(),
    ) = DefaultConnectionPolicyResolver(
        runtimeConfigurationCapture =
            testRequestedRuntimeConfigurationCapture(
                ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                source,
            ),
        context = RuntimeEnvironment.getApplication(),
        appSettingsRepository = TestAppSettingsRepository(settings),
        networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
        networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
        rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
        awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
        destinationRoutingPolicySource = source,
        runtimeContextAssembler =
            ConnectionPolicyRuntimeContextAssembler(
                context = RuntimeEnvironment.getApplication(),
                networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                serverCapabilityStore = TestServerCapabilityStore(),
                antiCorrelationRoutingPolicy =
                    object : AntiCorrelationRoutingPolicy {
                        override fun apply(
                            settings: AppSettings,
                            preferredEdges: Map<String, List<PreferredEdgeCandidate>>,
                        ): Map<String, List<PreferredEdgeCandidate>> = preferredEdges
                    },
                rootHelperManager = rootHelper,
                environmentDetector = EnvironmentDetector(),
                proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                activeProtectSocketPathProvider = protection,
            ),
    )
}
