package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindWebTunnel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartLocalNetworkPreflightTest {
    @Test
    fun `captured selector is permission checked even when standalone relay is off`() =
        runTest {
            val preferences = RipDpiProxyUIPreferences(relay = RipDpiRelayConfig(enabled = false))
            val base = sampleResolution(proxyPreferences = preferences)
            val request = base.requestedConfiguration
            val selection =
                com.poyka.ripdpi.data.RuntimeConfigurationSelection(
                    "native",
                    relayKind = RelayKindWebTunnel,
                    profileId = "member",
                    selectorGroupId = "group-b",
                    selectorMemberId = "member",
                )
            val captured =
                RequestedRuntimeConfiguration(
                    request.identity,
                    request.relayInputs,
                    selection,
                    request.dns,
                    request.strategy,
                    request.tunnelInput,
                    request.frozenTransportMaterial,
                    request.frozenDnsMaterial,
                    request.frozenWarpMaterial,
                    request.warpReference,
                )
            val checked = mutableListOf<com.poyka.ripdpi.data.RuntimeConfigurationSelection>()
            val preflight =
                DefaultServiceStartLocalNetworkPreflight(
                    resolvePolicy = { base.copy(requestedConfiguration = captured) },
                    resolveRelay = { config, _, expected ->
                        assertTrue(config.enabled)
                        assertEquals("member", config.profileId)
                        checked += expected
                        throw LocalNetworkAccessRequiredException()
                    },
                    planInitialRace = { _, _, _ -> error("Explicit selector must not race") },
                )
            val failure = runCatching { preflight.requireAccess(Mode.VPN) }.exceptionOrNull()
            assertTrue(failure is LocalNetworkAccessRequiredException)
            assertEquals(listOf(selection), checked)
            assertEquals(false, preferences.relay.enabled)
        }

    @Test
    fun `configured relay is checked before proxy service dispatch`() =
        runTest {
            val resolvedRelays = mutableListOf<RipDpiRelayConfig>()
            val configuredRelay =
                RipDpiRelayConfig(enabled = true, kind = RelayKindWebTunnel, profileId = "lan-relay")
            val preflight =
                DefaultServiceStartLocalNetworkPreflight(
                    resolvePolicy = {
                        sampleResolution(
                            proxyPreferences = RipDpiProxyUIPreferences(relay = configuredRelay),
                        )
                    },
                    resolveRelay = { relay, _, _ -> resolvedRelays += relay },
                    planInitialRace = { _, _, _ -> null },
                )

            preflight.requireAccess(Mode.Proxy)

            assertEquals(listOf(configuredRelay), resolvedRelays)
        }

    @Test
    fun `relay local network denial propagates to foreground recovery`() =
        runTest {
            val preflight =
                DefaultServiceStartLocalNetworkPreflight(
                    resolvePolicy = {
                        sampleResolution(
                            proxyPreferences =
                                RipDpiProxyUIPreferences(
                                    relay =
                                        RipDpiRelayConfig(
                                            enabled = true,
                                            kind = RelayKindWebTunnel,
                                            profileId = "lan-relay",
                                        ),
                                ),
                        )
                    },
                    resolveRelay = { _, _, _ -> throw LocalNetworkAccessRequiredException() },
                    planInitialRace = { _, _, _ -> null },
                )

            val failure = runCatching { preflight.requireAccess(Mode.Proxy) }.exceptionOrNull()

            assertTrue(failure is LocalNetworkAccessRequiredException)
        }

    @Test
    fun `vpn relay race checks every candidate before dispatch`() =
        runTest {
            val resolvedProfiles = mutableListOf<String>()
            val requirements = EgressRequirements(tcpConnect = true, udpAssociate = false)
            val racePlan =
                InitialRelayRacePlan(
                    probePlan = RelayProbePlan(null, RelayTargetCategory.Unavailable, requirements),
                    candidates =
                        listOf(
                            InitialRelayCandidate(
                                InitialRelayTransportClass.TlsMimicry,
                                "first",
                                RelayKindWebTunnel,
                            ),
                            InitialRelayCandidate(
                                InitialRelayTransportClass.UdpObfuscation,
                                "second",
                                RelayKindSnowflake,
                            ),
                        ),
                    requirements = requirements,
                    healthScope = RelayHealthScope(persistentNetworkHash = null, sessionGeneration = 1L),
                )
            val preflight =
                DefaultServiceStartLocalNetworkPreflight(
                    resolvePolicy = {
                        sampleResolution(
                            mode = Mode.VPN,
                            proxyPreferences =
                                RipDpiProxyUIPreferences(
                                    relay =
                                        RipDpiRelayConfig(
                                            enabled = true,
                                            kind = RelayKindWebTunnel,
                                            profileId = "configured",
                                        ),
                                ),
                        )
                    },
                    resolveRelay = { relay, _: RelayResolutionInputs, _ ->
                        resolvedProfiles += relay.profileId
                    },
                    planInitialRace = { _, _, _ -> racePlan },
                )

            preflight.requireAccess(Mode.VPN)

            assertEquals(listOf("first", "second"), resolvedProfiles)
        }
}
