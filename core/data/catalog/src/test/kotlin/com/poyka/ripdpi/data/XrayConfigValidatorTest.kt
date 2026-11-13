package com.poyka.ripdpi.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD coverage for the Xray-config validator that catches catalog-publish-
 * time errors before they ship to clients. Targets the 2026-06-01 xray-core
 * deprecation window and the v26.1.18 XHTTP+REALITY break.
 *
 * The validator is forward-looking: RIPDPI does not currently consume
 * raw Xray-style outbound JSON (the typed `VlessOutbound` schema in
 * `core:data:model` is the in-tree representation, and the native wire
 * layer hardcodes the Vision flow). The validator exists so that any
 * future import-from-Xray-config feature, or out-of-tree publisher tool
 * that wraps RIPDPI types, can call into a single source of truth.
 *
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
class XrayConfigValidatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): JsonObject = json.decodeFromString(JsonObject.serializer(), raw)

    @Test
    fun `valid REALITY XHTTP config without Vision passes`() {
        val config =
            parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [{
                          "address": "example.com",
                          "port": 443,
                          "users": [{
                            "id": "550e8400-e29b-41d4-a716-446655440000",
                            "flow": ""
                          }]
                        }]
                      },
                      "streamSettings": {
                        "network": "xhttp",
                        "security": "reality",
                        "realitySettings": {
                          "serverName": "www.example.com",
                          "publicKey": "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345678901234567"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag = "v1.260206.0"))

        assertEquals(
            "valid config should produce no errors",
            emptyList<XrayConfigValidator.ValidationError>(),
            result,
        )
    }

    @Test
    fun `vless outbound without flow is rejected after 2026-06-01 deprecation`() {
        val config =
            parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [{
                          "address": "example.com",
                          "port": 443,
                          "users": [{
                            "id": "550e8400-e29b-41d4-a716-446655440000"
                          }]
                        }]
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag = "v1.260206.0"))

        assertTrue(
            "must reject VLESS without flow; got $result",
            result.any { it.code == XrayConfigValidator.ErrorCode.VLESS_FLOW_MISSING },
        )
    }

    @Test
    fun `vless outbound with flow but allowInsecure true is rejected`() {
        val config =
            parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [{
                          "address": "example.com",
                          "port": 443,
                          "users": [{
                            "id": "550e8400-e29b-41d4-a716-446655440000",
                            "flow": "xtls-rprx-vision"
                          }]
                        }]
                      },
                      "streamSettings": {
                        "tlsSettings": {
                          "allowInsecure": true
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag = "v1.260206.0"))

        assertTrue(
            "must reject allowInsecure=true post-2026-06-01; got $result",
            result.any { it.code == XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED },
        )
    }

    @Test
    fun `reality plus xhttp combination is rejected at xray-core v26-1-18 or later`() {
        val config =
            parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [{
                          "address": "example.com",
                          "port": 443,
                          "users": [{
                            "id": "550e8400-e29b-41d4-a716-446655440000",
                            "flow": "xtls-rprx-vision"
                          }]
                        }]
                      },
                      "streamSettings": {
                        "network": "xhttp",
                        "security": "reality"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag = "v26.1.18"))

        assertTrue(
            "must reject REALITY+XHTTP at v26.1.18; got $result",
            result.any { it.code == XrayConfigValidator.ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG },
        )
    }

    @Test
    fun `REALITY XHTTP is accepted after the upstream fix`() {
        val config =
            parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {
                        "vnext": [{
                          "address": "example.com",
                          "port": 443,
                          "users": [{
                            "id": "550e8400-e29b-41d4-a716-446655440000",
                            "flow": ""
                          }]
                        }]
                      },
                      "streamSettings": {
                        "network": "xhttp",
                        "security": "reality"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag = "v1.260206.0"))

        assertEquals(
            "REALITY+XHTTP is fine at v1.260206.0 (pre-break); got $result",
            emptyList<XrayConfigValidator.ValidationError>(),
            result,
        )
    }

    @Test
    fun `Go stable successor tags use the ancestor release date for the regression window`() {
        val config = parse("""{"outbounds":[{"streamSettings":{"network":"xhttp","security":"reality"}}]}""")
        val versions =
            mapOf(
                "v26.1.17" to false,
                "1.260117.0" to false,
                "1.260117.1-0.20260119120000-676eaf66f8f0" to true,
                "1.260117.1-0.20261109052709-676eaf66f8f0" to true,
                "v26.1.18" to true,
                "1.260118.0" to true,
                "1.260118.1-0.20261109052709-676eaf66f8f0" to true,
                "v26.2.5" to true,
                "1.260205.0" to true,
                "1.260205.1-0.20261109052709-676eaf66f8f0" to true,
                "v26.2.6" to false,
                "1.260206.0" to false,
                "1.260206.1-0.20261109052709-676eaf66f8f0" to false,
                "1.260327.1-0.20261109052709-676eaf66f8f0" to false,
                "v1.260327.1-0.20261109052709-676eaf66f8f0" to false,
            )
        for ((tag, broken) in versions) {
            val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(tag))
            assertEquals(
                tag,
                broken,
                result.any { it.code == XrayConfigValidator.ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG },
            )
        }
    }

    @Test
    fun `unknown and malformed Go pseudo versions remain unverified`() {
        val config = parse("""{"outbounds":[{"streamSettings":{"network":"xhttp","security":"reality"}}]}""")
        val versions =
            listOf(
                "unknown",
                "1.260327.1-rc.20261109052709-676eaf66f8f0",
                "1.260327.0-0.20261109052709-676eaf66f8f0",
                "1.260327.01-0.20261109052709-676eaf66f8f0",
                "1.260327.1-0.20260230052709-676eaf66f8f0",
                "1.260327.1-0.20261309052709-676eaf66f8f0",
                "1.260327.1-0.20261109242709-676eaf66f8f0",
                "1.260327.1-0.20261109056009-676eaf66f8f0",
                "1.260327.1-0.20261109052760-676eaf66f8f0",
                "1.260327.1-0.2026110905270-676eaf66f8f0",
                "1.260230.1-0.20261109052709-676eaf66f8f0",
                "1.261327.1-0.20261109052709-676eaf66f8f0",
                "1.260327.1-0.20261109052709-676eaf66f8f",
                "1.260327.1-0.20261109052709-676eaf66f8f00",
                "1.260327.1-0.20261109052709-676eaf66f8fg",
                "1.260327.1-0.20261109052709-676EAF66F8F0",
                "1.260327.1-0.20261109052709-676eaf66f8f0+incompatible",
                "26.3.27-0.20261109052709-676eaf66f8f0",
                "2.260327.1-0.20261109052709-676eaf66f8f0",
            )
        for (tag in versions) {
            val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(tag))
            assertTrue(tag, result.any { it.code == XrayConfigValidator.ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG })
        }
    }

    @Test
    fun `non-vless outbound is ignored by vless flow rule`() {
        val config =
            parse(
                """
                {
                  "outbounds": [
                    {
                      "protocol": "trojan",
                      "settings": {}
                    }
                  ]
                }
                """.trimIndent(),
            )

        val result = XrayConfigValidator.validate(config, XrayConfigValidator.Context(upstreamTag = "v1.260206.0"))

        assertEquals(
            "trojan outbound must not trigger VLESS rules; got $result",
            emptyList<XrayConfigValidator.ValidationError>(),
            result,
        )
    }
}
