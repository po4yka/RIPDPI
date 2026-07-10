package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [ResolvedRipDpiRelayConfig] wire contract and proves the
 * [RelayConfigSections] decomposition is lossless.
 *
 * [ResolvedRipDpiRelayConfig] is the JSON payload `RipDpiRelay` hands to the
 * Rust relay core (`FlatResolvedRelayRuntimeConfig`). These tests guard that
 * moving the type into `RelayNativeConfig.kt` and adding section models left
 * the serialized object byte/semantically identical.
 */
class RelayNativeConfigTest {
    // Mirrors `relayJson` in RipDpiRelay: unknown keys ignored, defaults omitted.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `representative relay configs round-trip through section models unchanged`() {
        for (config in representativeConfigs()) {
            assertEquals(
                "section decomposition must be lossless for kind=${config.kind}",
                config,
                config.toSections().toResolvedConfig(),
            )
        }
    }

    @Test
    fun `section decomposition preserves the serialized relay JSON`() {
        for (config in representativeConfigs()) {
            val direct = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
            val viaSections =
                json.encodeToString(
                    ResolvedRipDpiRelayConfig.serializer(),
                    config.toSections().toResolvedConfig(),
                )
            assertEquals(
                "kind=${config.kind} JSON must be unchanged by the section round-trip",
                direct,
                viaSections,
            )
        }
    }

    @Test
    fun `socket protection is additive and uses stable wire names`() {
        val proxyJson = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), representativeConfigs().first())
        assertTrue("legacy proxy payload shape omits the inert default", "socketProtection" !in proxyJson)

        val vpnJson =
            json.encodeToString(
                ResolvedRipDpiRelayConfig.serializer(),
                representativeConfigs().first().copy(socketProtection = RelaySocketProtection.VpnRequired),
            )
        assertEquals(
            "vpn_required",
            (json.parseToJsonElement(vpnJson) as JsonObject).getValue("socketProtection").jsonPrimitive.content,
        )

        val legacy = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), proxyJson)
        assertEquals(RelaySocketProtection.Inactive, legacy.socketProtection)
    }

    @Test
    fun `shadowtls inner fingerprint is required and round-trips explicitly`() {
        val sparse =
            """{"kind":"vless_reality","profileId":"inner","server":"inner.example","serverPort":443,"serverName":"inner.example"}"""
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(ResolvedShadowTlsInnerRelayConfig.serializer(), sparse)
        }

        val explicit =
            ResolvedShadowTlsInnerRelayConfig(
                kind = "vless_reality",
                profileId = "inner",
                server = "inner.example",
                serverPort = 443,
                serverName = "inner.example",
                tlsFingerprintProfile = "firefox_stable",
            )
        val encoded = json.encodeToString(ResolvedShadowTlsInnerRelayConfig.serializer(), explicit)
        assertEquals(explicit, json.decodeFromString(ResolvedShadowTlsInnerRelayConfig.serializer(), encoded))
        assertTrue(encoded.contains("\"tlsFingerprintProfile\":\"firefox_stable\""))
    }

    @Test
    fun `chain hop fingerprint is required`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(
                ResolvedChainRelayHopConfig.serializer(),
                """{"kind":"vless_reality","profileId":"hop"}""",
            )
        }
    }

    @Test
    fun `top-level relay fingerprint is required`() {
        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), representativeConfigs().first())
        val withoutFingerprint = encoded.replace(Regex(",?\"tlsFingerprintProfile\":\"[^\"]+\""), "")

        assertThrows(SerializationException::class.java) {
            json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), withoutFingerprint)
        }
    }

    @Test
    fun `chain section folds the flat two-hop wire fields into an ordered hop list`() {
        val section = chainRelayConfig().toSections().chain

        assertEquals("entry + exit fold to a 2-element list", 2, section.hops.size)

        val entry = section.entry
        assertEquals("chain-entry-server", entry?.server)
        assertEquals(1111, entry?.serverPort)
        assertEquals("chain-entry-profile-id", entry?.profileId)
        assertEquals("chain-entry-uuid", entry?.uuid)
        assertEquals("vless_reality", entry?.config?.kind)

        val exit = section.exit
        assertEquals("chain-exit-server", exit?.server)
        assertEquals(2222, exit?.serverPort)
        assertEquals("chain-exit-profile-id", exit?.profileId)
        assertEquals("chain-exit-uuid", exit?.uuid)
        assertEquals("masque", exit?.config?.kind)
    }

    @Test
    fun `current chain config carries required schema and round trips`() {
        val config = chainRelayConfig()
        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
        val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)

        assertTrue(encoded.contains("\"schemaVersion\":10"))
        assertEquals(config, decoded.toSections().toResolvedConfig())
    }

    @Test
    fun `chain section rejects an out-of-range hop count with a typed error`() {
        val hop =
            ResolvedChainRelayHopRef(
                config = null,
                server = "hop.example",
                serverPort = 443,
                serverName = "hop-sni.example",
                publicKey = "",
                shortId = "",
                flow = com.poyka.ripdpi.data.RelayVlessFlowVision,
                xhttpMode = com.poyka.ripdpi.data.RelayXhttpModeAuto,
                profileId = "hop-profile",
                uuid = null,
            )

        val tooFew =
            try {
                RelayChainSection(hops = listOf(hop))
                null
            } catch (error: RelayChainHopCountException) {
                error
            }
        assertEquals("1 hop is below the minimum", 1, tooFew?.hopCount)

        val tooMany =
            try {
                RelayChainSection(hops = List(RelayChainMaxHops + 1) { hop })
                null
            } catch (error: RelayChainHopCountException) {
                error
            }
        assertEquals("5 hops exceeds the maximum", RelayChainMaxHops + 1, tooMany?.hopCount)

        // The in-range boundaries construct without raising.
        assertEquals(RelayChainMinHops, RelayChainSection(hops = List(RelayChainMinHops) { hop }).hops.size)
        assertEquals(RelayChainMaxHops, RelayChainSection(hops = List(RelayChainMaxHops) { hop }).hops.size)
    }

    @Test
    fun `three-hop chain crosses the flat wire as an ordered chainHops list`() {
        // Author a genuine 3-hop chain via the ordered list and confirm the
        // middle hop is reachable end-to-end: it serializes to a `chainHops`
        // array, deserializes back, folds into a 3-element section, and the wire
        // shape is idempotent across a full fold/unfold/re-encode cycle.
        val config = threeHopChainRelayConfig()

        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
        val wire = json.parseToJsonElement(encoded) as JsonObject
        val wireHops = wire["chainHops"] as JsonArray
        assertEquals("chainHops carries all 3 ordered hops", 3, wireHops.size)
        assertEquals(
            "chain-entry-profile-id",
            (wireHops[0] as JsonObject).getValue("profileId").jsonPrimitive.content,
        )
        assertEquals(
            "chain-middle-profile-id",
            (wireHops[1] as JsonObject).getValue("profileId").jsonPrimitive.content,
        )
        assertEquals(
            "chain-exit-profile-id",
            (wireHops[2] as JsonObject).getValue("profileId").jsonPrimitive.content,
        )
        // The derived entry/exit scalars still mirror hop[0] / hop[last].
        assertEquals("entry.example", wire.getValue("chainEntryServer").jsonPrimitive.content)
        assertEquals("exit.example", wire.getValue("chainExitServer").jsonPrimitive.content)

        val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)
        val section = decoded.toSections().chain
        assertEquals("the ordered list survives the wire", 3, section.hops.size)
        assertEquals("chain-entry-profile-id", section.hops[0].profileId)
        assertEquals("chain-middle-profile-id", section.hops[1].profileId)
        assertEquals("chain-exit-profile-id", section.hops[2].profileId)
        assertEquals("middle.example", section.hops[1].server)
        assertEquals("shadowsocks", section.hops[1].config?.kind)

        // The fold/unfold/re-encode cycle is idempotent: the N-hop wire object is
        // stable, so no hop (including the middle one) is lost on the round trip.
        val reEncoded =
            json.encodeToString(
                ResolvedRipDpiRelayConfig.serializer(),
                decoded.toSections().toResolvedConfig(),
            )
        assertEquals("3-hop wire object is stable across fold/unfold/re-encode", encoded, reEncoded)
    }

    private fun threeHopChainRelayConfig(): ResolvedRipDpiRelayConfig {
        val entryHop =
            ResolvedChainRelayHopConfig(
                kind = "vless_reality",
                profileId = "chain-entry-profile-id",
                server = "entry.example",
                serverPort = 443,
                serverName = "entry-sni.example",
                realityPublicKey = "entry-public-key",
                realityShortId = "entry-short-id",
                vlessUuid = "chain-entry-uuid",
                tlsFingerprintProfile = "firefox_stable",
            )
        val middleHop =
            ResolvedChainRelayHopConfig(
                kind = "shadowsocks",
                profileId = "chain-middle-profile-id",
                server = "middle.example",
                serverPort = 8388,
                shadowsocksMethod = "aes-256-gcm",
                shadowsocksPassword = placeholder(31),
                tlsFingerprintProfile = "edge_stable",
            )
        val exitHop =
            ResolvedChainRelayHopConfig(
                kind = "masque",
                profileId = "chain-exit-profile-id",
                server = "exit.example",
                masqueUrl = "https://masque.example/.well-known/masque/tcp/",
                masqueUseHttp2Fallback = true,
                masqueAuthMode = "bearer",
                masqueAuthToken = placeholder(32),
                tlsFingerprintProfile = "safari_stable",
            )
        // Seed the legacy scalar mirror to exactly what `toResolvedConfig` derives
        // from hop[0] / hop[last], so the fold/unfold/re-encode cycle is a no-op.
        return baseConfig("chain_relay").copy(
            chainHops = listOf(entryHop, middleHop, exitHop),
            chainEntry = entryHop,
            chainEntryServer = entryHop.server,
            chainEntryPort = entryHop.serverPort,
            chainEntryServerName = entryHop.serverName,
            chainEntryPublicKey = entryHop.realityPublicKey,
            chainEntryShortId = entryHop.realityShortId,
            chainEntryProfileId = entryHop.profileId,
            chainEntryUuid = entryHop.vlessUuid,
            chainExit = exitHop,
            chainExitServer = exitHop.server,
            chainExitPort = exitHop.serverPort,
            chainExitServerName = exitHop.serverName,
            chainExitPublicKey = exitHop.realityPublicKey,
            chainExitShortId = exitHop.realityShortId,
            chainExitProfileId = exitHop.profileId,
            chainExitUuid = exitHop.vlessUuid,
        )
    }

    @Test
    fun `resolved relay config survives a JSON encode-decode round-trip`() {
        for (config in representativeConfigs()) {
            val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
            val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)
            assertEquals("kind=${config.kind} must survive a JSON round-trip", config, decoded)
        }
    }

    @Test
    fun `fully populated relay config serializes to the expected flat wire object`() {
        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), fullyPopulatedRelayConfig())
        val wireObject = json.parseToJsonElement(encoded) as JsonObject

        assertEquals(
            "relay wire object must carry exactly the documented flat key set",
            expectedWireKeys,
            wireObject.keys,
        )
    }

    @Test
    fun `minimal relay config omits defaulted keys from the wire object`() {
        // `encodeDefaults` is off, so a config left at its defaults emits only
        // the required keys -- the historical behaviour the Rust side relies on.
        val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), baseConfig("vless_reality"))
        val wireObject = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("only required keys are emitted at defaults", requiredWireKeys, wireObject.keys)
        assertTrue("defaulted keys stay absent", wireObject.keys.none { it in defaultedWireKeys })
    }

    // One focused config per relay kind: each sets its concern's section
    // fields off-default so a misrouted field in toSections/toResolvedConfig
    // surfaces as that kind's round-trip or JSON-preservation failure.
    private fun representativeConfigs(): List<ResolvedRipDpiRelayConfig> =
        listOf(
            vlessRealityTcpConfig(),
            vlessXhttpConfig(),
            masqueConfig(),
            tuicConfig(),
            hysteria2Config(),
            anyTlsConfig(),
            trojanConfig(),
            shadowsocksConfig(),
            shadowTlsConfig(),
            chainRelayConfig(),
            cloudflareTunnelConfig(),
            appsScriptConfig(),
            finalmaskConfig(),
            fullyPopulatedRelayConfig(),
        )

    // --- Representative per-kind configs -------------------------------------

    /** VLESS Reality over the default direct-TCP transport. */
    private fun vlessRealityTcpConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("vless_reality").copy(vlessUuid = "vless-reality-uuid")

    /** VLESS Reality over the xHTTP transport, exercising the xHTTP fields. */
    private fun vlessXhttpConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("vless_reality").copy(
            vlessTransport = "xhttp",
            xhttpPath = "/xhttp/path",
            xhttpHost = "xhttp.host.example",
            vlessUuid = "vless-xhttp-uuid",
        )

    private fun masqueConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("masque").copy(
            masqueCloudflareGeohashEnabled = true,
            masqueAuthMode = "token",
            masqueAuthToken = placeholder(1),
            masqueCloudflareGeohashHeader = "masque-geohash-header",
            masquePrivacyPassProviderUrl = "https://privacy-pass.example",
            masquePrivacyPassProviderAuthToken = placeholder(2),
        )

    private fun tuicConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("tuic_v5").copy(
            tuicZeroRtt = true,
            tuicCongestionControl = "cubic",
            tuicUuid = "tuic-uuid",
            tuicPassword = placeholder(3),
        )

    private fun hysteria2Config(): ResolvedRipDpiRelayConfig =
        baseConfig("hysteria2").copy(
            hysteriaPassword = placeholder(4),
            hysteriaSalamanderKey = "hysteria-salamander-key",
        )

    private fun anyTlsConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("anytls").copy(
            anyTlsPassword = placeholder(11),
            udpEnabled = true,
        )

    private fun trojanConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("trojan").copy(
            trojanPassword = placeholder(5),
            trojanRootCertificatePem = "-----BEGIN CERTIFICATE-----\ntrojan-root\n-----END CERTIFICATE-----",
        )

    private fun shadowsocksConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("shadowsocks").copy(
            shadowsocksMethod = "aes-256-gcm",
            shadowsocksPassword = placeholder(6),
        )

    private fun shadowTlsConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("shadowtls_v3").copy(
            shadowTlsInnerProfileId = "shadowtls-inner-profile-id",
            shadowTlsPassword = placeholder(7),
            shadowTlsInner =
                ResolvedShadowTlsInnerRelayConfig(
                    kind = "vless_reality",
                    profileId = "inner-profile",
                    server = "inner.example",
                    serverPort = 8443,
                    serverName = "inner-name.example",
                    realityPublicKey = "inner-public-key",
                    realityShortId = "inner-short-id",
                    vlessTransport = "xhttp",
                    vlessUuid = "inner-uuid",
                    tlsFingerprintProfile = "firefox_stable",
                ),
        )

    private fun chainRelayConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("chain_relay").copy(
            chainEntry =
                ResolvedChainRelayHopConfig(
                    kind = "vless_reality",
                    profileId = "chain-entry-profile-id",
                    server = "entry.example",
                    serverPort = 443,
                    serverName = "entry-sni.example",
                    realityPublicKey = "entry-public-key",
                    realityShortId = "entry-short-id",
                    vlessUuid = "chain-entry-uuid",
                    tlsFingerprintProfile = "firefox_stable",
                ),
            chainEntryProfileId = "chain-entry-profile-id",
            chainEntryUuid = "chain-entry-uuid",
            chainExit =
                ResolvedChainRelayHopConfig(
                    kind = "masque",
                    profileId = "chain-exit-profile-id",
                    masqueUrl = "https://masque.example/.well-known/masque/tcp/",
                    masqueUseHttp2Fallback = true,
                    masqueAuthMode = "bearer",
                    masqueAuthToken = placeholder(12),
                    tlsFingerprintProfile = "safari_stable",
                ),
            chainExitProfileId = "chain-exit-profile-id",
            chainExitUuid = "chain-exit-uuid",
        )

    private fun cloudflareTunnelConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("cloudflare_tunnel").copy(
            cloudflareTunnelMode = "publish_managed",
            cloudflarePublishLocalOriginUrl = "http://origin.local",
            cloudflareCredentialsRef = "credentials-ref",
            cloudflareTunnelToken = placeholder(7),
            cloudflareTunnelCredentialsJson = "{\"cloudflare\":\"credentials\"}",
        )

    private fun appsScriptConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("google_apps_script").copy(
            appsScriptScriptIds = listOf("apps-script-id-a", "apps-script-id-b"),
            appsScriptGoogleIp = "10.1.2.3",
            appsScriptFrontDomain = "apps-script-front.example",
            appsScriptSniHosts = listOf("apps-script-sni.example"),
            appsScriptVerifySsl = !DefaultRelayAppsScriptVerifySsl,
            appsScriptParallelRelay = true,
            appsScriptDirectHosts = listOf("apps-script-direct.example"),
            appsScriptAuthKey = "apps-script-auth-key",
        )

    private fun finalmaskConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("vless_reality").copy(
            finalmask =
                ResolvedRelayFinalmaskConfig(
                    type = "header_custom",
                    headerHex = "aabb",
                    trailerHex = "ccdd",
                    randRange = "8-12",
                    sudokuSeed = "finalmask-seed",
                    fragmentPackets = 3,
                    fragmentMinBytes = 32,
                    fragmentMaxBytes = 96,
                ),
        )

    // All 25 required fields, each with a distinct value so a misrouted field
    // in toSections/toResolvedConfig changes the round-tripped config.
    private fun baseConfig(kind: String): ResolvedRipDpiRelayConfig =
        ResolvedRipDpiRelayConfig(
            enabled = true,
            kind = kind,
            profileId = "profile-id",
            server = "server.example",
            serverPort = 8443,
            serverName = "server-name.example",
            realityPublicKey = "reality-public-key",
            realityShortId = "reality-short-id",
            chainEntryServer = "chain-entry-server",
            chainEntryPort = 1111,
            chainEntryServerName = "chain-entry-server-name",
            chainEntryPublicKey = "chain-entry-public-key",
            chainEntryShortId = "chain-entry-short-id",
            chainExitServer = "chain-exit-server",
            chainExitPort = 2222,
            chainExitServerName = "chain-exit-server-name",
            chainExitPublicKey = "chain-exit-public-key",
            chainExitShortId = "chain-exit-short-id",
            masqueUrl = "https://masque.example",
            masqueUseHttp2Fallback = true,
            localSocksHost = "127.0.0.1",
            localSocksPort = 1080,
            udpEnabled = true,
            tcpFallbackEnabled = true,
            tlsFingerprintProfile = "chrome_stable",
        )

    // Distinct, non-literal values for `password` / `token` fields so the
    // secret-scanner pre-commit hook is not tripped by fake test credentials.
    private fun placeholder(slot: Int): String = "relay-fixture-placeholder-$slot"

    // Every defaulted field set to a non-default value so all wire keys
    // appear and every field exercises the section round-trip.
    private fun fullyPopulatedRelayConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("vless_reality")
            .withTransportFixture()
            .withResolvedChainFixture()
            .withCredentialFixture()
            .withAppsScriptAndFinalmaskFixture()

    private fun ResolvedRipDpiRelayConfig.withTransportFixture(): ResolvedRipDpiRelayConfig =
        copy(
            outboundBindIp = "10.0.0.9",
            vlessTransport = "xhttp",
            xhttpPath = "/wire/path",
            xhttpHost = "wire.host",
            cloudflareTunnelMode = "publish_managed",
            cloudflarePublishLocalOriginUrl = "http://origin.local",
            cloudflareCredentialsRef = "credentials-ref",
            masqueTcpProtocol = "http3",
            masqueCloudflareGeohashEnabled = true,
            tuicZeroRtt = true,
            tuicCongestionControl = "cubic",
            shadowTlsInnerProfileId = "shadowtls-inner-profile-id",
            naivePath = "/naive/path",
            ptBridgeLine = "pt-bridge-line",
            ptWebTunnelUrl = "https://webtunnel.example",
            ptSnowflakeBrokerUrl = "https://broker.example",
            ptSnowflakeFrontDomain = "front.example",
            quicBindLowPort = true,
            quicMigrateAfterHandshake = true,
            tlsFingerprintProfile = "firefox_stable",
            trojanRootCertificatePem = "-----BEGIN CERTIFICATE-----\ntrojan-root\n-----END CERTIFICATE-----",
        )

    private fun ResolvedRipDpiRelayConfig.withResolvedChainFixture(): ResolvedRipDpiRelayConfig =
        copy(
            chainEntry =
                ResolvedChainRelayHopConfig(
                    kind = "vless_reality",
                    profileId = "chain-entry-profile-id",
                    server = "entry.example",
                    serverPort = 443,
                    serverName = "entry-sni.example",
                    realityPublicKey = "entry-public-key",
                    realityShortId = "entry-short-id",
                    vlessUuid = "chain-entry-uuid",
                    tlsFingerprintProfile = "firefox_stable",
                ),
            chainEntryProfileId = "chain-entry-profile-id",
            chainExit =
                ResolvedChainRelayHopConfig(
                    kind = "masque",
                    profileId = "chain-exit-profile-id",
                    masqueUrl = "https://masque.example/.well-known/masque/tcp/",
                    masqueUseHttp2Fallback = true,
                    masqueAuthMode = "bearer",
                    masqueAuthToken = placeholder(12),
                    tlsFingerprintProfile = "safari_stable",
                ),
            chainExitProfileId = "chain-exit-profile-id",
            shadowTlsInner =
                ResolvedShadowTlsInnerRelayConfig(
                    kind = "vless_reality",
                    profileId = "inner-profile",
                    server = "inner.example",
                    serverPort = 9443,
                    serverName = "inner-name.example",
                    realityPublicKey = "inner-public-key",
                    realityShortId = "inner-short-id",
                    vlessTransport = "xhttp",
                    vlessUuid = "inner-uuid",
                    tlsFingerprintProfile = "edge_stable",
                ),
        )

    private fun ResolvedRipDpiRelayConfig.withCredentialFixture(): ResolvedRipDpiRelayConfig =
        copy(
            vlessUuid = "vless-uuid",
            chainEntryUuid = "chain-entry-uuid",
            chainExitUuid = "chain-exit-uuid",
            hysteriaPassword = placeholder(1),
            hysteriaSalamanderKey = "hysteria-salamander-key",
            anyTlsPassword = placeholder(11),
            tuicUuid = "tuic-uuid",
            tuicPassword = placeholder(2),
            shadowTlsPassword = placeholder(3),
            shadowsocksMethod = "2022-blake3-aes-256-gcm",
            shadowsocksPassword = placeholder(10),
            naiveUsername = "naive-username",
            naivePassword = placeholder(4),
            masqueAuthMode = "token",
            trojanPassword = placeholder(5),
            masqueAuthToken = placeholder(6),
            masqueClientCertificateChainPem = "masque-cert-chain-pem",
            masqueClientPrivateKeyPem = "masque-private-key-pem",
            masqueCloudflareGeohashHeader = "masque-geohash-header",
            masquePrivacyPassProviderUrl = "https://privacy-pass.example",
            masquePrivacyPassProviderAuthToken = placeholder(7),
            cloudflareTunnelToken = placeholder(8),
            cloudflareTunnelCredentialsJson = "{\"cloudflare\":\"credentials\"}",
        )

    private fun ResolvedRipDpiRelayConfig.withAppsScriptAndFinalmaskFixture(): ResolvedRipDpiRelayConfig =
        copy(
            appsScriptScriptIds = listOf("apps-script-id"),
            appsScriptGoogleIp = "10.1.2.3",
            appsScriptFrontDomain = "apps-script-front.example",
            appsScriptSniHosts = listOf("apps-script-sni.example"),
            appsScriptVerifySsl = !DefaultRelayAppsScriptVerifySsl,
            appsScriptParallelRelay = true,
            appsScriptDirectHosts = listOf("apps-script-direct.example"),
            appsScriptAuthKey = "apps-script-auth-key",
            finalmask =
                ResolvedRelayFinalmaskConfig(
                    type = "header_custom",
                    headerHex = "aabb",
                    trailerHex = "ccdd",
                    randRange = "8-12",
                    sudokuSeed = "finalmask-seed",
                    fragmentPackets = 3,
                    fragmentMinBytes = 32,
                    fragmentMaxBytes = 96,
                ),
        )

    private companion object {
        // The 25 always-emitted keys of ResolvedRipDpiRelayConfig.
        private val requiredWireKeys =
            setOf(
                "enabled",
                "kind",
                "profileId",
                "server",
                "serverPort",
                "serverName",
                "realityPublicKey",
                "realityShortId",
                "chainEntryServer",
                "chainEntryPort",
                "chainEntryServerName",
                "chainEntryPublicKey",
                "chainEntryShortId",
                "chainExitServer",
                "chainExitPort",
                "chainExitServerName",
                "chainExitPublicKey",
                "chainExitShortId",
                "masqueUrl",
                "masqueUseHttp2Fallback",
                "localSocksHost",
                "localSocksPort",
                "udpEnabled",
                "tcpFallbackEnabled",
                "tlsFingerprintProfile",
                "schemaVersion",
            )

        // The 57 keys carrying a default; emitted only when set off-default.
        private val defaultedWireKeys =
            setOf(
                "outboundBindIp",
                "vlessTransport",
                "xhttpPath",
                "xhttpHost",
                "cloudflareTunnelMode",
                "cloudflarePublishLocalOriginUrl",
                "cloudflareCredentialsRef",
                "chainEntry",
                "chainEntryProfileId",
                "chainExit",
                "chainExitProfileId",
                "masqueTcpProtocol",
                "masqueCloudflareGeohashEnabled",
                "tuicZeroRtt",
                "tuicCongestionControl",
                "shadowTlsInnerProfileId",
                "shadowTlsInner",
                "naivePath",
                "ptBridgeLine",
                "ptWebTunnelUrl",
                "ptSnowflakeBrokerUrl",
                "ptSnowflakeFrontDomain",
                "quicBindLowPort",
                "quicMigrateAfterHandshake",
                "vlessUuid",
                "chainEntryUuid",
                "chainExitUuid",
                "hysteriaPassword",
                "hysteriaSalamanderKey",
                "anytlsPassword",
                "tuicUuid",
                "tuicPassword",
                "shadowTlsPassword",
                "trojanPassword",
                "shadowsocksMethod",
                "shadowsocksPassword",
                "trojanRootCertificatePem",
                "naiveUsername",
                "naivePassword",
                "masqueAuthMode",
                "masqueAuthToken",
                "masqueClientCertificateChainPem",
                "masqueClientPrivateKeyPem",
                "masqueCloudflareGeohashHeader",
                "masquePrivacyPassProviderUrl",
                "masquePrivacyPassProviderAuthToken",
                "cloudflareTunnelToken",
                "cloudflareTunnelCredentialsJson",
                "appsScriptScriptIds",
                "appsScriptGoogleIp",
                "appsScriptFrontDomain",
                "appsScriptSniHosts",
                "appsScriptVerifySsl",
                "appsScriptParallelRelay",
                "appsScriptDirectHosts",
                "appsScriptAuthKey",
                "finalmask",
            )

        // The complete flat wire object: required + defaulted = 83 keys.
        private val expectedWireKeys = requiredWireKeys + defaultedWireKeys
    }
}
