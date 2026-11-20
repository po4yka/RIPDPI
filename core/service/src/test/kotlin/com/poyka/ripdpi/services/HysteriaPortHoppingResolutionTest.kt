package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.Hysteria2PortHopping
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class HysteriaPortHoppingResolutionTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
    private val hopping = Hysteria2PortHopping("20000-21000,30000", 5_000L, 15_000L)
    private val profile =
        RelayProfileRecord(
            id = "hopping",
            kind = RelayKindHysteria2,
            server = "hopping.example",
            serverPort = 443,
            serverName = "tls.example",
            hysteriaPortHopping = hopping,
        )
    private val credential =
        RelayCredentialRecord(
            profileId = profile.id,
            hysteriaPassword = credentialFixture("hopping"),
            hysteriaSalamanderKey = credentialFixture("salamander"),
            hysteriaInsecure = true,
            hysteriaPinSha256 = "cd".repeat(32),
        )

    @Test
    fun `stored profile preserves hopping and overrides stale config`() =
        runTest {
            val profiles = TestRelayProfileStore().apply { save(profile) }
            val credentials = TestRelayCredentialStore().apply { save(credential) }
            val resolved =
                resolver(profiles, credentials).resolve(
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = profile.kind,
                        profileId = profile.id,
                        hysteriaPortHopping = Hysteria2PortHopping("40000"),
                    ),
                    testRelayResolutionInputs(tlsProfile = "firefox_stable"),
                    RuntimeConfigurationSelection("native"),
                )
            assertHopping(resolved)
            profiles.save(profile.copy(hysteriaPortHopping = null))
            val scalar =
                resolver(profiles, credentials).resolve(
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = profile.kind,
                        profileId = profile.id,
                        hysteriaPortHopping = hopping,
                    ),
                    testRelayResolutionInputs(tlsProfile = "firefox_stable"),
                    RuntimeConfigurationSelection("native"),
                )
            assertEquals(null, scalar.hysteriaPortHopPorts)
            assertEquals(null, scalar.hysteriaPortHopIntervalMillis)
        }

    @Test
    fun `transient hopping candidate preserves TLS and leaves stores unchanged`() =
        runTest {
            val profiles = TestRelayProfileStore()
            val credentials = TestRelayCredentialStore()
            val resolved =
                resolver(profiles, credentials).resolveTransient(
                    profile,
                    credential,
                    OwnedRelayQuicMigrationConfig(),
                    "firefox_stable",
                    emptyMap(),
                )
            assertHopping(resolved)
            assertEquals(emptyList<RelayProfileRecord>(), profiles.list())
            assertEquals(0, credentials.credentials.size)
        }

    @Test
    fun `chain entry preserves hopping Salamander and TLS identity`() =
        runTest {
            val exit = RelayProfileRecord(id = "exit", kind = RelayKindTrojan, server = "exit.example")
            val profiles =
                TestRelayProfileStore().apply {
                    save(profile)
                    save(exit)
                }
            val credentials =
                TestRelayCredentialStore().apply {
                    save(credential)
                    save(RelayCredentialRecord(profileId = exit.id, trojanPassword = credentialFixture("exit")))
                }
            val chain =
                resolveChainRelayConfigSupport(
                    chainProfileId = "chain",
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            chainEntryProfileId = profile.id,
                            chainExitProfileId = exit.id,
                        ),
                    credentials = null,
                    relayProfileStore = profiles,
                    relayCredentialStore = credentials,
                    fallbackTlsFingerprintProfile = "firefox_stable",
                )
            val entry = chain.entry.config
            assertEquals(443, entry.serverPort)
            assertEquals("tls.example", entry.serverName)
            assertEquals(hopping.ports, entry.hysteriaPortHopPorts)
            assertEquals(hopping.intervalMillis, entry.hysteriaPortHopIntervalMillis)
            assertEquals(hopping.intervalMaxMillis, entry.hysteriaPortHopIntervalMaxMillis)
            assertEquals(credential.hysteriaSalamanderKey, entry.hysteriaSalamanderKey)
            assertEquals(credential.hysteriaPinSha256, entry.hysteriaPinSha256)
            assertEquals(true, entry.hysteriaInsecure)
        }

    @Test
    fun `imported realm bundle reaches the native relay config JSON`() =
        runTest {
            val bundle =
                """
                { "outbounds": [
                    { "type": "hysteria2", "tag": "p5-realm", "password": "test-password",
                      "tls": { "enabled": true, "server_name": "peer.example" },
                      "realm": { "server_url": "https://realm.example.com:8444", "token": "test-token",
                                 "realm_id": "realm-a", "stun_servers": ["stun.example.com:3478"] } } ] }
                """.trimIndent()
            val parsed = SingBoxSubscriptionParser.parse(bundle, "group") as SingBoxParseResult.Success
            val mapping = mapRelayProfile(parsed.profiles.single() as ProxyProfile.Hysteria2)!!
            val profiles = TestRelayProfileStore().apply { save(mapping.profile) }
            val credentials = TestRelayCredentialStore().apply { save(mapping.credentials) }
            val resolved =
                resolver(profiles, credentials).resolve(
                    RipDpiRelayConfig(enabled = true, kind = mapping.profile.kind, profileId = mapping.profile.id),
                    testRelayResolutionInputs(tlsProfile = "firefox_stable"),
                    RuntimeConfigurationSelection("native"),
                )
            val wire =
                Json
                    .parseToJsonElement(
                        Json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), resolved),
                    ).jsonObject
            assertEquals("realm-a", wire.getValue("hysteriaRealmId").jsonPrimitive.content)
            assertEquals(
                "https://realm.example.com:8444",
                wire.getValue("hysteriaRealmServerUrl").jsonPrimitive.content,
            )
            assertEquals("test-token", wire.getValue("hysteriaRealmToken").jsonPrimitive.content)
            assertEquals(
                listOf("stun.example.com:3478"),
                wire.getValue("hysteriaRealmStunServers").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("realm.example.com", wire.getValue("server").jsonPrimitive.content)
            assertEquals(
                8444,
                wire
                    .getValue("serverPort")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    private fun assertHopping(config: ResolvedRipDpiRelayConfig) {
        assertEquals(443, config.serverPort)
        assertEquals("tls.example", config.serverName)
        assertEquals(hopping.ports, config.hysteriaPortHopPorts)
        assertEquals(hopping.intervalMillis, config.hysteriaPortHopIntervalMillis)
        assertEquals(hopping.intervalMaxMillis, config.hysteriaPortHopIntervalMaxMillis)
        assertEquals(credential.hysteriaSalamanderKey, config.hysteriaSalamanderKey)
        assertEquals(credential.hysteriaPinSha256, config.hysteriaPinSha256)
        assertEquals(true, config.hysteriaInsecure)
    }
}
