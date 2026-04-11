package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class FinalmaskCompatibilityTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (!Files.exists(current.resolve("docs"))) {
            current = current.parent ?: break
        }
        return current
    }

    private fun examplePath(name: String): Path = repoRoot().resolve(Path.of("docs", "examples", "finalmask", name))

    private fun readExample(name: String): String = examplePath(name).toFile().readText()

    @Test
    fun `xhttp finalmask example documents the expected server fields`() {
        val example = readExample("xhttp-finalmask-server.json")

        assertTrue(example.contains("\"type\": \"xhttp\""))
        assertTrue(example.contains("\"finalmask\""))
        assertTrue(example.contains("\"xPaddingBytes\""))
    }

    @Test
    fun `cloudflare tunnel finalmask example documents the expected server fields`() {
        val example = readExample("cloudflare-tunnel-finalmask-server.json")

        assertTrue(example.contains("\"type\": \"xhttp\""))
        assertTrue(example.contains("\"cloudflareTunnel\""))
        assertTrue(example.contains("\"finalmask\""))
    }

    @Test
    fun `xhttp finalmask client profile resolves through the relay supervisor`() =
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
                                json.decodeFromString(
                                    RelayProfileRecord.serializer(),
                                    readExample("xhttp-client-profile.json"),
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "finalmask-xhttp",
                                    vlessUuid = "00000000-0000-0000-0000-000000000000",
                                ),
                            )
                        },
                )

            supervisor.start(
                config =
                    com.poyka.ripdpi.core.RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindVlessReality,
                        profileId = "finalmask-xhttp",
                    ),
                onUnexpectedExit = {},
            )

            val resolved = relayFactory.lastRuntime.lastConfig
            assertEquals(RelayKindVlessReality, resolved?.kind)
            assertEquals(RelayVlessTransportXhttp, resolved?.vlessTransport)
            assertEquals("/xhttp", resolved?.xhttpPath)
            assertEquals("origin.example.com", resolved?.xhttpHost)
            assertEquals(false, resolved?.udpEnabled)

            supervisor.stop()
        }

    @Test
    fun `cloudflare tunnel finalmask client profile resolves through the relay supervisor`() =
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
                                json.decodeFromString(
                                    RelayProfileRecord.serializer(),
                                    readExample("cloudflare-tunnel-client-profile.json"),
                                ),
                            )
                        },
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "finalmask-cloudflare-tunnel",
                                    vlessUuid = "00000000-0000-0000-0000-000000000000",
                                ),
                            )
                        },
                )

            supervisor.start(
                config =
                    com.poyka.ripdpi.core.RipDpiRelayConfig(
                        enabled = true,
                        kind = RelayKindCloudflareTunnel,
                        profileId = "finalmask-cloudflare-tunnel",
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
}
