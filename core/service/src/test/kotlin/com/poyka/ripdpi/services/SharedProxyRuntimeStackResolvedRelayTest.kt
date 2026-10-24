package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.withRelayRuntimeSelection
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedProxyRuntimeStackResolvedRelayTest {
    @Test
    fun normalSavedHysteriaProjectsResolvedUdpAndBoundEndpointIntoProxyJson() =
        runTest { assertNormalSavedHysteriaProjection(useJson = false) }

    @Test
    fun normalSavedHysteriaProjectsResolvedUdpAndBoundEndpointIntoRememberedJson() =
        runTest { assertNormalSavedHysteriaProjection(useJson = true) }

    @Test
    fun normalSavedHysteriaProjectsRawIpv6HostIntoProxyJson() =
        runTest {
            assertNormalSavedHysteriaProjection(
                useJson = true,
                listenerAddress = "[::1]:$RealityRacePort",
                expectedHost = "::1",
            )
        }

    @Test
    fun normalSavedUdpDisabledProfileRejectsEnabledGlobalFlagBeforeRuntimeCreation() =
        runTest {
            val fixture = createFixture(resolver = savedHysteriaResolver(udpEnabled = false))
            val preferences =
                RipDpiProxyUIPreferences(
                    protocols = RipDpiProtocolConfig(udpAssociateEnabled = true),
                    relay =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindHysteria2,
                            profileId = HysteriaProfileId,
                            udpEnabled = true,
                        ),
                )

            val failure = runCatching { fixture.startNormalRelay(preferences) }.exceptionOrNull()

            assertTrue(failure is InitialTransportSelectionException)
            assertTrue(fixture.relayFactory.runtimes.isEmpty())
            assertTrue(fixture.proxyFactory.runtimes.isEmpty())
        }

    @Test
    fun normalRendererLosingResolvedUdpRejectsBeforeProxyAndKeepsCleanupOwnership() =
        runTest {
            val fixture =
                createFixture(
                    resolver = savedHysteriaResolver(udpEnabled = true),
                    renderer = { preferences, selection, host, port ->
                        preferences.withRelayRuntimeSelection(selection.copy(udpEnabled = false), host, port)
                    },
                )
            val preferences =
                RipDpiProxyUIPreferences(
                    relay =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindHysteria2,
                            profileId = HysteriaProfileId,
                        ),
                )

            val failure = runCatching { fixture.startNormalRelay(preferences) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.proxyFactory.runtimes.isEmpty())
            assertTrue(fixture.stack.hasOwnedRuntime)
            fixture.stack.stop(skipRuntimeShutdown = false)
            assertEquals(1, fixture.relayFactory.lastRuntime.stopCount)
            assertFalse(fixture.stack.hasOwnedRuntime)
        }

    private fun TestScope.createFixture(
        resolver: UpstreamRelayRuntimeConfigResolver,
        relayListenerAddress: String? = null,
        renderer: (RipDpiProxyPreferences, RipDpiRelayConfig, String, Int) -> RipDpiProxyPreferences =
            { preferences, selection, host, port ->
                preferences.withRelayRuntimeSelection(selection, host, port)
            },
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val relayFactory =
            TestRipDpiRelayFactory {
                TestRelayRuntime().apply {
                    telemetry =
                        NativeRuntimeSnapshot(
                            source = "relay",
                            state = "running",
                            health = "healthy",
                            listenerAddress = relayListenerAddress ?: "127.0.0.1:$RealityRacePort",
                        )
                }
            }
        val proxyFactory = TestRipDpiProxyFactory()
        val stack =
            SharedProxyRuntimeStack(
                upstreamRelaySupervisor =
                    UpstreamRelaySupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        relayFactory = relayFactory,
                        naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                        runtimeConfigResolver = resolver,
                    ),
                warpRuntimeSupervisor =
                    WarpRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        warpFactory = TestRipDpiWarpFactory(),
                        runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                    ),
                amneziaWgRuntimeSupervisor =
                    AmneziaWgRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        amneziaWgFactory = TestRipDpiAmneziaWgFactory(),
                        runtimeConfigResolver = RecordingAmneziaWgRuntimeConfigResolver(),
                        activeRegistry = ActiveAwgRuntimeRegistry(),
                    ),
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        ripDpiProxyFactory = proxyFactory,
                        networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    ),
                relayRuntimeSelectionRenderer = renderer,
            )
        return Fixture(stack, proxyFactory, relayFactory)
    }

    private suspend fun TestScope.assertNormalSavedHysteriaProjection(
        useJson: Boolean,
        listenerAddress: String? = null,
        expectedHost: String = "127.0.0.1",
    ) {
        val fixture =
            createFixture(
                resolver = savedHysteriaResolver(udpEnabled = true),
                relayListenerAddress = listenerAddress,
            )
        val preferences =
            RipDpiProxyUIPreferences(
                protocols = RipDpiProtocolConfig(udpAssociateEnabled = true),
                relay =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindHysteria2,
                        profileId = HysteriaProfileId,
                        udpEnabled = false,
                        localSocksPort = RememberedRelayPort,
                    ),
            )

        val input = if (useJson) RipDpiProxyJsonPreferences(preferences.toNativeConfigJson()) else preferences
        fixture.startNormalRelay(input)

        val actualPreferences = requireNotNull(fixture.proxyFactory.lastRuntime.lastPreferences)
        val envelope = Json.parseToJsonElement(actualPreferences.toNativeConfigJson()).jsonObject
        val upstream = envelope.getValue("upstreamRelay").jsonObject
        assertEquals(
            true,
            fixture.relayFactory.lastRuntime.lastConfig
                ?.udpEnabled,
        )
        assertEquals(true, upstream.getValue("udpEnabled").jsonPrimitive.boolean)
        assertEquals(RelayKindHysteria2, upstream.getValue("kind").jsonPrimitive.content)
        assertEquals(HysteriaProfileId, upstream.getValue("profileId").jsonPrimitive.content)
        assertEquals(expectedHost, upstream.getValue("localSocksHost").jsonPrimitive.content)
        assertEquals(RealityRacePort, upstream.getValue("localSocksPort").jsonPrimitive.int)
        fixture.stack.stop(skipRuntimeShutdown = false)
    }

    private suspend fun Fixture.startNormalRelay(preferences: RipDpiProxyPreferences) =
        stack.start(
            requestedSelection =
                com.poyka.ripdpi.data
                    .RuntimeConfigurationSelection("native"),
            relayInputs = testRelayResolutionInputs(tlsProfile = TlsFingerprintProfileFirefoxStable),
            requestedWarpReference = null,
            proxyPreferences = preferences,
            onRelayExit = {},
            onWarpExit = {},
            onAwgExit = {},
            onProxyExit = {},
        )

    private suspend fun savedHysteriaResolver(udpEnabled: Boolean): UpstreamRelayRuntimeConfigResolver {
        val profiles = TestRelayProfileStore()
        profiles.save(
            RelayProfileRecord(
                id = HysteriaProfileId,
                kind = RelayKindHysteria2,
                server = "fixture.example",
                serverPort = 443,
                serverName = "fixture.example",
                udpEnabled = udpEnabled,
            ),
        )
        val credentials = TestRelayCredentialStore()
        credentials.save(RelayCredentialRecord(profileId = HysteriaProfileId, hysteriaPassword = "fixture-password"))
        val dependencies = UpstreamRelayResolverDependencies()
        return createDefaultUpstreamRelayRuntimeConfigResolver(
            relayProfileStore = profiles,
            relayCredentialStore = credentials,
            selectorRelayRuntimeProfileResolver = TestSelectorRelayRuntimeProfileResolver(),
            cloudflareMasqueGeohashResolver = dependencies.cloudflareMasqueGeohashResolver,
            masquePrivacyPassProvider = dependencies.masquePrivacyPassProvider,
            torRuntimePathProvider = dependencies.torRuntimeProviders.pathProvider,
            torPluggableTransportProvider = dependencies.torRuntimeProviders.pluggableTransportProvider,
        )
    }

    private data class Fixture(
        val stack: SharedProxyRuntimeStack,
        val proxyFactory: TestRipDpiProxyFactory,
        val relayFactory: TestRipDpiRelayFactory,
    )

    private companion object {
        const val HysteriaProfileId = "saved-hysteria"
        const val RememberedRelayPort = 11_980
        const val RealityRacePort = 19_001
    }
}
