package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedProxySelectorRuntimeStackTest {
    @Test
    fun `explicit selector starts real resolved upstream when saved standalone relay is disabled`() =
        runTest {
            val selected = createSelectedFixture()
            val fixture = selected.runtime
            val profile = selected.profile
            val secret = selected.secret
            val resolver = selected.resolver
            val profiles = selected.profiles
            val secrets = selected.secrets
            val preferences =
                RipDpiProxyUIPreferences(
                    protocols = RipDpiProtocolConfig(udpAssociateEnabled = false),
                    relay = RipDpiRelayConfig(enabled = false),
                )
            val before = preferences.toNativeConfigJson()
            val result =
                fixture.stack.start(
                    requestedSelection =
                        com.poyka.ripdpi.data.RuntimeConfigurationSelection(
                            "native",
                            relayKind = profile.kind,
                            profileId = profile.id,
                            selectorGroupId = "group-b",
                            selectorMemberId = "shared",
                        ),
                    relayInputs = testRelayResolutionInputs(),
                    requestedWarpReference = null,
                    proxyPreferences = preferences,
                    onRelayExit = {},
                    onWarpExit = {},
                    onAwgExit = {},
                    onProxyExit = {},
                )
            assertEquals(1, fixture.relayFactory.runtimes.size)
            val proof = checkNotNull(result.consumedUpstreams.single().measuredInputProof)
            val expected =
                (resolver as DefaultUpstreamRelayRuntimeConfigResolver).resolveTransient(
                    profile,
                    secret,
                    testRelayResolutionInputs().quic,
                    testRelayResolutionInputs().tlsProfile,
                    testRelayResolutionInputs().featureFlags,
                )
            assertTrue(CandidateConfigurationProofs.relay(expected).matches(proof))
            assertEquals(
                "shared",
                result.consumedUpstreams
                    .single()
                    .selection.profileId,
            )
            assertEquals(before, preferences.toNativeConfigJson())
            assertTrue(profiles.profiles.isEmpty())
            assertTrue(secrets.credentials.isEmpty())
            fixture.stack.stop(false)
            val stale =
                runCatching {
                    fixture.stack.start(
                        requestedSelection =
                            com.poyka.ripdpi.data.RuntimeConfigurationSelection(
                                "native",
                                relayKind = profile.kind,
                                profileId = profile.id,
                                selectorGroupId = "group-a",
                                selectorMemberId = "shared",
                            ),
                        relayInputs = testRelayResolutionInputs(),
                        requestedWarpReference = null,
                        proxyPreferences = preferences,
                        onRelayExit = {},
                        onWarpExit = {},
                        onAwgExit = {},
                        onProxyExit = {},
                    )
                }.exceptionOrNull()
            assertTrue(stale is com.poyka.ripdpi.data.ServiceStartupRejectedException)
            assertEquals(1, fixture.relayFactory.runtimes.size)
            assertEquals(1, fixture.proxyFactory.runtimes.size)
        }

    private fun TestScope.createSelectedFixture(): SelectedFixture {
        val profiles = TestRelayProfileStore()
        val secrets = TestRelayCredentialStore()
        val profile =
            com.poyka.ripdpi.data.RelayProfileRecord(
                id = "shared",
                kind = "shadowsocks",
                server = "selected-b.example",
                serverPort = 443,
                udpEnabled = false,
            )
        val secret =
            com.poyka.ripdpi.data.RelayCredentialRecord(
                profileId = "shared",
                shadowsocksMethod = "aes-256-gcm",
                shadowsocksPassword = "fixture-shadowsocks-password",
            )
        val selected = SelectedSelectorRelay(profile, secret, "group-b", "shared")
        val resolver =
            createDefaultUpstreamRelayRuntimeConfigResolver(
                profiles,
                secrets,
                object : SelectorRelayRuntimeProfileResolver {
                    override suspend fun resolve() = selected
                },
                object : CloudflareMasqueGeohashResolver {
                    override suspend fun resolveHeaderValue(): String? = null
                },
                StaticMasquePrivacyPassProvider(),
                LocalTorRuntimePathProvider(),
                UnconfiguredTorPluggableTransportProvider(),
            )
        val fixture = createRuntimeFixture(resolver)
        return SelectedFixture(
            fixture,
            profile,
            secret,
            resolver as DefaultUpstreamRelayRuntimeConfigResolver,
            profiles,
            secrets,
        )
    }

    private fun TestScope.createRuntimeFixture(resolver: UpstreamRelayRuntimeConfigResolver): RuntimeFixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val relayFactory =
            TestRipDpiRelayFactory {
                TestRelayRuntime().apply {
                    telemetry =
                        NativeRuntimeSnapshot(
                            source = "relay",
                            state = "running",
                            health = "healthy",
                            listenerAddress = "127.0.0.1:19001",
                        )
                }
            }
        val proxyFactory = TestRipDpiProxyFactory { TestProxyRuntime() }
        val stack =
            SharedProxyRuntimeStack(
                UpstreamRelaySupervisor(
                    backgroundScope,
                    dispatcher,
                    relayFactory,
                    TestNaiveProxyRuntimeFactory(),
                    runtimeConfigResolver = resolver,
                ),
                WarpRuntimeSupervisor(
                    backgroundScope,
                    dispatcher,
                    TestRipDpiWarpFactory(),
                    TestWarpRuntimeConfigResolver(),
                ),
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    dispatcher,
                    TestRipDpiAmneziaWgFactory(),
                    RecordingAmneziaWgRuntimeConfigResolver(),
                ),
                ProxyRuntimeSupervisor(backgroundScope, dispatcher, proxyFactory, TestNativeNetworkSnapshotProvider()),
            )
        return RuntimeFixture(stack, proxyFactory, relayFactory)
    }

    private data class RuntimeFixture(
        val stack: SharedProxyRuntimeStack,
        val proxyFactory: TestRipDpiProxyFactory,
        val relayFactory: TestRipDpiRelayFactory,
    )

    private data class SelectedFixture(
        val runtime: RuntimeFixture,
        val profile: RelayProfileRecord,
        val secret: RelayCredentialRecord,
        val resolver: DefaultUpstreamRelayRuntimeConfigResolver,
        val profiles: TestRelayProfileStore,
        val secrets: TestRelayCredentialStore,
    )
}
