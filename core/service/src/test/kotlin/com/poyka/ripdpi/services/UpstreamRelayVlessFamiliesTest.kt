package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamRelayVlessFamiliesTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
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
                            vlessFlow = "",
                            vlessTransport = RelayVlessTransportXhttp,
                            xhttpPath = "/xhttp",
                            xhttpHost = "origin.example",
                            xhttpMode = "stream-one",
                        ),
                    inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                    expectedSelection =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationSelection("native"),
                )

            assertEquals(RelayKindVlessReality, resolved.kind)
            assertEquals("", resolved.vlessFlow)
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
                    inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                    expectedSelection =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationSelection("native"),
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
                        inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                        expectedSelection =
                            com.poyka.ripdpi.data
                                .RuntimeConfigurationSelection("native"),
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
                    inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                    expectedSelection =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationSelection("native"),
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
                                    vlessFlow = "",
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
                    inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                    expectedSelection =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationSelection("native"),
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
                    inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                    expectedSelection =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationSelection("native"),
                )

            assertEquals(com.poyka.ripdpi.data.TlsFingerprintProfileSafariStable, resolved.tlsFingerprintProfile)
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
            val unsupportedMode = "stream-down"

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
                        inputs = testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                        expectedSelection =
                            com.poyka.ripdpi.data
                                .RuntimeConfigurationSelection("native"),
                    )
                }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(unsupportedMode))
        }
}
