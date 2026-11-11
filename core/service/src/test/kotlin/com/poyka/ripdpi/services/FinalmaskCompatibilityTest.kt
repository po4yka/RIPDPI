package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.StrategyFeatureFinalmask
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `xhttp example uses a fixed prefix and no server response mask`() {
        val server = json.parseToJsonElement(readExample("xhttp-finalmask-server.json")).jsonObject
        val stream =
            server
                .getValue("inbounds")
                .jsonArray
                .single()
                .jsonObject
                .getValue("streamSettings")
                .jsonObject
        assertEquals("xhttp", stream.getValue("network").jsonPrimitive.content)
        val mask =
            stream
                .getValue("finalmask")
                .jsonObject
                .getValue("tcp")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("header-custom", mask.getValue("type").jsonPrimitive.content)
        val settings = mask.getValue("settings").jsonObject
        assertTrue(settings.getValue("servers").jsonArray.isEmpty())
        val sequence =
            settings
                .getValue("clients")
                .jsonArray
                .single()
                .jsonArray
        assertEquals(
            "4",
            sequence[1]
                .jsonObject
                .getValue("rand")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `cloudflare origin does not remove the edge TLS mask`() {
        val server = json.parseToJsonElement(readExample("cloudflare-tunnel-finalmask-server.json")).jsonObject
        val stream =
            server
                .getValue("inbounds")
                .jsonArray
                .single()
                .jsonObject
                .getValue("streamSettings")
                .jsonObject
        assertEquals("xhttp", stream.getValue("network").jsonPrimitive.content)
        assertEquals("none", stream.getValue("security").jsonPrimitive.content)
        assertTrue("finalmask" !in stream)
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
                    selectorRelayRuntimeProfileResolver = TestSelectorRelayRuntimeProfileResolver(),
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "finalmask-xhttp",
                                    vlessUuid = "00000000-0000-4000-8000-000000000006",
                                ),
                            )
                        },
                )

            supervisor.start(
                expectedSelection =
                    com.poyka.ripdpi.data
                        .RuntimeConfigurationSelection("native"),
                inputs = testRelayResolutionInputs(featureFlags = mapOf(StrategyFeatureFinalmask to true)),
                requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
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
            assertEquals("header_custom", resolved?.finalmask?.type)
            assertEquals("4-4", resolved?.finalmask?.randRange)
            assertEquals("/xhttp", resolved?.xhttpPath)
            assertEquals("fixture.example", resolved?.xhttpHost)
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
                    selectorRelayRuntimeProfileResolver = TestSelectorRelayRuntimeProfileResolver(),
                    relayCredentialStore =
                        TestRelayCredentialStore().apply {
                            save(
                                RelayCredentialRecord(
                                    profileId = "finalmask-cloudflare-tunnel",
                                    vlessUuid = "00000000-0000-4000-8000-000000000006",
                                ),
                            )
                        },
                )

            supervisor.start(
                expectedSelection =
                    com.poyka.ripdpi.data
                        .RuntimeConfigurationSelection("native"),
                inputs = testRelayResolutionInputs(featureFlags = mapOf(StrategyFeatureFinalmask to true)),
                requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
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
            assertEquals("fragment", resolved?.finalmask?.type)
            assertEquals(3, resolved?.finalmask?.fragmentPackets)

            supervisor.stop()
        }
}
