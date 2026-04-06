package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                            kind = RelayKindHysteria2,
                            server = "relay.example",
                            serverPort = 8443,
                            serverName = "relay-sni.example",
                            localSocksPort = 1091,
                        ),
                    )
                }
            val credentialStore =
                TestRelayCredentialStore().apply {
                    save(
                        RelayCredentialRecord(
                            profileId = "edge",
                            hysteriaPassword = "secret",
                        ),
                    )
                }
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    relayProfileStore = profileStore,
                    relayCredentialStore = credentialStore,
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindHysteria2,
                        profileId = "edge",
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals("edge", resolved?.profileId)
            assertEquals("relay.example", resolved?.server)
            assertEquals("secret", resolved?.hysteriaPassword)

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
    fun `start passes hysteria salamander and udp settings through to native runtime`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val supervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    relayFactory = relayFactory,
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindHysteria2,
                                    server = "relay.example",
                                    serverPort = 8443,
                                    serverName = "relay-sni.example",
                                    udpEnabled = true,
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "edge",
                                    hysteriaPassword = "secret",
                                    hysteriaSalamanderKey = "salamander",
                                ),
                            )
                        },
                )

            supervisor.start(
                config =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindHysteria2,
                        profileId = "edge",
                        udpEnabled = true,
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals("salamander", resolved?.hysteriaSalamanderKey)
            assertEquals(true, resolved?.udpEnabled)

            supervisor.stop()
        }

    @Test
    fun `start resolves masque privacy pass provider before native runtime launch`() =
        runTest {
            val relayFactory = TestRipDpiRelayFactory()
            val providerAuthToken = providerAuthFixture()
            val provider =
                object : MasquePrivacyPassProvider {
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
                    relayProfileStore =
                        TestRelayProfileStore().apply {
                            save(
                                RelayProfileRecord(
                                    id = "edge",
                                    kind = RelayKindMasque,
                                    masqueUrl = "https://masque.example/",
                                    masqueCloudflareMode = true,
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
}
