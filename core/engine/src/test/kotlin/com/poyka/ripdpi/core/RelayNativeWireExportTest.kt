package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Produces live Kotlin bytes for the opt-in native decoder contract check. */
class RelayNativeWireExportTest {
    @Test
    fun `export current relay payloads and schema rejection cases`() {
        val configs = representativeConfigs()
        val cases =
            configs.flatMapIndexed { index, config ->
                val wire = RipDpiJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), config)
                val withoutSchema =
                    JsonObject(
                        (RipDpiJson.parseToJsonElement(wire) as JsonObject).filterKeys { it != "schemaVersion" },
                    ).toString()
                listOf(
                    case("$index-${config.kind}-current", wire, true),
                    case("$index-${config.kind}-missing", withoutSchema, false),
                    case(
                        "$index-${config.kind}-retired",
                        RipDpiJson.encodeToString(
                            ResolvedRipDpiRelayConfig.serializer(),
                            config.copy(schemaVersion = RelayNativeConfigSchemaVersion - 1),
                        ),
                        false,
                    ),
                    case(
                        "$index-${config.kind}-future",
                        RipDpiJson.encodeToString(
                            ResolvedRipDpiRelayConfig.serializer(),
                            config.copy(schemaVersion = RelayNativeConfigSchemaVersion + 1),
                        ),
                        false,
                    ),
                )
            }
        assertTrue("the export must exercise native and Apps Script decoders", cases.size >= 64)
        File("build/reports/relay-native-wire.json").apply {
            parentFile.mkdirs()
            writeText(JsonArray(cases).toString())
        }
    }

    private fun representativeConfigs(): List<ResolvedRipDpiRelayConfig> {
        val kinds =
            listOf(
                "vless",
                "vless_reality",
                "hysteria2",
                "tuic_v5",
                "masque",
                "anytls",
                "trojan",
                "shadowsocks",
                "mieru",
                "ssh",
                "cloudflare_tunnel",
                "naiveproxy",
                "tor",
                "shadowtls_v3",
            )
        val configs =
            kinds.map { kind ->
                relayNativeBaseFixture(kind).copy(
                    profileId = "wire-$kind",
                    localSocksUsername = "wire-user",
                    localSocksPassword = relayNativePlaceholder(71),
                    socketProtection = RelaySocketProtection.VpnRequired,
                )
            } +
                listOf(
                    relayNativeThreeHopChainFixture(),
                    relayNativeBaseFixture("shadowtls_v3").copy(
                        shadowTlsInner =
                            ResolvedShadowTlsInnerRelayConfig(
                                kind = "vless_reality",
                                profileId = "wire-inner",
                                server = "inner.example",
                                serverPort = 443,
                                serverName = "inner.example",
                                tlsFingerprintProfile = "firefox_stable",
                            ),
                    ),
                    relayNativeBaseFixture("google_apps_script").copy(
                        appsScriptScriptIds = listOf("wire-script"),
                        appsScriptAuthKey = relayNativePlaceholder(72),
                        appsScriptDataDir = "/private/wire-apps-script",
                    ),
                )
        return configs
    }

    private fun case(
        name: String,
        payload: String,
        accepted: Boolean,
    ): JsonObject =
        JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "payload" to JsonPrimitive(payload),
                "accepted" to JsonPrimitive(accepted),
            ),
        )
}
