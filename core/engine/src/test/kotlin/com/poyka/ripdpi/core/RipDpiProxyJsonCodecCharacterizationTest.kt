package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.EnvironmentKind
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests that lock the *current* JSON-mapping behavior of
 * [RipDpiProxyJsonCodec] before an upcoming refactor.
 *
 * These complement:
 *  - [NativeConfigContractSnapshotTest] — whole-payload snapshot pinning.
 *  - [ConfigContractRoundTripTest] — encode/decode structural round trips.
 *  - [RipDpiProxyJsonCodecTest] — session-override / geo-path / chain round trips.
 *
 * Focus here is the gaps those leave open:
 *  - encrypted DNS / runtime-context sections (area 5 + 7).
 *  - root-mode true/false and the `EncodeDefault.NEVER` handling of
 *    `rootHelperSocketPath` (area 6).
 *  - section-level structure of an *enabled* relay / WARP payload emitted
 *    directly through [RipDpiProxyJsonCodec.encodeUiPreferences] (area 4).
 *
 * Assertions inspect parsed JSON sections, never whole-string formatting, so
 * they survive whitespace / key-order changes but lock which keys, enum
 * strings, `kind` discriminator and nested sections the codec emits today.
 */
class RipDpiProxyJsonCodecCharacterizationTest {
    private fun encode(
        preferences: RipDpiProxyUIPreferences,
        rootMode: Boolean = false,
        rootHelperSocketPath: String? = null,
        environmentKind: EnvironmentKind = EnvironmentKind.Unknown,
    ): JsonObject =
        Json
            .parseToJsonElement(
                RipDpiProxyJsonCodec.encodeUiPreferences(
                    preferences = preferences,
                    rootMode = rootMode,
                    rootHelperSocketPath = rootHelperSocketPath,
                    environmentKind = environmentKind,
                ),
            ).jsonObject

    // ------------------------------------------------------------------
    // Area 1 + 7: baseline default config keeps the ui discriminator and
    // every grouped section; deterministic runtime/session slots stay null.
    // ------------------------------------------------------------------

    @Test
    fun `default ui preferences emit ui discriminator with every grouped section`() {
        val payload = encode(RipDpiProxyUIPreferences())

        assertEquals("ui", payload["kind"]?.jsonPrimitive?.content)
        // Every grouped section the codec advertises must be present as an object.
        for (
        section in
        listOf(
            "listen",
            "protocols",
            "chains",
            "fakePackets",
            "parserEvasions",
            "adaptiveFallback",
            "quic",
            "hosts",
            "upstreamRelay",
            "warp",
            "hostAutolearn",
            "wsTunnel",
        )
        ) {
            assertNotNull("section `$section` must be present", payload[section]?.jsonObject)
        }
        assertEquals("Unknown", payload["environmentKind"]?.jsonPrimitive?.content)
        assertEquals(false, payload["rootMode"]?.jsonPrimitive?.boolean)
        // Deterministic runtime / session slots default to JSON null.
        assertNull(payload["runtimeContext"]?.jsonPrimitive?.contentOrNull)
        assertNull(payload["logContext"]?.jsonPrimitive?.contentOrNull)
        assertNull(payload["sessionOverrides"]?.jsonPrimitive?.contentOrNull)
        assertNull(payload["strategyPreset"]?.jsonPrimitive?.contentOrNull)
        // EncodeDefault.NEVER fields are absent (not null) when at default.
        assertFalse("rootHelperSocketPath omitted at default", payload.containsKey("rootHelperSocketPath"))
        assertFalse("geoipDbPath omitted at default", payload.containsKey("geoipDbPath"))
        assertFalse("geositeDbPath omitted at default", payload.containsKey("geositeDbPath"))
        assertFalse("nativeLogLevel omitted at default", payload.containsKey("nativeLogLevel"))
    }

    // ------------------------------------------------------------------
    // Area 2: custom listen / protocol values map into their sections.
    // ------------------------------------------------------------------

    @Test
    fun `custom listen and protocol values land in their json sections`() {
        val preferences =
            RipDpiProxyUIPreferences(
                listen =
                    RipDpiListenConfig(
                        ip = "0.0.0.0",
                        port = 3128,
                        maxConnections = 256,
                        bufferSize = 8192,
                        tcpFastOpen = true,
                        defaultTtl = 48,
                        customTtl = true,
                    ),
                protocols =
                    RipDpiProtocolConfig(
                        resolveDomains = false,
                        desyncHttp = false,
                        desyncHttps = true,
                        desyncUdp = true,
                    ),
            )

        val listen = encode(preferences)["listen"]!!.jsonObject
        assertEquals("0.0.0.0", listen["ip"]?.jsonPrimitive?.content)
        assertEquals(3128, listen["port"]?.jsonPrimitive?.int)
        assertEquals(256, listen["maxConnections"]?.jsonPrimitive?.int)
        assertEquals(8192, listen["bufferSize"]?.jsonPrimitive?.int)
        assertEquals(true, listen["tcpFastOpen"]?.jsonPrimitive?.boolean)
        assertEquals(48, listen["defaultTtl"]?.jsonPrimitive?.int)
        assertEquals(true, listen["customTtl"]?.jsonPrimitive?.boolean)

        val protocols = encode(preferences)["protocols"]!!.jsonObject
        assertEquals(false, protocols["resolveDomains"]?.jsonPrimitive?.boolean)
        assertEquals(false, protocols["desyncHttp"]?.jsonPrimitive?.boolean)
        assertEquals(true, protocols["desyncHttps"]?.jsonPrimitive?.boolean)
        assertEquals(true, protocols["desyncUdp"]?.jsonPrimitive?.boolean)
    }

    // ------------------------------------------------------------------
    // Area 3: a non-trivial TCP + UDP strategy chain serializes step kinds
    // as wire strings, preserving order across both lists.
    // ------------------------------------------------------------------

    @Test
    fun `tcp and udp strategy chain emit ordered wire kind strings`() {
        val preferences =
            RipDpiProxyUIPreferences(
                protocols = RipDpiProtocolConfig(desyncUdp = true),
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                                TcpChainStepModel(kind = TcpChainStepKind.Fake, marker = "host+1"),
                                TcpChainStepModel(kind = TcpChainStepKind.Split, marker = "host+2"),
                            ),
                        udpSteps =
                            listOf(
                                UdpChainStepModel(kind = UdpChainStepKind.DummyPrepend, count = 1),
                                UdpChainStepModel(kind = UdpChainStepKind.FakeBurst, count = 3),
                            ),
                    ),
            )

        val chains = encode(preferences)["chains"]!!.jsonObject
        val tcpSteps = chains["tcpSteps"]!!.jsonArray
        val udpSteps = chains["udpSteps"]!!.jsonArray

        assertEquals(
            listOf("tlsrec", "fake", "split"),
            tcpSteps.map { it.jsonObject["kind"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("extlen", "host+1", "host+2"),
            tcpSteps.map { it.jsonObject["marker"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("dummy_prepend", "fake_burst"),
            udpSteps.map { it.jsonObject["kind"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf(1, 3),
            udpSteps.map { it.jsonObject["count"]?.jsonPrimitive?.int },
        )
    }

    // ------------------------------------------------------------------
    // Area 4: an enabled relay + WARP payload keeps both sections and their
    // nested objects under the stable wire keys.
    // ------------------------------------------------------------------

    @Test
    fun `enabled relay and warp sections keep nested objects and wire keys`() {
        val preferences =
            RipDpiProxyUIPreferences(
                relay =
                    RipDpiRelayConfig(
                        enabled = true,
                        kind = com.poyka.ripdpi.data.RelayKindMasque,
                        profileId = "relay-primary",
                        masqueUrl = "https://example.cloudflare.com/.well-known/masque/udp/203.0.113.5/443/",
                        masqueUseHttp2Fallback = true,
                        masqueCloudflareGeohashEnabled = true,
                        localSocksHost = "127.0.0.5",
                        localSocksPort = 11985,
                    ),
                warp =
                    RipDpiWarpConfig(
                        enabled = true,
                        routeMode = "rules",
                        routeHosts = "blocked.example",
                        endpointSelectionMode = "manual",
                        manualEndpoint =
                            RipDpiWarpManualEndpointConfig(
                                host = "engage.cloudflareclient.com",
                                ipv4 = "162.159.193.10",
                                port = 2409,
                            ),
                        amneziaPreset = "custom",
                        amnezia = RipDpiWarpAmneziaConfig(enabled = true, jc = 4, jmin = 1, jmax = 8),
                    ),
            )
        val payload = encode(preferences)

        val relay = payload["upstreamRelay"]!!.jsonObject
        assertEquals(true, relay["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(com.poyka.ripdpi.data.RelayKindMasque, relay["kind"]?.jsonPrimitive?.content)
        assertEquals("relay-primary", relay["profileId"]?.jsonPrimitive?.content)
        assertEquals(
            "https://example.cloudflare.com/.well-known/masque/udp/203.0.113.5/443/",
            relay["masqueUrl"]?.jsonPrimitive?.content,
        )
        assertEquals(true, relay["masqueUseHttp2Fallback"]?.jsonPrimitive?.boolean)
        assertEquals(true, relay["masqueCloudflareGeohashEnabled"]?.jsonPrimitive?.boolean)
        assertEquals("127.0.0.5", relay["localSocksHost"]?.jsonPrimitive?.content)
        assertEquals(11985, relay["localSocksPort"]?.jsonPrimitive?.int)
        // The relay section always carries the nested finalmask object.
        assertNotNull("relay carries finalmask object", relay["finalmask"]?.jsonObject)

        val warp = payload["warp"]!!.jsonObject
        assertEquals(true, warp["enabled"]?.jsonPrimitive?.boolean)
        assertEquals("rules", warp["routeMode"]?.jsonPrimitive?.content)
        assertEquals("blocked.example", warp["routeHosts"]?.jsonPrimitive?.content)
        assertEquals("manual", warp["endpointSelectionMode"]?.jsonPrimitive?.content)

        val manualEndpoint = warp["manualEndpoint"]!!.jsonObject
        assertEquals("engage.cloudflareclient.com", manualEndpoint["host"]?.jsonPrimitive?.content)
        assertEquals("162.159.193.10", manualEndpoint["ipv4"]?.jsonPrimitive?.content)
        assertEquals(2409, manualEndpoint["port"]?.jsonPrimitive?.int)

        val amnezia = warp["amnezia"]!!.jsonObject
        assertEquals(true, amnezia["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(4, amnezia["jc"]?.jsonPrimitive?.int)
        assertEquals(8, amnezia["jmax"]?.jsonPrimitive?.int)
        assertEquals("custom", warp["amneziaPreset"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------
    // Area 5: encrypted DNS, carried through runtimeContext, serializes as a
    // nested encryptedDns object with the protocol lower-cased and bootstrap
    // IPs preserved as a JSON array.
    // ------------------------------------------------------------------

    @Test
    fun `encrypted dns context serializes as nested runtime context object`() {
        val preferences =
            RipDpiProxyUIPreferences(
                runtimeContext =
                    RipDpiRuntimeContext(
                        encryptedDns =
                            RipDpiEncryptedDnsContext(
                                resolverId = "cloudflare",
                                protocol = "DoH",
                                host = "cloudflare-dns.com",
                                port = 443,
                                tlsServerName = "cloudflare-dns.com",
                                bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                                dohUrl = "https://cloudflare-dns.com/dns-query",
                            ),
                    ),
            )

        val runtimeContext = encode(preferences)["runtimeContext"]
        assertNotNull("runtimeContext object must be present", runtimeContext?.jsonObject)

        val encryptedDns = runtimeContext!!.jsonObject["encryptedDns"]!!.jsonObject
        assertEquals("cloudflare", encryptedDns["resolverId"]?.jsonPrimitive?.content)
        // Protocol is normalized to lower-case on the wire.
        assertEquals("doh", encryptedDns["protocol"]?.jsonPrimitive?.content)
        assertEquals("cloudflare-dns.com", encryptedDns["host"]?.jsonPrimitive?.content)
        assertEquals(443, encryptedDns["port"]?.jsonPrimitive?.int)
        assertEquals("cloudflare-dns.com", encryptedDns["tlsServerName"]?.jsonPrimitive?.content)
        assertEquals(
            "https://cloudflare-dns.com/dns-query",
            encryptedDns["dohUrl"]?.jsonPrimitive?.content,
        )
        assertEquals(
            listOf("1.1.1.1", "1.0.0.1"),
            (encryptedDns["bootstrapIps"] as JsonArray).map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `encrypted dns context survives encode and decode round trip`() {
        val preferences =
            RipDpiProxyUIPreferences(
                runtimeContext =
                    RipDpiRuntimeContext(
                        encryptedDns =
                            RipDpiEncryptedDnsContext(
                                protocol = "dnscrypt",
                                host = "dnscrypt.example",
                                port = 8443,
                                dnscryptProviderName = "2.dnscrypt-cert.example",
                                dnscryptPublicKey = "ABCD1234",
                            ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
        val dns = decoded?.runtimeContext?.encryptedDns

        assertNotNull("decoded encrypted DNS must be present", dns)
        assertEquals("dnscrypt", dns?.protocol)
        assertEquals("dnscrypt.example", dns?.host)
        assertEquals(8443, dns?.port)
        assertEquals("2.dnscrypt-cert.example", dns?.dnscryptProviderName)
        assertEquals("ABCD1234", dns?.dnscryptPublicKey)
    }

    // ------------------------------------------------------------------
    // Area 7: protectPath inside runtimeContext is the deterministic part of
    // the runtime context and survives encoding.
    // ------------------------------------------------------------------

    @Test
    fun `runtime context protect path serializes under runtime context section`() {
        val preferences =
            RipDpiProxyUIPreferences(
                runtimeContext = RipDpiRuntimeContext(protectPath = "/data/local/tmp/ripdpi-protect.sock"),
            )

        val runtimeContext = encode(preferences)["runtimeContext"]!!.jsonObject
        assertEquals(
            "/data/local/tmp/ripdpi-protect.sock",
            runtimeContext["protectPath"]?.jsonPrimitive?.content,
        )
    }

    // ------------------------------------------------------------------
    // Area 6: root mode enabled vs disabled.
    // rootMode is always emitted (plain Boolean default); rootHelperSocketPath
    // is EncodeDefault.NEVER so it appears only when non-null.
    // ------------------------------------------------------------------

    @Test
    fun `root mode disabled emits rootMode false and omits helper socket path`() {
        val payload = encode(RipDpiProxyUIPreferences(), rootMode = false, rootHelperSocketPath = null)

        assertEquals(false, payload["rootMode"]?.jsonPrimitive?.boolean)
        assertFalse(
            "rootHelperSocketPath must be absent when null (EncodeDefault.NEVER)",
            payload.containsKey("rootHelperSocketPath"),
        )
    }

    @Test
    fun `root mode enabled emits rootMode true and includes helper socket path`() {
        val payload =
            encode(
                RipDpiProxyUIPreferences(),
                rootMode = true,
                rootHelperSocketPath = "/data/local/tmp/ripdpi-root-helper.sock",
            )

        assertEquals(true, payload["rootMode"]?.jsonPrimitive?.boolean)
        assertTrue(
            "rootHelperSocketPath must be present when supplied",
            payload.containsKey("rootHelperSocketPath"),
        )
        assertEquals(
            "/data/local/tmp/ripdpi-root-helper.sock",
            payload["rootHelperSocketPath"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `root mode survives encode and decode round trip`() {
        val rootEnabled =
            decodeRipDpiProxyUiPreferences(
                RipDpiProxyJsonCodec.encodeUiPreferences(
                    preferences = RipDpiProxyUIPreferences(),
                    rootMode = true,
                    rootHelperSocketPath = "/data/local/tmp/ripdpi-root-helper.sock",
                ),
            )
        assertEquals(true, rootEnabled?.rootMode)
        assertEquals("/data/local/tmp/ripdpi-root-helper.sock", rootEnabled?.rootHelperSocketPath)

        val rootDisabled = decodeRipDpiProxyUiPreferences(RipDpiProxyUIPreferences().toNativeConfigJson())
        assertEquals(false, rootDisabled?.rootMode)
        assertNull(rootDisabled?.rootHelperSocketPath)
    }

    // ------------------------------------------------------------------
    // Area 7: session overrides — shape/presence only, never exact token.
    // The auth token and ephemeral listen-port override are non-deterministic
    // inputs; assert the section shape, not the volatile values.
    // ------------------------------------------------------------------

    @Test
    fun `session overrides section appears with both keys when supplied`() {
        val payload =
            Json
                .parseToJsonElement(
                    RipDpiProxyJsonCodec.encodeUiPreferences(
                        preferences = RipDpiProxyUIPreferences(),
                        localListenPortOverride = 0,
                        localAuthToken = "fixture-placeholder-value",
                    ),
                ).jsonObject

        val sessionOverrides = payload["sessionOverrides"]
        assertNotNull("sessionOverrides section must be present", sessionOverrides?.jsonObject)
        // Shape-only: both keys exist; value identity is non-deterministic.
        assertTrue(sessionOverrides!!.jsonObject.containsKey("listenPortOverride"))
        assertTrue(sessionOverrides.jsonObject.containsKey("authToken"))
    }

    @Test
    fun `session overrides section stays null when no overrides supplied`() {
        val payload = encode(RipDpiProxyUIPreferences())
        assertNull(
            "sessionOverrides must be JSON null when no override supplied",
            payload["sessionOverrides"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // ------------------------------------------------------------------
    // stripRuntimeContext drops the runtime + log context but keeps the rest
    // of the payload intact — locks the strip behavior before refactor.
    // ------------------------------------------------------------------

    @Test
    fun `strip runtime context clears runtime and log context but keeps sections`() {
        val preferences =
            RipDpiProxyUIPreferences(
                listen = RipDpiListenConfig(port = 4040),
                runtimeContext = RipDpiRuntimeContext(protectPath = "/tmp/protect.sock"),
                logContext = RipDpiLogContext(runtimeId = "runtime-7", mode = "VPN"),
            )
        val encoded = preferences.toNativeConfigJson()

        val stripped = Json.parseToJsonElement(RipDpiProxyJsonCodec.stripRuntimeContext(encoded)).jsonObject

        assertNull(stripped["runtimeContext"]?.jsonPrimitive?.contentOrNull)
        assertNull(stripped["logContext"]?.jsonPrimitive?.contentOrNull)
        // The rest of the payload is untouched.
        assertEquals("ui", stripped["kind"]?.jsonPrimitive?.content)
        assertEquals(
            4040,
            stripped["listen"]
                ?.jsonObject
                ?.get("port")
                ?.jsonPrimitive
                ?.int,
        )
    }
}
