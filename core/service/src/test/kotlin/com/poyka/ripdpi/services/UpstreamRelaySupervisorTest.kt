package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamRelaySupervisorTest {
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
                            hysteriaSalamanderKey = "salamander",
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
}
