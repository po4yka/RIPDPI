package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamRelayRuntimeConfigResolverTest {
    @Test
    fun `resolve merges stored profile credentials and quic migration config`() =
        runTest {
            val resolver =
                DefaultUpstreamRelayRuntimeConfigResolver(
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
                    cloudflareMasqueGeohashResolver =
                        object : CloudflareMasqueGeohashResolver {
                            override suspend fun resolveHeaderValue(): String? = null
                        },
                    masquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
                    tlsFingerprintProfileProvider =
                        object : OwnedTlsFingerprintProfileProvider {
                            override fun currentProfile(): String = "firefox"
                        },
                    runtimeExperimentSelectionProvider =
                        object : RuntimeExperimentSelectionProvider {
                            override fun current(): RuntimeExperimentSelection =
                                RuntimeExperimentSelection(
                                    featureFlags =
                                        mapOf(
                                            com.poyka.ripdpi.data.StrategyFeatureCloudflarePublish to true,
                                        ),
                                )
                        },
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
    fun `resolve includes privacy pass runtime when masque profile requests it`() =
        runTest {
            val resolver =
                DefaultUpstreamRelayRuntimeConfigResolver(
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
                    cloudflareMasqueGeohashResolver =
                        object : CloudflareMasqueGeohashResolver {
                            override suspend fun resolveHeaderValue(): String? = null
                        },
                    masquePrivacyPassProvider =
                        StaticMasquePrivacyPassProvider(
                            available = true,
                            providerUrl = "https://issuer.example/token",
                        ),
                    tlsFingerprintProfileProvider =
                        object : OwnedTlsFingerprintProfileProvider {
                            override fun currentProfile(): String = TlsFingerprintProfileChromeStable
                        },
                    runtimeExperimentSelectionProvider =
                        object : RuntimeExperimentSelectionProvider {
                            override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                        },
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
}
