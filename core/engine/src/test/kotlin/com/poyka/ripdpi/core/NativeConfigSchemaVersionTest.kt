package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.NativeProxyConfig
import com.poyka.ripdpi.core.codec.NativeProxyConfigSchemaVersion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Golden coverage for the additive `schemaVersion` envelope field on the
 * proxy, relay, and tunnel native-config wire DTOs.
 *
 * The field is `@EncodeDefault(NEVER)`, so the current version never reaches
 * the wire when the default value is used. These tests pin both halves of the
 * contract: a legacy relay payload that omits `schemaVersion` decodes to the
 * current relay schema version, a payload that carries the current version
 * decodes to the same, and the encoder emits no `schemaVersion` key for
 * defaulted payloads. See `docs/architecture/CONFIG_CONTRACTS.md` §8.
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
    fun `proxy ui payload — legacy and versioned forms both decode to version 1`() {
        val legacy = proxyJson.decodeFromString(NativeProxyConfig.serializer(), """{"kind":"ui"}""")
        val versioned =
            proxyJson.decodeFromString(NativeProxyConfig.serializer(), """{"kind":"ui","schemaVersion":1}""")

        assertEquals(NativeProxyConfigSchemaVersion, (legacy as NativeProxyConfig.Ui).schemaVersion)
        assertEquals(NativeProxyConfigSchemaVersion, (versioned as NativeProxyConfig.Ui).schemaVersion)
    }

    @Test
    fun `proxy command-line payload — legacy and versioned forms both decode to version 1`() {
        val legacy =
            proxyJson.decodeFromString(NativeProxyConfig.serializer(), """{"kind":"command_line","args":["ripdpi"]}""")
        val versioned =
            proxyJson.decodeFromString(
                NativeProxyConfig.serializer(),
                """{"kind":"command_line","args":["ripdpi"],"schemaVersion":1}""",
            )

        assertEquals(NativeProxyConfigSchemaVersion, (legacy as NativeProxyConfig.CommandLine).schemaVersion)
        assertEquals(NativeProxyConfigSchemaVersion, (versioned as NativeProxyConfig.CommandLine).schemaVersion)
    }

    @Test
    fun `proxy payloads omit schemaVersion on the wire`() {
        val ui = proxyJson.encodeToString(NativeProxyConfig.serializer(), NativeProxyConfig.Ui())
        val cmd =
            proxyJson.encodeToString(
                NativeProxyConfig.serializer(),
                NativeProxyConfig.CommandLine(args = listOf("ripdpi")),
            )

        assertFalse("ui payload must not carry schemaVersion at v1", ui.contains("schemaVersion"))
        assertFalse("command-line payload must not carry schemaVersion at v1", cmd.contains("schemaVersion"))
    }

    @Test
    fun `tunnel payload — legacy and versioned forms both decode to version 1`() {
        val legacy = tunnelJson.decodeFromString(Tun2SocksConfig.serializer(), """{"socks5Port":1080}""")
        val versioned =
            tunnelJson.decodeFromString(Tun2SocksConfig.serializer(), """{"socks5Port":1080,"schemaVersion":1}""")

        assertEquals(Tun2SocksConfigSchemaVersion, legacy.schemaVersion)
        assertEquals(Tun2SocksConfigSchemaVersion, versioned.schemaVersion)
    }

    @Test
    fun `tunnel payload omits schemaVersion on the wire`() {
        val encoded = tunnelJson.encodeToString(Tun2SocksConfig.serializer(), Tun2SocksConfig(socks5Port = 1080))

        assertFalse("tunnel payload must not carry schemaVersion at v1", encoded.contains("schemaVersion"))
    }

    @Test
    fun `relay payload — legacy and versioned forms both decode to current relay schema version`() {
        val encoded = relayJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), sampleRelayConfig())
        assertFalse("relay payload must not carry schemaVersion at current default", encoded.contains("schemaVersion"))

        val legacy = relayJson.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)
        val versioned =
            relayJson.decodeFromString(
                ResolvedRipDpiRelayConfig.serializer(),
                encoded.replaceFirst("{", """{"schemaVersion":6,"""),
            )

        assertEquals(RelayNativeConfigSchemaVersion, legacy.schemaVersion)
        assertEquals(6, RelayNativeConfigSchemaVersion)
        assertEquals(RelayNativeConfigSchemaVersion, versioned.schemaVersion)
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
        )
}
