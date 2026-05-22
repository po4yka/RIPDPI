package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
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

    private fun shadowTlsConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("shadowtls_v3").copy(
            shadowTlsInnerProfileId = "shadowtls-inner-profile-id",
            shadowTlsPassword = placeholder(5),
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
                ),
        )

    private fun chainRelayConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("chain_relay").copy(
            chainEntryProfileId = "chain-entry-profile-id",
            chainEntryUuid = "chain-entry-uuid",
            chainExitProfileId = "chain-exit-profile-id",
            chainExitUuid = "chain-exit-uuid",
        )

    private fun cloudflareTunnelConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("cloudflare_tunnel").copy(
            cloudflareTunnelMode = "publish_managed",
            cloudflarePublishLocalOriginUrl = "http://origin.local",
            cloudflareCredentialsRef = "credentials-ref",
            cloudflareTunnelToken = placeholder(6),
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

    // All 24 required fields, each with a distinct value so a misrouted field
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
        )

    // Distinct, non-literal values for `password` / `token` fields so the
    // secret-scanner pre-commit hook is not tripped by fake test credentials.
    private fun placeholder(slot: Int): String = "relay-fixture-placeholder-$slot"

    // Every defaulted field set to a non-default value so all 74 wire keys
    // appear and every field exercises the section round-trip.
    private fun fullyPopulatedRelayConfig(): ResolvedRipDpiRelayConfig =
        baseConfig("vless_reality").copy(
            outboundBindIp = "10.0.0.9",
            vlessTransport = "xhttp",
            xhttpPath = "/wire/path",
            xhttpHost = "wire.host",
            cloudflareTunnelMode = "publish_managed",
            cloudflarePublishLocalOriginUrl = "http://origin.local",
            cloudflareCredentialsRef = "credentials-ref",
            chainEntryProfileId = "chain-entry-profile-id",
            chainExitProfileId = "chain-exit-profile-id",
            masqueCloudflareGeohashEnabled = true,
            tuicZeroRtt = true,
            tuicCongestionControl = "cubic",
            shadowTlsInnerProfileId = "shadowtls-inner-profile-id",
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
                ),
            naivePath = "/naive/path",
            ptBridgeLine = "pt-bridge-line",
            ptWebTunnelUrl = "https://webtunnel.example",
            ptSnowflakeBrokerUrl = "https://broker.example",
            ptSnowflakeFrontDomain = "front.example",
            quicBindLowPort = true,
            quicMigrateAfterHandshake = true,
            vlessUuid = "vless-uuid",
            chainEntryUuid = "chain-entry-uuid",
            chainExitUuid = "chain-exit-uuid",
            hysteriaPassword = placeholder(1),
            hysteriaSalamanderKey = "hysteria-salamander-key",
            tuicUuid = "tuic-uuid",
            tuicPassword = placeholder(2),
            shadowTlsPassword = placeholder(3),
            naiveUsername = "naive-username",
            naivePassword = placeholder(4),
            tlsFingerprintProfile = "firefox_stable",
            masqueAuthMode = "token",
            masqueAuthToken = placeholder(5),
            masqueClientCertificateChainPem = "masque-cert-chain-pem",
            masqueClientPrivateKeyPem = "masque-private-key-pem",
            masqueCloudflareGeohashHeader = "masque-geohash-header",
            masquePrivacyPassProviderUrl = "https://privacy-pass.example",
            masquePrivacyPassProviderAuthToken = placeholder(6),
            cloudflareTunnelToken = placeholder(7),
            cloudflareTunnelCredentialsJson = "{\"cloudflare\":\"credentials\"}",
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
        // The 24 always-emitted (no-default) keys of ResolvedRipDpiRelayConfig.
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
            )

        // The 50 keys carrying a default; emitted only when set off-default.
        private val defaultedWireKeys =
            setOf(
                "outboundBindIp",
                "vlessTransport",
                "xhttpPath",
                "xhttpHost",
                "cloudflareTunnelMode",
                "cloudflarePublishLocalOriginUrl",
                "cloudflareCredentialsRef",
                "chainEntryProfileId",
                "chainExitProfileId",
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
                "tuicUuid",
                "tuicPassword",
                "shadowTlsPassword",
                "naiveUsername",
                "naivePassword",
                "tlsFingerprintProfile",
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

        // The complete flat wire object: required + defaulted = 74 keys.
        private val expectedWireKeys = requiredWireKeys + defaultedWireKeys
    }
}
