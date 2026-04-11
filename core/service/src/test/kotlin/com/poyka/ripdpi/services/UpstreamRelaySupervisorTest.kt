package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamRelaySupervisorTest {
    private fun providerAuthFixture(): String = listOf("provider", "auth").joinToString("-")

    @Test
    fun `start resolves stored profile and credentials before native runtime launch`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val profileStore =
                TestRelayProfileStore().apply {
                    save(
                        RelayProfileRecord(
                            id = "edge",
                            kind = RelayKindVlessReality,
                            server = "relay.example",
                            serverPort = 8443,
                            serverName = "relay-sni.example",
                            realityPublicKey = "public-key",
                            realityShortId = "abcd1234",
                            localSocksPort = 1091,
                        ),
                    )
                }
            val credentialStore =
                TestRelayCredentialStore().apply {
                    save(
                        RelayCredentialRecord(
                            profileId = "edge",
                            vlessUuid = "00000000-0000-0000-0000-000000000000",
                        ),
                    )
                }
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore = profileStore,
                    relayCredentialStore = credentialStore,
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindVlessReality,
                        profileId = "edge",
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals("edge", resolved?.profileId)
            assertEquals("relay.example", resolved?.server)
            assertEquals("00000000-0000-0000-0000-000000000000", resolved?.vlessUuid)

            supervisor.stop()
        }

    @Test
    fun `start passes owned quic migration policy to native runtime`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore = TestRelayProfileStore(),
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "tuic",
                                    tuicUuid = "00000000-0000-0000-0000-000000000000",
                                    tuicPassword = "secret",
                                ),
                            )
                        },
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = "tuic_v5",
                        profileId = "tuic",
                        server = "relay.example",
                        serverPort = 443,
                        serverName = "relay.example",
                        udpEnabled = true,
                    ),
                quicMigrationConfig =
                    OwnedRelayQuicMigrationConfig(
                        bindLowPort = true,
                        migrateAfterHandshake = true,
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals(true, resolved?.quicBindLowPort)
            assertEquals(true, resolved?.quicMigrateAfterHandshake)

            supervisor.stop()
        }

    @Test
    fun `start resolves masque cloudflare direct identity and optional geohash`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "cf-masque",
                                    kind = RelayKindMasque,
                                    masqueUrl = "https://masque.example/",
                                    masqueCloudflareGeohashEnabled = true,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "cf-masque",
                                    masqueAuthMode = RelayMasqueAuthModeCloudflareMtls,
                                    masqueClientCertificateChainPem = "cert-chain",
                                    masqueClientPrivateKeyPem = "private-key",
                                ),
                            )
                        },
                    cloudflareMasqueGeohashResolver =
                        object : CloudflareMasqueGeohashResolver {
                            override suspend fun resolveHeaderValue(): String? = "u4pruyd-GB"
                        },
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindMasque,
                        profileId = "cf-masque",
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals(RelayMasqueAuthModeCloudflareMtls, resolved?.masqueAuthMode)
            assertEquals("cert-chain", resolved?.masqueClientCertificateChainPem)
            assertEquals("private-key", resolved?.masqueClientPrivateKeyPem)
            assertEquals("u4pruyd-GB", resolved?.masqueCloudflareGeohashHeader)

            supervisor.stop()
        }

    @Test
    fun `start fails fast when relay credentials are missing`() =
        runTest {
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore = TestRelayProfileStore(),
                    relayCredentialStore = TestRelayCredentialStore(),
                )

            try {
                supervisor.start(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindHysteria2,
                            profileId = "missing",
                            server = "relay.example",
                            serverPort = 8443,
                            serverName = "relay-sni.example",
                        ),
                    onUnexpectedExit = {},
                )
                fail("Expected relay startup to fail without credentials")
            } catch (_: IllegalArgumentException) {
            }
        }

    @Test
    fun `start passes VLESS xhttp settings through to native runtime`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindVlessReality,
                                    server = "relay.example",
                                    serverPort = 8443,
                                    serverName = "relay-sni.example",
                                    realityPublicKey = "public-key",
                                    realityShortId = "abcd1234",
                                    vlessTransport = RelayVlessTransportXhttp,
                                    xhttpPath = "/xhttp",
                                    xhttpHost = "origin.example",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "edge",
                                    vlessUuid = "00000000-0000-0000-0000-000000000000",
                                ),
                            )
                        },
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindVlessReality,
                        profileId = "edge",
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals(RelayVlessTransportXhttp, resolved?.vlessTransport)
            assertEquals("/xhttp", resolved?.xhttpPath)
            assertEquals("origin.example", resolved?.xhttpHost)

            supervisor.stop()
        }

    @Test
    fun `chain relay resolves referenced hop profiles before native runtime launch`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
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

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindChainRelay,
                        profileId = "chain",
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals("entry-hop", resolved?.chainEntryProfileId)
            assertEquals("entry.example", resolved?.chainEntryServer)
            assertEquals("11111111-1111-1111-1111-111111111111", resolved?.chainEntryUuid)
            assertEquals("exit-hop", resolved?.chainExitProfileId)
            assertEquals("exit.example", resolved?.chainExitServer)
            assertEquals("22222222-2222-2222-2222-222222222222", resolved?.chainExitUuid)

            supervisor.stop()
        }

    @Test
    fun `start resolves masque privacy pass provider before native runtime launch`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val providerAuthToken = providerAuthFixture()
            val provider =
                object : MasquePrivacyPassProvider {
                    override fun isAvailable(): Boolean = true

                    override fun buildStatus(): MasquePrivacyPassBuildStatus = MasquePrivacyPassBuildStatus.Available

                    override fun readinessFor(
                        config: RipDpiRelayConfig,
                        credentials: RelayCredentialRecord?,
                    ): MasquePrivacyPassReadiness = MasquePrivacyPassReadiness.Ready

                    override suspend fun resolve(
                        profileId: String,
                        config: RipDpiRelayConfig,
                        credentials: RelayCredentialRecord?,
                    ): MasquePrivacyPassRuntimeConfig? =
                        MasquePrivacyPassRuntimeConfig(
                            providerUrl = "https://provider.example/token",
                            providerAuthToken = providerAuthToken,
                        )
                }
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindMasque,
                                    masqueUrl = "https://masque.example/",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "edge",
                                    masqueAuthMode = RelayMasqueAuthModePrivacyPass,
                                ),
                            )
                        },
                    masquePrivacyPassProvider = provider,
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindMasque,
                        profileId = "edge",
                        udpEnabled = true,
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals(RelayMasqueAuthModePrivacyPass, resolved?.masqueAuthMode)
            assertEquals("https://provider.example/token", resolved?.masquePrivacyPassProviderUrl)
            assertEquals(providerAuthToken, resolved?.masquePrivacyPassProviderAuthToken)
            assertNull(resolved?.masqueAuthToken)

            supervisor.stop()
        }

    @Test
    fun `start fails fast when masque privacy pass provider is unavailable`() =
        runTest {
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindMasque,
                                    masqueUrl = "https://masque.example/",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "edge",
                                    masqueAuthMode = RelayMasqueAuthModePrivacyPass,
                                ),
                            )
                        },
                )

            try {
                supervisor.start(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindMasque,
                            profileId = "edge",
                            udpEnabled = true,
                        ),
                    onUnexpectedExit = {},
                )
                fail("Expected MASQUE privacy_pass startup to fail without a provider")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message?.contains("provider URL") == true)
            }
        }

    @Test
    fun `cloudflare tunnel resolves to xhttp transport and disables udp`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "cf",
                                    kind = RelayKindCloudflareTunnel,
                                    server = "edge.example.com",
                                    serverName = "edge.example.com",
                                    vlessTransport = RelayVlessTransportXhttp,
                                    xhttpPath = "/xhttp",
                                    xhttpHost = "origin.example.com",
                                    udpEnabled = true,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "cf",
                                    vlessUuid = "00000000-0000-0000-0000-000000000000",
                                ),
                            )
                        },
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindCloudflareTunnel,
                        profileId = "cf",
                        udpEnabled = true,
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals(RelayKindCloudflareTunnel, resolved?.kind)
            assertEquals(RelayVlessTransportXhttp, resolved?.vlessTransport)
            assertEquals(false, resolved?.udpEnabled)
            assertEquals(TlsFingerprintProfileChromeStable, resolved?.tlsFingerprintProfile)
            supervisor.stop()
        }

    @Test
    fun `strict chrome fingerprint rejects hysteria2 startup`() =
        runTest {
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindHysteria2,
                                    server = "relay.example",
                                    serverPort = 8443,
                                    serverName = "relay-sni.example",
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "edge",
                                    hysteriaPassword = "fixture-pass",
                                ),
                            )
                        },
                )

            try {
                supervisor.start(
                    config =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindHysteria2,
                            profileId = "edge",
                        ),
                    onUnexpectedExit = {},
                )
                fail("Expected strict TLS policy to reject Hysteria2")
            } catch (error: ServiceStartupRejectedException) {
                assertTrue(error.reason is FailureReason.RelayFingerprintPolicyRejected)
            }
        }
}
