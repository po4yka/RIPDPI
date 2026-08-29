package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.data.XrayConfigValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Golden + behavioural coverage for [XrayConfigRenderer].
 *
 * - A valid VLESS/REALITY profile renders the expected JSON.
 * - A valid VLESS/XHTTP profile renders the expected JSON.
 * - Invalid combinations are refused via [XrayConfigValidator] error codes.
 * - Rendered configs are gated by the injected [XrayConfigTester].
 *
 * The golden strings are produced by re-encoding through a canonical [Json]
 * (`prettyPrint`) so key ordering is the renderer's insertion order and the
 * comparison is stable across runs.
 *
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
class XrayConfigRendererTest {
    private val canonical =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** Pre-2026.1.18 tag so REALITY+XHTTP is not flagged by the version gate. */
    private val stableTag = "v1.260206.0"

    private fun render(
        profile: XrayProfile,
        tag: String = stableTag,
        tester: XrayConfigTester = XrayConfigTester.NoOp,
    ): XrayConfigRenderer.Result = XrayConfigRenderer(tester).render(profile, tag)

    private fun successConfig(result: XrayConfigRenderer.Result): JsonObject =
        when (result) {
            is XrayConfigRenderer.Result.Success -> {
                result.config
            }

            is XrayConfigRenderer.Result.Rejected -> {
                fail("expected Success, got Rejected: $result")
                error("unreachable")
            }
        }

    private fun realityProfile(): XrayProfile =
        XrayProfile(
            name = "reality-vision",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 443,
                    uuid = "550e8400-e29b-41d4-a716-446655440000",
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                            serverName = "www.cloudflare.com",
                            shortId = "0123abcd",
                            fingerprint = "chrome",
                        ),
                ),
        )

    private fun xhttpProfile(): XrayProfile =
        XrayProfile(
            name = "reality-xhttp",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 443,
                    uuid = "550e8400-e29b-41d4-a716-446655440000",
                    flow = "",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.XHTTP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                            serverName = "www.cloudflare.com",
                            shortId = "0123abcd",
                            fingerprint = "chrome",
                        ),
                    xhttp = XrayProfile.Xhttp(path = "/stream", mode = "stream-up", host = "edge.example.com"),
                ),
        )

    @Test
    fun `valid VLESS REALITY profile renders expected JSON`() {
        val config = successConfig(render(realityProfile()))
        val expected =
            """
            {
              "dns": {
                "servers": [
                  "1.1.1.1",
                  "8.8.8.8"
                ],
                "queryStrategy": "UseIP"
              },
              "inbounds": [
                {
                  "tag": "socks-in",
                  "listen": "127.0.0.1",
                  "port": 10808,
                  "protocol": "socks",
                  "settings": {
                    "auth": "noauth",
                    "udp": true
                  }
                }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "edge.example.com",
                        "port": 443,
                        "users": [
                          {
                            "id": "550e8400-e29b-41d4-a716-446655440000",
                            "encryption": "none",
                            "flow": "xtls-rprx-vision"
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "reality",
                    "realitySettings": {
                      "serverName": "www.cloudflare.com",
                      "publicKey": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                      "shortId": "0123abcd",
                      "fingerprint": "chrome"
                    }
                  }
                },
                {
                  "tag": "direct",
                  "protocol": "freedom",
                  "settings": {}
                }
              ],
              "routing": {
                "rules": [
                  {
                    "type": "field",
                    "network": "tcp,udp",
                    "outboundTag": "proxy"
                  }
                ]
              }
            }
            """.trimIndent()
        assertEquals(expected, canonical.encodeToString(JsonObject.serializer(), config))
    }

    @Test
    fun `valid VLESS XHTTP profile renders expected JSON`() {
        val config = successConfig(render(xhttpProfile()))
        val expected =
            """
            {
              "dns": {
                "servers": [
                  "1.1.1.1",
                  "8.8.8.8"
                ],
                "queryStrategy": "UseIP"
              },
              "inbounds": [
                {
                  "tag": "socks-in",
                  "listen": "127.0.0.1",
                  "port": 10808,
                  "protocol": "socks",
                  "settings": {
                    "auth": "noauth",
                    "udp": true
                  }
                }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "edge.example.com",
                        "port": 443,
                        "users": [
                          {
                            "id": "550e8400-e29b-41d4-a716-446655440000",
                            "encryption": "none",
                            "flow": ""
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "xhttp",
                    "security": "reality",
                    "realitySettings": {
                      "serverName": "www.cloudflare.com",
                      "publicKey": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                      "shortId": "0123abcd",
                      "fingerprint": "chrome"
                    },
                    "xhttpSettings": {
                      "path": "/stream",
                      "mode": "stream-up",
                      "host": "edge.example.com"
                    }
                  }
                },
                {
                  "tag": "direct",
                  "protocol": "freedom",
                  "settings": {}
                }
              ],
              "routing": {
                "rules": [
                  {
                    "type": "field",
                    "network": "tcp,udp",
                    "outboundTag": "proxy"
                  }
                ]
              }
            }
            """.trimIndent()
        assertEquals(expected, canonical.encodeToString(JsonObject.serializer(), config))
    }

    @Test
    fun `provider rejects a SOCKS listener exposed outside loopback`() {
        val profile = realityProfile().copy(inbound = XrayProfile.LocalInbound(listen = "0.0.0.0"))
        assertTrue(render(profile) is XrayConfigRenderer.Result.Rejected)
    }

    @Test
    fun `empty flow is rejected with VLESS_FLOW_MISSING`() {
        val profile = realityProfile().let { it.copy(outbound = it.outbound.copy(flow = "")) }
        val result = render(profile)
        val rejected =
            result as? XrayConfigRenderer.Result.Rejected
                ?: fail("expected Rejected, got $result").let { error("unreachable") }
        assertTrue(
            "must reject empty flow; got $rejected",
            rejected.validationErrors.any { it.code == XrayConfigValidator.ErrorCode.VLESS_FLOW_MISSING },
        )
    }

    @Test
    fun `TLS profile with allowInsecure true is rejected`() {
        val profile =
            XrayProfile(
                name = "insecure-tls",
                outbound =
                    XrayProfile.Outbound(
                        serverAddress = "edge.example.com",
                        serverPort = 443,
                        uuid = "550e8400-e29b-41d4-a716-446655440000",
                        flow = "xtls-rprx-vision",
                        security = XrayProfile.Security.TLS,
                        network = XrayProfile.Network.TCP,
                        tls = XrayProfile.Tls(serverName = "edge.example.com", allowInsecure = true),
                    ),
            )
        val result = render(profile)
        val rejected =
            result as? XrayConfigRenderer.Result.Rejected
                ?: fail("expected Rejected, got $result").let { error("unreachable") }
        assertTrue(
            "must reject allowInsecure=true; got $rejected",
            rejected.validationErrors.any { it.code == XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED },
        )
    }

    @Test
    fun `REALITY plus XHTTP is rejected at broken upstream tag`() {
        val result = render(xhttpProfile(), tag = "v26.1.18")
        val rejected =
            result as? XrayConfigRenderer.Result.Rejected
                ?: fail("expected Rejected, got $result").let { error("unreachable") }
        assertTrue(
            "must reject REALITY+XHTTP at v26.1.18; got $rejected",
            rejected.validationErrors.any {
                it.code == XrayConfigValidator.ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG
            },
        )
    }

    @Test
    fun `tester rejection surfaces as Rejected with testError`() {
        val tester = XrayConfigTester { XrayConfigTester.TestResult.Invalid("xray-core: unknown transport") }
        val result = render(realityProfile(), tester = tester)
        val rejected =
            result as? XrayConfigRenderer.Result.Rejected
                ?: fail("expected Rejected, got $result").let { error("unreachable") }
        assertTrue(rejected.validationErrors.isEmpty())
        assertEquals("xray-core: unknown transport", rejected.testError)
    }

    @Test
    fun `validateImported accepts a valid raw config and rejects an invalid one`() {
        val renderer = XrayConfigRenderer()
        val valid = successConfig(render(realityProfile()))
        assertTrue(renderer.validateImported(valid, stableTag) is XrayConfigRenderer.Result.Success)

        val invalidRaw =
            Json.decodeFromString(
                JsonObject.serializer(),
                """
                {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"a","port":1,
                "users":[{"id":"x"}]}]}}]}
                """.trimIndent(),
            )
        val result = renderer.validateImported(invalidRaw, stableTag)
        val rejected =
            result as? XrayConfigRenderer.Result.Rejected
                ?: fail("expected Rejected, got $result").let { error("unreachable") }
        assertTrue(
            rejected.validationErrors.any {
                it.code == XrayConfigValidator.ErrorCode.VLESS_FLOW_MISSING
            },
        )
    }
}
