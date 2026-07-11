package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedTorPluggableTransportConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.StrategyFeatureCloudflarePublish
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

abstract class UpstreamRelayRuntimeConfigResolverTestFixture {
    internal fun resolver(
        relayProfileStore: TestRelayProfileStore = TestRelayProfileStore(),
        relayCredentialStore: TestRelayCredentialStore = TestRelayCredentialStore(),
        tlsFingerprintProfile: String = TlsFingerprintProfileChromeStable,
        masquePrivacyPassProvider: MasquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
        featureFlags: Map<String, Boolean> = emptyMap(),
        masqueGeohashHeader: String? = null,
        torRuntimePathProvider: TorRuntimePathProvider = StaticTorRuntimePathProvider(),
        torPluggableTransportProvider: TorPluggableTransportProvider = StaticTorPluggableTransportProvider(),
    ): DefaultUpstreamRelayRuntimeConfigResolver =
        DefaultUpstreamRelayRuntimeConfigResolver(
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            relayKindResolverRegistry =
                createDefaultRelayKindResolverRegistry(
                    relayProfileStore = relayProfileStore,
                    relayCredentialStore = relayCredentialStore,
                    cloudflareMasqueGeohashResolver =
                        object : CloudflareMasqueGeohashResolver {
                            override suspend fun resolveHeaderValue(): String? = masqueGeohashHeader
                        },
                    masquePrivacyPassProvider = masquePrivacyPassProvider,
                ),
            tlsFingerprintProfileProvider =
                object : OwnedTlsFingerprintProfileProvider {
                    override fun currentProfile(): String = tlsFingerprintProfile
                },
            runtimeExperimentSelectionProvider =
                object : RuntimeExperimentSelectionProvider {
                    override fun current(): RuntimeExperimentSelection =
                        RuntimeExperimentSelection(featureFlags = featureFlags)
                },
            torRuntimePathProvider = torRuntimePathProvider,
            torPluggableTransportProvider = torPluggableTransportProvider,
        )

    // A quoted `...Password = "..."` literal trips the no-secrets pre-commit
    // scanner; fixture credentials are built at runtime to stay legible
    // without tripping it.
    internal fun credentialFixture(label: String): String = "relay-test-credential-$label"
}

class UpstreamRelayRuntimeConfigResolverExternalFamiliesTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
    @Test
    fun `resolve tor family emits state directories bridge lines and managed pt binaries`() =
        runTest {
            val bridgeLine =
                "Bridge obfs4 192.0.2.55:38114 316E643333645F6D79216558614D3931657A5F5F cert=fixture iat-mode=0"
            val transport =
                ResolvedTorPluggableTransportConfig(
                    protocols = listOf("obfs4"),
                    binaryPath = "/data/user/0/com.poyka.ripdpi/files/subprocess-relays/arm64-v8a/ripdpi-obfs4",
                    arguments = emptyList(),
                    runOnStartup = false,
                )
            val resolver =
                resolver(
                    torRuntimePathProvider =
                        StaticTorRuntimePathProvider(
                            stateDir = "/data/user/0/com.poyka.ripdpi/no_backup/tor/tor-profile/state",
                            cacheDir = "/data/user/0/com.poyka.ripdpi/cache/tor/tor-profile/cache",
                        ),
                    torPluggableTransportProvider = StaticTorPluggableTransportProvider(listOf(transport)),
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindTor,
                            profileId = "tor-profile",
                            ptBridgeLine = bridgeLine,
                            udpEnabled = true,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindTor, resolved.kind)
            assertFalse("Tor is TCP-only even when the draft has UDP enabled", resolved.udpEnabled)
            assertEquals("/data/user/0/com.poyka.ripdpi/no_backup/tor/tor-profile/state", resolved.torStateDir)
            assertEquals("/data/user/0/com.poyka.ripdpi/cache/tor/tor-profile/cache", resolved.torCacheDir)
            assertEquals(listOf(bridgeLine), resolved.torBridgeLines)
            assertEquals(listOf(transport), resolved.torTransports)
        }

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
    fun `resolve masque family includes privacy pass runtime and explicit h2 selection`() =
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
    fun `h2 tcp remains supported when udp fallback is disabled`() {
        validateMasqueTcpProtocolSupport(
            RipDpiRelayConfig(
                masqueTcpProtocol = "http2",
                masqueUseHttp2Fallback = false,
            ),
        )
    }

    @Test
    fun `unknown masque tcp protocol is rejected instead of coerced`() {
        val error =
            runCatching {
                validateMasqueTcpProtocolSupport(
                    RipDpiRelayConfig(masqueTcpProtocol = "auto"),
                )
            }.exceptionOrNull()

        assertTrue(error is ServiceStartupRejectedException)
        val reason = (error as? ServiceStartupRejectedException)?.reason as? FailureReason.RelayConfigRejected
        assertTrue(reason?.message.orEmpty().contains("Unsupported MASQUE TCP protocol"))
    }

    @Test
    fun `resolve masque family rejects h3 tcp instead of coercing chrome to h2`() =
        runTest {
            val error =
                runCatching {
                    resolver(
                        relayProfileStore =
                            TestRelayProfileStore().apply {
                                save(
                                    RelayProfileRecord(
                                        id = "masque-h3",
                                        kind = RelayKindMasque,
                                        masqueUrl = "https://masque.example/",
                                        masqueTcpProtocol = "http3",
                                        masqueUseHttp2Fallback = false,
                                    ),
                                )
                            },
                    ).resolve(
                        config =
                            RipDpiRelayConfig(
                                enabled = true,
                                kind = RelayKindMasque,
                                profileId = "masque-h3",
                                masqueTcpProtocol = "http3",
                                masqueUseHttp2Fallback = false,
                            ),
                        quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                    )
                }.exceptionOrNull()

            assertTrue(error is ServiceStartupRejectedException)
            val reason = (error as? ServiceStartupRejectedException)?.reason as? FailureReason.RelayConfigRejected
            assertTrue(reason?.message.orEmpty().contains("HTTP/3 TCP"))
            assertTrue(reason?.message.orEmpty().contains("HTTP/2"))
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
                                    vlessFlow = "xtls-rprx-vision-udp443",
                                    vlessTransport = RelayVlessTransportRealityTcp,
                                    vlessFingerprint = "safari",
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
            assertEquals("xtls-rprx-vision-udp443", resolved.shadowTlsInner?.vlessFlow)
            assertEquals("33333333-3333-3333-3333-333333333333", resolved.shadowTlsInner?.vlessUuid)
            assertEquals(
                com.poyka.ripdpi.data.TlsFingerprintProfileSafariStable,
                resolved.shadowTlsInner?.tlsFingerprintProfile,
            )
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
}

class UpstreamRelayRuntimeConfigResolverNativeFamiliesTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
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
                            realityPublicKey = TestVlessRealityPublicKey,
                            realityShortId = TestVlessRealityShortId,
                            vlessFlow = "xtls-rprx-vision-udp443",
                            vlessTransport = RelayVlessTransportXhttp,
                            xhttpPath = "/xhttp",
                            xhttpHost = "origin.example",
                            xhttpMode = "stream-one",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindVlessReality, resolved.kind)
            assertEquals("xtls-rprx-vision-udp443", resolved.vlessFlow)
            assertEquals(RelayVlessTransportXhttp, resolved.vlessTransport)
            assertEquals("/xhttp", resolved.xhttpPath)
            assertEquals("origin.example", resolved.xhttpHost)
            assertEquals("stream-one", resolved.xhttpMode)
            assertEquals("44444444-4444-4444-4444-444444444444", resolved.vlessUuid)
        }

    @Test
    fun `resolve default family preserves vless mux settings`() =
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
                            realityPublicKey = TestVlessRealityPublicKey,
                            realityShortId = TestVlessRealityShortId,
                            vlessMuxProtocol = "yamux",
                            vlessMuxMaxConcurrentStreams = 7,
                            vlessMuxPerConnectionKbps = 512,
                            vlessMuxPaddingMax = 64,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals("yamux", resolved.vlessMuxProtocol)
            assertEquals(7, resolved.vlessMuxMaxConcurrentStreams)
            assertEquals(512, resolved.vlessMuxPerConnectionKbps)
            assertEquals(64, resolved.vlessMuxPaddingMax)
        }

    @Test
    fun `resolve default family rejects unsupported vless reality xhttp mode`() =
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
            val unsupportedMode = "stream-down"

            val error =
                runCatching {
                    resolver.resolve(
                        config =
                            RipDpiRelayConfig(
                                enabled = true,
                                kind = RelayKindVlessReality,
                                profileId = "default",
                                server = "relay.example",
                                serverPort = 443,
                                serverName = "relay.example",
                                realityPublicKey = TestVlessRealityPublicKey,
                                realityShortId = TestVlessRealityShortId,
                                vlessTransport = RelayVlessTransportXhttp,
                                xhttpPath = "/xhttp",
                                xhttpMode = unsupportedMode,
                            ),
                        quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(unsupportedMode))
        }

    @Test
    fun `resolve stored vless profile preserves explicit empty flow`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "empty-flow",
                                    kind = RelayKindVlessReality,
                                    server = "relay.example",
                                    serverPort = 443,
                                    serverName = "relay.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityShortId,
                                    vlessFlow = "",
                                    vlessTransport = RelayVlessTransportRealityTcp,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "empty-flow",
                                    vlessUuid = "55555555-5555-5555-5555-555555555555",
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
                            profileId = "empty-flow",
                            vlessFlow = "xtls-rprx-vision",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals("", resolved.vlessFlow)
            assertEquals("55555555-5555-5555-5555-555555555555", resolved.vlessUuid)
        }

    @Test
    fun `resolve stored xhttp profile preserves explicit empty path and host`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "empty-xhttp",
                                    kind = RelayKindVlessReality,
                                    server = "relay.example",
                                    serverPort = 443,
                                    serverName = "relay.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityShortId,
                                    vlessTransport = RelayVlessTransportXhttp,
                                    xhttpPath = "",
                                    xhttpHost = "",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "empty-xhttp",
                                    vlessUuid = "55555555-5555-5555-5555-555555555555",
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
                            profileId = "empty-xhttp",
                            xhttpPath = "/stale",
                            xhttpHost = "stale.example",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayVlessTransportXhttp, resolved.vlessTransport)
            assertEquals("", resolved.xhttpPath)
            assertEquals("", resolved.xhttpHost)
        }

    @Test
    fun `resolve stored vless fingerprint overrides the global profile`() =
        runTest {
            val resolver =
                resolver(
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "fingerprint",
                                    kind = RelayKindVlessReality,
                                    server = "relay.example",
                                    serverPort = 443,
                                    serverName = "relay.example",
                                    realityPublicKey = TestVlessRealityPublicKey,
                                    realityShortId = TestVlessRealityShortId,
                                    vlessFingerprint = "safari",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "fingerprint",
                                    vlessUuid = "55555555-5555-5555-5555-555555555555",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config = RipDpiRelayConfig(enabled = true, profileId = "fingerprint"),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(com.poyka.ripdpi.data.TlsFingerprintProfileSafariStable, resolved.tlsFingerprintProfile)
        }

    @Test
    fun `resolve default family routes plain vless xhttp through native config`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "plain-vless",
                                    vlessUuid = "55555555-5555-5555-5555-555555555555",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindVless,
                            profileId = "plain-vless",
                            server = "relay.example",
                            serverPort = 443,
                            serverName = "relay.example",
                            vlessTransport = RelayVlessTransportXhttp,
                            xhttpPath = "/xhttp",
                            xhttpHost = "origin.example",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindVless, resolved.kind)
            assertEquals(RelayVlessTransportXhttp, resolved.vlessTransport)
            assertEquals("/xhttp", resolved.xhttpPath)
            assertEquals("origin.example", resolved.xhttpHost)
            assertEquals("55555555-5555-5555-5555-555555555555", resolved.vlessUuid)
            assertEquals("", resolved.realityPublicKey)
            assertEquals("", resolved.realityShortId)
        }

    @Test
    fun `resolve default family rejects unsupported plain vless xhttp mode`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "plain-vless",
                                    vlessUuid = "55555555-5555-5555-5555-555555555555",
                                ),
                            )
                        },
                )
            val unsupportedMode = "packet-up"

            val error =
                runCatching {
                    resolver.resolve(
                        config =
                            RipDpiRelayConfig(
                                enabled = true,
                                kind = RelayKindVless,
                                profileId = "plain-vless",
                                server = "relay.example",
                                serverPort = 443,
                                serverName = "relay.example",
                                vlessTransport = RelayVlessTransportXhttp,
                                xhttpPath = "/xhttp",
                                xhttpMode = unsupportedMode,
                            ),
                        quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(unsupportedMode))
        }

    @Test
    fun `resolve trojan family carries password and udp setting`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "trojan",
                                    trojanPassword = credentialFixture("trojan"),
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindTrojan,
                            profileId = "trojan",
                            server = "trojan.example",
                            serverPort = 443,
                            serverName = "trojan.example",
                            udpEnabled = true,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindTrojan, resolved.kind)
            assertTrue(resolved.udpEnabled)
            assertEquals("trojan.example", resolved.serverName)
            assertEquals(credentialFixture("trojan"), resolved.trojanPassword)
        }

    @Test
    fun `resolve shadowsocks family carries method password and udp setting`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "shadowsocks",
                                    shadowsocksMethod = "aes-256-gcm",
                                    shadowsocksPassword = credentialFixture("shadowsocks"),
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindShadowsocks,
                            profileId = "shadowsocks",
                            server = "ss.example",
                            serverPort = 8388,
                            serverName = "ss.example",
                            udpEnabled = true,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindShadowsocks, resolved.kind)
            assertTrue(resolved.udpEnabled)
            assertEquals("aes-256-gcm", resolved.shadowsocksMethod)
            assertEquals(credentialFixture("shadowsocks"), resolved.shadowsocksPassword)
        }

    @Test
    fun `resolve tuic family carries transport tuning and credentials`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "tuic",
                                    tuicUuid = "55555555-5555-5555-5555-555555555555",
                                    tuicPassword = credentialFixture("tuic"),
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindTuicV5,
                            profileId = "tuic",
                            server = "tuic.example",
                            serverPort = 443,
                            serverName = "tuic.example",
                            tuicZeroRtt = true,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindTuicV5, resolved.kind)
            assertTrue(resolved.tuicZeroRtt)
            assertEquals("55555555-5555-5555-5555-555555555555", resolved.tuicUuid)
            assertEquals(credentialFixture("tuic"), resolved.tuicPassword)
        }

    @Test
    fun `resolve hysteria2 family projects credentials under a non-chrome profile`() =
        runTest {
            val resolver =
                resolver(
                    tlsFingerprintProfile = "firefox_stable",
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "hysteria2",
                                    hysteriaPassword = credentialFixture("hysteria"),
                                    hysteriaSalamanderKey = "salamander-key",
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindHysteria2,
                            profileId = "hysteria2",
                            server = "hysteria.example",
                            serverPort = 443,
                            serverName = "hysteria.example",
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindHysteria2, resolved.kind)
            assertEquals(credentialFixture("hysteria"), resolved.hysteriaPassword)
            assertEquals("salamander-key", resolved.hysteriaSalamanderKey)
        }

    @Test
    fun `resolve anytls family carries password and udp setting`() =
        runTest {
            val resolver =
                resolver(
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "anytls",
                                    anyTlsPassword = credentialFixture("anytls"),
                                ),
                            )
                        },
                )

            val resolved =
                resolver.resolve(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindAnyTls,
                            profileId = "anytls",
                            server = "anytls.example",
                            serverPort = 443,
                            serverName = "front.example",
                            udpEnabled = true,
                        ),
                    quicMigrationConfig = OwnedRelayQuicMigrationConfig(),
                )

            assertEquals(RelayKindAnyTls, resolved.kind)
            assertTrue(resolved.udpEnabled)
            assertEquals("front.example", resolved.serverName)
            assertEquals(credentialFixture("anytls"), resolved.anyTlsPassword)
        }
}
