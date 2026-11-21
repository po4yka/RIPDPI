package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.RelaySectionCodec
import com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl
import com.poyka.ripdpi.data.MieruHandshakeDefault
import com.poyka.ripdpi.data.MieruHandshakeNoWait
import com.poyka.ripdpi.data.MieruHandshakeStandard
import com.poyka.ripdpi.data.MieruPaddingPattern
import com.poyka.ripdpi.data.MieruTcpFragment
import com.poyka.ripdpi.data.MieruTrafficPattern
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `Apps Script private directory uses the existing native dataDir contract`() {
        val config = representativeConfigs().first().copy(appsScriptDataDir = "/private/files/apps-script-relay")
        assertEquals(config, config.toSections().toResolvedConfig())
        val encoded =
            json.parseToJsonElement(
                json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config),
            ) as JsonObject
        assertEquals("/private/files/apps-script-relay", encoded.getValue("dataDir").jsonPrimitive.content)
        assertFalse("appsScriptDataDir" in encoded)
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
            """
            {"kind":"vless_reality","profileId":"inner","server":"inner.example","serverPort":443,
            "serverName":"inner.example"}
            """.trimIndent()
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
        // Rust `ResolvedShadowTlsInnerRelayConfig` has no serde default for these keys.
        assertTrue(encoded.contains("\"realityPublicKey\":\"\""))
        assertTrue(encoded.contains("\"realityShortId\":\"\""))
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
    fun `resolved relay native fields survive a JSON encode-decode round-trip`() {
        for (config in representativeConfigs()) {
            val encoded = json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
            val decoded = json.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), encoded)
            val nativeFields =
                config.copy(
                    ptBridgeLine = "",
                    ptWebTunnelUrl = "",
                    ptSnowflakeBrokerUrl = "",
                    ptSnowflakeFrontDomain = "",
                )
            assertEquals("kind=${config.kind} native fields must survive a JSON round-trip", nativeFields, decoded)
        }
    }

    @Test
    fun `managed transport launch inputs stay in memory and never enter native JSON`() {
        val config = fullyPopulatedRelayConfig()
        assertEquals(config, config.toSections().toResolvedConfig())
        val encodeDefaults = Json { encodeDefaults = true }
        for (encoder in listOf(json, encodeDefaults)) {
            val encoded = encoder.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
            val keys = (encoder.parseToJsonElement(encoded) as JsonObject).keys
            for (field in listOf("ptBridgeLine", "ptWebTunnelUrl", "ptSnowflakeBrokerUrl", "ptSnowflakeFrontDomain")) {
                assertTrue("Android launch input $field is rejected by the native schema", field !in keys)
            }
        }
    }

    @Test
    fun `relay listener password never appears in any relay config string form`() {
        val resolved = fullyPopulatedRelayConfig()
        val password = checkNotNull(resolved.localSocksPassword)
        val proxyRelay =
            RipDpiRelayConfig(
                enabled = true,
                kind = "hysteria2",
                localSocksCredentials = LocalSocksCredentials("session-user", password),
            )
        val native =
            com.poyka.ripdpi.core.codec.RelaySectionCodec
                .toNative(proxyRelay)

        assertEquals(password, native.localSocksPassword)
        assertEquals(
            proxyRelay,
            com.poyka.ripdpi.core.codec.RelaySectionCodec
                .toModel(native),
        )
        for (rendered in listOf(resolved, resolved.toSections().common, proxyRelay, native).map(Any::toString)) {
            assertFalse("string form leaked the listener password: $rendered", password in rendered)
        }
    }

    @Test
    fun `UI relay model conversion retains raw Mieru optional presence`() {
        val patterns =
            listOf(
                null,
                MieruTrafficPattern(),
                MieruTrafficPattern(
                    seed = 0,
                    unlockAll = false,
                    tcpFragment = MieruTcpFragment(enable = false, maxSleepMs = 0),
                    padding = MieruPaddingPattern(maxEndPaddingLen = 0),
                ),
            )
        for (kind in listOf("mieru", "hysteria2")) {
            for (mode in listOf(null, MieruHandshakeDefault, MieruHandshakeStandard, MieruHandshakeNoWait)) {
                for (pattern in patterns) {
                    val original =
                        RipDpiRelayConfig(kind = kind, mieruHandshakeMode = mode, mieruTrafficPattern = pattern)
                    val native = RelaySectionCodec.toNative(original)
                    assertEquals(mode, native.mieruHandshakeMode)
                    assertEquals(pattern, native.mieruTrafficPattern)
                    assertEquals(original, RelaySectionCodec.toModel(native))
                }
            }
        }
    }

    @Test
    fun `UI relay model conversion still rejects invalid Mieru advanced settings`() {
        val invalid =
            listOf(
                RipDpiRelayConfig(mieruHandshakeMode = "unsupported"),
                RipDpiRelayConfig(
                    mieruTrafficPattern = MieruTrafficPattern(tcpFragment = MieruTcpFragment(maxSleepMs = 101)),
                ),
            )
        invalid.forEach { config ->
            assertThrows(IllegalArgumentException::class.java) { RelaySectionCodec.toNative(config) }
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
        val encoded =
            json.encodeToString(
                ResolvedRipDpiRelayConfig.serializer(),
                relayNativeBaseFixture("vless_reality"),
            )
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
            snellConfig(),
            shadowTlsConfig(),
            relayNativeChainFixture(),
            cloudflareTunnelConfig(),
            appsScriptConfig(),
            finalmaskConfig(),
            fullyPopulatedRelayConfig(),
        )

    // --- Representative per-kind configs -------------------------------------

    /** VLESS Reality over the default direct-TCP transport. */
    private fun vlessRealityTcpConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("vless_reality").copy(vlessUuid = "vless-reality-uuid")

    /** VLESS Reality over the xHTTP transport, exercising the xHTTP fields. */
    private fun vlessXhttpConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("vless_reality").copy(
            vlessTransport = "xhttp",
            xhttpPath = "/xhttp/path",
            xhttpHost = "xhttp.host.example",
            vlessUuid = "vless-xhttp-uuid",
        )

    private fun masqueConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("masque").copy(
            masqueCloudflareGeohashEnabled = true,
            masqueAuthMode = "token",
            masqueAuthToken = relayNativePlaceholder(1),
            masqueCloudflareGeohashHeader = "masque-geohash-header",
            masquePrivacyPassProviderUrl = "https://privacy-pass.example",
            masquePrivacyPassProviderAuthToken = relayNativePlaceholder(2),
        )

    private fun tuicConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("tuic_v5").copy(
            tuicZeroRtt = true,
            tuicCongestionControl = "cubic",
            tuicRootCertificatePem = "-----BEGIN CERTIFICATE-----\ntuic-root\n-----END CERTIFICATE-----",
            masqueRootCertificatePem = "-----BEGIN CERTIFICATE-----\nmasque-root\n-----END CERTIFICATE-----",
            tuicUuid = "tuic-uuid",
            tuicPassword = relayNativePlaceholder(3),
        )

    private fun hysteria2Config(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("hysteria2").copy(
            hysteriaPassword = relayNativePlaceholder(4),
            hysteriaSalamanderKey = "hysteria-salamander-key",
            hysteriaInsecure = true,
            hysteriaPinSha256 = "ab".repeat(32),
            hysteriaPortHopPorts = "5000-5010,8443",
            hysteriaPortHopIntervalMillis = 5000,
            hysteriaPortHopIntervalMaxMillis = 15000,
        )

    private fun anyTlsConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("anytls").copy(
            anyTlsPassword = relayNativePlaceholder(11),
            udpEnabled = true,
        )

    private fun trojanConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("trojan").copy(
            trojanPassword = relayNativePlaceholder(5),
            trojanRootCertificatePem = "-----BEGIN CERTIFICATE-----\ntrojan-root\n-----END CERTIFICATE-----",
        )

    private fun snellConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("snell").copy(
            snellPsk = "snell-fixture-psk",
            snellUserKey = "snell-fixture-user",
        )

    @Test
    fun `snell relay config serializes exactly its two credential keys on top of the required ones`() {
        val wire =
            json.parseToJsonElement(
                json.encodeToString(ResolvedRipDpiRelayConfig.serializer(), snellConfig()),
            ) as JsonObject
        assertEquals(requiredWireKeys + setOf("snellPsk", "snellUserKey"), wire.keys)
        assertEquals("snell", (wire["kind"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(10, (wire["schemaVersion"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals("snell-fixture-psk", (wire["snellPsk"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("snell-fixture-user", (wire["snellUserKey"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    private fun shadowsocksConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("shadowsocks").copy(
            shadowsocksMethod = "aes-256-gcm",
            shadowsocksPassword = relayNativePlaceholder(6),
        )

    private fun shadowTlsConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("shadowtls_v3").copy(
            shadowTlsInnerProfileId = "shadowtls-inner-profile-id",
            shadowTlsPassword = relayNativePlaceholder(7),
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

    private fun cloudflareTunnelConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("cloudflare_tunnel").copy(
            cloudflareTunnelMode = "publish_managed",
            cloudflarePublishLocalOriginUrl = "http://origin.local",
            cloudflareCredentialsRef = "credentials-ref",
            cloudflareTunnelToken = relayNativePlaceholder(7),
            cloudflareTunnelCredentialsJson = "{\"cloudflare\":\"credentials\"}",
            localSocksUsername = "session-user",
            localSocksPassword = relayNativePlaceholder(12),
        )

    private fun appsScriptConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("google_apps_script").copy(
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
        relayNativeBaseFixture("vless_reality").copy(
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

    // Every defaulted field set to a non-default value so all wire keys
    // appear and every field exercises the section round-trip.
    private fun fullyPopulatedRelayConfig(): ResolvedRipDpiRelayConfig =
        relayNativeBaseFixture("vless_reality")
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
            vlessMuxProtocol = "yamux",
            vlessMuxMaxConcurrentStreams = 3,
            vlessMuxPerConnectionKbps = 1,
            vlessMuxPaddingMax = 1,
            cloudflareTunnelMode = "publish_managed",
            cloudflarePublishLocalOriginUrl = "http://origin.local",
            cloudflareCredentialsRef = "credentials-ref",
            masqueTcpProtocol = "http3",
            masqueCloudflareGeohashEnabled = true,
            tuicZeroRtt = true,
            tuicCongestionControl = "cubic",
            tuicRootCertificatePem = "-----BEGIN CERTIFICATE-----\ntuic-root\n-----END CERTIFICATE-----",
            masqueRootCertificatePem = "-----BEGIN CERTIFICATE-----\nmasque-root\n-----END CERTIFICATE-----",
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
                    masqueAuthToken = relayNativePlaceholder(12),
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
            hysteriaPassword = relayNativePlaceholder(1),
            hysteriaSalamanderKey = "hysteria-salamander-key",
            hysteriaInsecure = true,
            hysteriaPinSha256 = "ab".repeat(32),
            hysteriaPortHopPorts = "5000-5010,8443",
            hysteriaPortHopIntervalMillis = 5000,
            hysteriaPortHopIntervalMaxMillis = 15000,
            anyTlsPassword = relayNativePlaceholder(11),
            anyTlsPinSha256 = "cd".repeat(32),
            tuicUuid = "tuic-uuid",
            tuicPassword = relayNativePlaceholder(2),
            shadowTlsPassword = relayNativePlaceholder(3),
            shadowsocksMethod = "2022-blake3-aes-256-gcm",
            shadowsocksPassword = relayNativePlaceholder(10),
            snellPsk = "snell-fixture-psk",
            snellUserKey = "snell-fixture-user",
            naiveUsername = "naive-username",
            naivePassword = relayNativePlaceholder(4),
            masqueAuthMode = "token",
            trojanPassword = relayNativePlaceholder(5),
            masqueAuthToken = relayNativePlaceholder(6),
            masqueClientCertificateChainPem = "masque-cert-chain-pem",
            masqueClientPrivateKeyPem = "masque-private-key-pem",
            masqueCloudflareGeohashHeader = "masque-geohash-header",
            masquePrivacyPassProviderUrl = "https://privacy-pass.example",
            masquePrivacyPassProviderAuthToken = relayNativePlaceholder(7),
            cloudflareTunnelToken = relayNativePlaceholder(8),
            cloudflareTunnelCredentialsJson = "{\"cloudflare\":\"credentials\"}",
            localSocksUsername = "session-user",
            localSocksPassword = relayNativePlaceholder(12),
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
        // The always-emitted keys of ResolvedRipDpiRelayConfig.
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

        // Keys carrying a default; emitted only when set off-default.
        private val defaultedWireKeys =
            setOf(
                "outboundBindIp",
                "localSocksUsername",
                "localSocksPassword",
                "vlessTransport",
                "xhttpPath",
                "xhttpHost",
                "vlessMuxProtocol",
                "vlessMuxMaxConcurrentStreams",
                "vlessMuxPerConnectionKbps",
                "vlessMuxPaddingMax",
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
                "quicBindLowPort",
                "quicMigrateAfterHandshake",
                "vlessUuid",
                "chainEntryUuid",
                "chainExitUuid",
                "hysteriaPassword",
                "hysteriaSalamanderKey",
                "hysteriaInsecure",
                "hysteriaPinSha256",
                "hysteriaPortHopPorts",
                "hysteriaPortHopIntervalMillis",
                "hysteriaPortHopIntervalMaxMillis",
                "anytlsPassword",
                "anytlsCertificatePinSha256",
                "tuicUuid",
                "tuicPassword",
                "shadowTlsPassword",
                "trojanPassword",
                "shadowsocksMethod",
                "shadowsocksPassword",
                "snellPsk",
                "snellUserKey",
                "trojanRootCertificatePem",
                "tuicRootCertificatePem",
                "masqueRootCertificatePem",
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

        // The complete flat wire object contains exactly required + defaulted keys.
        private val expectedWireKeys = requiredWireKeys + defaultedWireKeys
    }
}
