package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.NativeProxyConfig
import com.poyka.ripdpi.core.codec.NativeProxyConfigSchemaVersion
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the required `schemaVersion` envelope on proxy, relay, and
 * tunnel native-config wire DTOs. Current producers must emit it and consumers
 * must reject missing, older, and future versions.
 */
class NativeConfigSchemaVersionTest {
    private val proxyJson =
        Json {
            classDiscriminator = "kind"
            encodeDefaults = true
        }
    private val tunnelJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val relayJson = Json { encodeDefaults = true }

    @Test
    fun `proxy ui payload requires current schema version`() {
        assertThrows(SerializationException::class.java) {
            proxyJson.decodeFromString(NativeProxyConfig.serializer(), """{"kind":"ui"}""")
        }
        val current =
            proxyJson.decodeFromString(NativeProxyConfig.serializer(), """{"kind":"ui","schemaVersion":2}""")

        assertEquals(NativeProxyConfigSchemaVersion, (current as NativeProxyConfig.Ui).schemaVersion)
    }

    @Test
    fun `proxy command-line payload requires current schema version`() {
        assertThrows(SerializationException::class.java) {
            proxyJson.decodeFromString(
                NativeProxyConfig.serializer(),
                """{"kind":"command_line","args":["ripdpi"]}""",
            )
        }
        val current =
            proxyJson.decodeFromString(
                NativeProxyConfig.serializer(),
                """{"kind":"command_line","args":["ripdpi"],"schemaVersion":2}""",
            )

        assertEquals(NativeProxyConfigSchemaVersion, (current as NativeProxyConfig.CommandLine).schemaVersion)
    }

    @Test
    fun `proxy payloads emit schemaVersion on the wire`() {
        val ui = proxyJson.encodeToString(NativeProxyConfig.serializer(), NativeProxyConfig.Ui())
        val cmd =
            proxyJson.encodeToString(
                NativeProxyConfig.serializer(),
                NativeProxyConfig.CommandLine(args = listOf("ripdpi")),
            )

        assertTrue("ui payload must carry schemaVersion", ui.contains("\"schemaVersion\":2"))
        assertTrue("command-line payload must carry schemaVersion", cmd.contains("\"schemaVersion\":2"))
    }

    @Test
    fun `proxy codec rejects unsupported schema versions`() {
        assertThrows(IllegalArgumentException::class.java) {
            RipDpiProxyJsonCodec.stripRuntimeContext("""{"kind":"ui","schemaVersion":1,"listen":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RipDpiProxyJsonCodec.stripRuntimeContext(
                """{"kind":"command_line","args":["ripdpi"],"schemaVersion":3}""",
            )
        }
    }

    @Test
    fun `tunnel payload requires current schema version`() {
        assertThrows(SerializationException::class.java) {
            tunnelJson.decodeFromString(Tun2SocksConfig.serializer(), """{"socks5Port":1080}""")
        }
        val current =
            tunnelJson.decodeFromString(Tun2SocksConfig.serializer(), """{"socks5Port":1080,"schemaVersion":2}""")

        assertEquals(Tun2SocksConfigSchemaVersion, current.schemaVersion)
    }

    @Test
    fun `tunnel payload emits schemaVersion on the wire`() {
        val encoded = tunnelJson.encodeToString(Tun2SocksConfig.serializer(), Tun2SocksConfig(socks5Port = 1080))

        assertTrue("tunnel payload must carry schemaVersion", encoded.contains("\"schemaVersion\":2"))
        assertTrue(
            "tunnel payload must default ICMP UID policy to false",
            encoded.contains("\"uidPolicyAllowIcmp\":false"),
        )
    }

    @Test
    fun `relay payload requires and emits current schema version`() {
        val encoded = relayJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), sampleRelayConfig())
        val current = relayJson.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)
        val withoutSchema = encoded.replace(",\"schemaVersion\":10", "")

        assertTrue(encoded.contains("\"schemaVersion\":10"))
        assertThrows(SerializationException::class.java) {
            relayJson.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), withoutSchema)
        }
        assertEquals(10, RelayNativeConfigSchemaVersion)
        assertEquals(RelayNativeConfigSchemaVersion, current.schemaVersion)
    }

    @Test
    fun `relay start rejects retired and future schema versions before JNI`() =
        runTest {
            for (version in listOf(9, 11)) {
                val bindings = FakeRipDpiRelayBindings()
                val relay = RipDpiRelay(bindings)

                val error =
                    runCatching {
                        relay.start(
                            sampleRelayConfig().copy(schemaVersion = version),
                        )
                    }.exceptionOrNull()

                assertTrue(error is IllegalArgumentException)
                assertEquals(null, bindings.lastCreatePayload)
            }
        }

    @Test
    fun `relay tor payload carries bridge pt bootstrap fields`() {
        val config =
            sampleRelayConfig().copy(
                enabled = true,
                kind = "tor",
                torStateDir = "/data/user/0/com.poyka.ripdpi/no_backup/tor/default/state",
                torCacheDir = "/data/user/0/com.poyka.ripdpi/cache/tor/default/cache",
                torBridgeLines =
                    listOf(
                        "Bridge obfs4 192.0.2.55:38114 " +
                            "316E643333645F6D79216558614D3931657A5F5F cert=fixture iat-mode=0",
                    ),
                torTransports =
                    listOf(
                        ResolvedTorPluggableTransportConfig(
                            protocols = listOf("obfs4"),
                            binaryPath = "/data/user/0/com.poyka.ripdpi/files/subprocess-relays/arm64-v8a/ripdpi-obfs4",
                            arguments = emptyList(),
                            runOnStartup = false,
                        ),
                    ),
            )

        val encoded = relayJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
        val decoded = relayJson.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)

        assertEquals("/data/user/0/com.poyka.ripdpi/no_backup/tor/default/state", decoded.torStateDir)
        assertEquals("/data/user/0/com.poyka.ripdpi/cache/tor/default/cache", decoded.torCacheDir)
        assertEquals(config.torBridgeLines, decoded.torBridgeLines)
        assertEquals(config.torTransports, decoded.torTransports)
    }

    private fun sampleRelayConfig(): ResolvedRipDpiRelayConfig =
        ResolvedRipDpiRelayConfig(
            enabled = false,
            kind = "off",
            profileId = "default",
            server = "",
            serverPort = 0,
            serverName = "",
            realityPublicKey = "",
            realityShortId = "",
            chainEntryServer = "",
            chainEntryPort = 0,
            chainEntryServerName = "",
            chainEntryPublicKey = "",
            chainEntryShortId = "",
            chainExitServer = "",
            chainExitPort = 0,
            chainExitServerName = "",
            chainExitPublicKey = "",
            chainExitShortId = "",
            masqueUrl = "",
            masqueUseHttp2Fallback = false,
            localSocksHost = "127.0.0.1",
            localSocksPort = 1080,
            udpEnabled = false,
            tcpFallbackEnabled = true,
            tlsFingerprintProfile = "chrome_stable",
        )
}
