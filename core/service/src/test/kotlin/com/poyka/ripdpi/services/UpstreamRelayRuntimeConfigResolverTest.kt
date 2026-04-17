package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.StrategyFeatureCloudflarePublish
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamRelayRuntimeConfigResolverTest {
    private fun resolver(
        relayProfileStore: TestRelayProfileStore = TestRelayProfileStore(),
        relayCredentialStore: TestRelayCredentialStore = TestRelayCredentialStore(),
        tlsFingerprintProfile: String = TlsFingerprintProfileChromeStable,
        masquePrivacyPassProvider: MasquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
        featureFlags: Map<String, Boolean> = emptyMap(),
        masqueGeohashHeader: String? = null,
    ): DefaultUpstreamRelayRuntimeConfigResolver =
        DefaultUpstreamRelayRuntimeConfigResolver(
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            cloudflareMasqueGeohashResolver =
                object : CloudflareMasqueGeohashResolver {
                    override suspend fun resolveHeaderValue(): String? = masqueGeohashHeader
                },
            masquePrivacyPassProvider = masquePrivacyPassProvider,
            tlsFingerprintProfileProvider =
                object : OwnedTlsFingerprintProfileProvider {
                    override fun currentProfile(): String = tlsFingerprintProfile
                },
            runtimeExperimentSelectionProvider =
                object : RuntimeExperimentSelectionProvider {
                    override fun current(): RuntimeExperimentSelection =
                        RuntimeExperimentSelection(featureFlags = featureFlags)
                },
        )

    @Test
    fun `resolve cloudflare tunnel family applies runtime overrides and credentials`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindCloudflareTunnel,
                                    server = "relay.example",
                                    serverPort = 8443,
                                    serverName = "relay-sni.example",
                                    cloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
                                    cloudflarePublishLocalOriginUrl = "http://127.0.0.1:8080",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "edge",
                                    vlessUuid = "00000000-0000-0000-0000-000000000000",
                                    cloudflareTunnelToken = "token",
                                ),
                            )
                        },
                    tlsFingerprintProfile = "firefox",
                    featureFlags = mapOf(StrategyFeatureCloudflarePublish to true),
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindCloudflareTunnel,
                            profileId = "edge",
                        ),
                    quicMigrationConfig =
                        OwnedRelayQuicMigrationConfig(
                            bindLowPort = true,
                            migrateAfterHandshake = true,
                        ),
                )

            assertEquals(RelayVlessTransportXhttp, resolved.vlessTransport)
            assertFalse(resolved.udpEnabled)
            assertEquals(TlsFingerprintProfileChromeStable, resolved.tlsFingerprintProfile)
            assertTrue(resolved.quicBindLowPort)
            assertTrue(resolved.quicMigrateAfterHandshake)
            assertEquals("00000000-0000-0000-0000-000000000000", resolved.vlessUuid)
        }

    @Test
    fun `resolve masque family includes privacy pass runtime and chrome fallback`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "masque",
                                    kind = RelayKindMasque,
                                    masqueUrl = "https://masque.example/.well-known/masque/udp/",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "masque",
                                    masqueAuthMode = RelayMasqueAuthModePrivacyPass,
                                ),
                            )
                        },
                    masquePrivacyPassProvider =
                        StaticMasquePrivacyPassProvider(
                            available = true,
                            providerUrl = "https://issuer.example/token",
                        ),
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindMasque,
                            profileId = "masque",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayMasqueAuthModePrivacyPass, resolved.masqueAuthMode)
            assertEquals("https://issuer.example/token", resolved.masquePrivacyPassProviderUrl)
            assertEquals(null, resolved.masquePrivacyPassProviderAuthToken)
            assertTrue(resolved.masqueUseHttp2Fallback)
        }

    @Test
    fun `resolve snowflake family defaults broker and front domain`() =
        runTest {
            val resolved =
                resolver().resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindSnowflake,
                            profileId = "snowflake",
                            udpEnabled = true,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertFalse(resolved.udpEnabled)
            assertEquals("https://snowflake-broker.torproject.net/", resolved.ptSnowflakeBrokerUrl)
            assertEquals("cdn.sstatic.net", resolved.ptSnowflakeFrontDomain)
        }

    @Test
    fun `resolve chain relay family resolves referenced hop profiles`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "chain",
                                    kind = RelayKindChainRelay,
                                    chainEntryProfileId = "entry-hop",
                                    chainExitProfileId = "exit-hop",
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "entry-hop",
                                    kind = RelayKindVlessReality,
                                    server = "entry.example",
                                    serverPort = 443,
                                    serverName = "entry-sni.example",
                                    realityPublicKey = "entry-public",
                                    realityShortId = "entry-short",
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "exit-hop",
                                    kind = RelayKindVlessReality,
                                    server = "exit.example",
                                    serverPort = 8443,
                                    serverName = "exit-sni.example",
                                    realityPublicKey = "exit-public",
                                    realityShortId = "exit-short",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "entry-hop",
                                    vlessUuid = "11111111-1111-1111-1111-111111111111",
                                ),
                            )
                            save(
                                RelayCredentialRecord(
                                    profileId = "exit-hop",
                                    vlessUuid = "22222222-2222-2222-2222-222222222222",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindChainRelay,
                            profileId = "chain",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals("entry-hop", resolved.chainEntryProfileId)
            assertEquals("entry.example", resolved.chainEntryServer)
            assertEquals("11111111-1111-1111-1111-111111111111", resolved.chainEntryUuid)
            assertEquals("exit-hop", resolved.chainExitProfileId)
            assertEquals("exit.example", resolved.chainExitServer)
            assertEquals("22222222-2222-2222-2222-222222222222", resolved.chainExitUuid)
        }

    @Test
    fun `resolve shadowtls family resolves inner relay config`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "outer",
                                    kind = RelayKindShadowTlsV3,
                                    server = "outer.example",
                                    serverPort = 443,
                                    serverName = "outer.example",
                                    shadowTlsInnerProfileId = "inner",
                                ),
                            )
                            save(
                                RelayProfileRecord(
                                    id = "inner",
                                    kind = RelayKindVlessReality,
                                    server = "inner.example",
                                    serverPort = 8443,
                                    serverName = "inner.example",
                                    realityPublicKey = "inner-public",
                                    realityShortId = "inner-short",
                                    vlessTransport = RelayVlessTransportRealityTcp,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "outer",
                                    shadowTlsPassword = "pw12345",
                                ),
                            )
                            save(
                                RelayCredentialRecord(
                                    profileId = "inner",
                                    vlessUuid = "33333333-3333-3333-3333-333333333333",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindShadowTlsV3,
                            profileId = "outer",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals("pw12345", resolved.shadowTlsPassword)
            assertNotNull(resolved.shadowTlsInner)
            assertEquals("inner", resolved.shadowTlsInner?.profileId)
            assertEquals("inner.example", resolved.shadowTlsInner?.server)
            assertEquals("33333333-3333-3333-3333-333333333333", resolved.shadowTlsInner?.vlessUuid)
        }

    @Test
    fun `resolve naive family preserves local listener and credentials`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "naive",
                                    naiveUsername = "alice",
                                    naivePassword = "secret",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindNaiveProxy,
                            profileId = "naive",
                            server = "naive.example",
                            serverPort = 443,
                            serverName = "naive.example",
                            naivePath = "/proxy",
                            localSocksHost = "127.0.0.2",
                            localSocksPort = 13000,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals("/proxy", resolved.naivePath)
            assertEquals("127.0.0.2", resolved.localSocksHost)
            assertEquals(13000, resolved.localSocksPort)
            assertEquals("alice", resolved.naiveUsername)
            assertEquals("secret", resolved.naivePassword)
        }

    @Test
    fun `resolve local path family keeps webtunnel target URL`() =
        runTest {
            val resolved =
                resolver().resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindWebTunnel,
                            profileId = "webtunnel",
                            ptWebTunnelUrl = "wss://transport.example/connect",
                            localSocksHost = "127.0.0.9",
                            localSocksPort = 14000,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindWebTunnel, resolved.kind)
            assertEquals("wss://transport.example/connect", resolved.ptWebTunnelUrl)
            assertEquals("127.0.0.9", resolved.localSocksHost)
            assertEquals(14000, resolved.localSocksPort)
        }

    @Test
    fun `resolve default family leaves vless settings untouched`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "default",
                                    vlessUuid = "44444444-4444-4444-4444-444444444444",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindVlessReality,
                            profileId = "default",
                            server = "relay.example",
                            serverPort = 443,
                            serverName = "relay.example",
                            realityPublicKey = "public-key",
                            realityShortId = "short-id",
                            vlessTransport = RelayVlessTransportXhttp,
                            xhttpPath = "/xhttp",
                            xhttpHost = "origin.example",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindVlessReality, resolved.kind)
            assertEquals(RelayVlessTransportXhttp, resolved.vlessTransport)
            assertEquals("/xhttp", resolved.xhttpPath)
            assertEquals("origin.example", resolved.xhttpHost)
            assertEquals("44444444-4444-4444-4444-444444444444", resolved.vlessUuid)
        }
}
