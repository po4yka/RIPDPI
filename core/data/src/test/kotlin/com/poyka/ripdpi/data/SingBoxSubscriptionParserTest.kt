package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SingBoxSubscriptionParser]: maps a sing-box JSON
 * subscription (bare `outbounds` array or single-outbound object) into
 * [ProxyProfile] records.
 */
class SingBoxSubscriptionParserTest {
    private val groupId = "sub-singbox"

    private fun success(result: SingBoxParseResult): SingBoxParseResult.Success {
        assertTrue("expected success, got $result", result is SingBoxParseResult.Success)
        return result as SingBoxParseResult.Success
    }

    @Test
    fun `parses an outbounds array mapping known types`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "vless-node", "server": "vless.example.com",
                  "server_port": 443, "uuid": "11111111-1111-1111-1111-111111111111" },
                { "type": "shadowsocks", "tag": "ss-node", "server": "ss.example.com",
                  "server_port": 8388, "method": "aes-256-gcm", "password": "ss-secret" },
                { "type": "trojan", "tag": "trojan-node", "server": "trojan.example.com",
                  "server_port": 443, "password": "trojan-secret" },
                { "type": "hysteria2", "tag": "hy2-node", "server": "hy2.example.com",
                  "server_port": 8443, "password": "hy2-secret" }
              ]
            }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(4, parsed.profiles.size)
        assertTrue(parsed.profiles[0] is ProxyProfile.Vless)
        assertTrue(parsed.profiles[1] is ProxyProfile.Shadowsocks)
        assertTrue(parsed.profiles[2] is ProxyProfile.Trojan)
        assertTrue(parsed.profiles[3] is ProxyProfile.Hysteria2)

        val vless = parsed.profiles[0] as ProxyProfile.Vless
        assertEquals("vless.example.com", vless.server)
        assertEquals(443, vless.serverPort)
        assertEquals("11111111-1111-1111-1111-111111111111", vless.uuid)
        assertEquals("vless-node", vless.displayName)
    }

    @Test
    fun `wraps a single outbound object as a one element list`() {
        val json =
            """
            { "type": "trojan", "tag": "solo", "server": "solo.example.com",
              "server_port": 443, "password": "solo-secret" }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(1, parsed.profiles.size)
        assertTrue(parsed.profiles.single() is ProxyProfile.Trojan)
        assertEquals("solo", parsed.profiles.single().displayName)
    }

    @Test
    fun `unknown outbound type falls through to raw config holding the json fragment`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "wireguard", "tag": "wg-node", "server": "wg.example.com",
                  "server_port": 51820, "private_key": "secret" },
                { "type": "ssh", "tag": "ssh-node", "server": "ssh.example.com",
                  "server_port": 22, "user": "root" }
              ]
            }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(2, parsed.profiles.size)
        assertTrue(parsed.profiles.all { it is ProxyProfile.RawConfig })
        val wg = parsed.profiles[0] as ProxyProfile.RawConfig
        assertEquals("wg-node", wg.displayName)
        assertTrue(wg.config.contains("wireguard"))
        assertTrue(wg.config.contains("wg.example.com"))
    }

    @Test
    fun `maps anytls outbound to anytls profile`() {
        val json =
            """
            {
              "outbounds": [
                {
                  "type": "anytls",
                  "tag": "anytls-node",
                  "server": "anytls.example.com",
                  "server_port": 443,
                  "password": "anytls-secret",
                  "tls": {"server_name": "front.example"}
                }
              ]
            }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        val profile = parsed.profiles.single()
        assertTrue(profile is ProxyProfile.AnyTls)
        profile as ProxyProfile.AnyTls
        assertEquals("anytls-node", profile.displayName)
        assertEquals("anytls.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("front.example", profile.serverName)
        assertEquals("anytls-secret", profile.password)
    }

    @Test
    fun `ignores inbounds route and dns sections`() {
        val json =
            """
            {
              "inbounds": [ { "type": "tun" } ],
              "route": { "rules": [] },
              "dns": { "servers": [] },
              "outbounds": [
                { "type": "trojan", "tag": "only", "server": "only.example.com",
                  "server_port": 443, "password": "p" }
              ]
            }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(1, parsed.profiles.size)
        assertTrue(parsed.profiles.single() is ProxyProfile.Trojan)
    }

    @Test
    fun `group and service outbounds are skipped by the base parser`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "p0", "server": "p0.example.com",
                  "server_port": 443, "uuid": "11111111-1111-1111-1111-111111111111" },
                { "type": "selector", "tag": "select", "outbounds": ["p0", "auto"], "default": "auto" },
                { "type": "urltest", "tag": "auto", "outbounds": ["p0"],
                  "url": "https://www.gstatic.com/generate_204" },
                { "type": "direct", "tag": "direct" },
                { "type": "block", "tag": "block" },
                { "type": "dns", "tag": "dns-out" }
              ]
            }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(1, parsed.profiles.size)
        assertTrue(parsed.profiles.single() is ProxyProfile.Vless)
    }

    @Test
    fun `malformed json surfaces a typed error with a location pointer`() {
        val result = SingBoxSubscriptionParser.parse("{ not json at all ", groupId)

        assertTrue(result is SingBoxParseResult.Error)
        result as SingBoxParseResult.Error
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `non-json payload surfaces a typed error`() {
        val result = SingBoxSubscriptionParser.parse("proxies:\n  - {}", groupId)

        assertTrue(result is SingBoxParseResult.Error)
    }

    @Test
    fun `assigns the supplied group id to every parsed profile`() {
        val json =
            """
            { "outbounds": [
              { "type": "trojan", "tag": "n", "server": "t.example.com",
                "server_port": 443, "password": "p" } ] }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertTrue(parsed.profiles.all { it.groupId == groupId })
    }

    @Test
    fun `imports supported package routes with subscription provenance`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "trojan", "tag": "n", "server": "t.example.com",
                  "server_port": 443, "password": "p" }
              ],
              "route": {
                "rules": [
                  { "package_name": ["com.bypass.app"], "outbound": "direct" },
                  { "package_name": ["com.tunnel.app"], "outbound": "select" }
                ]
              }
            }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(2, parsed.packageRoutingRules.size)
        assertEquals(
            PackageRoutingAction.BYPASS,
            parsed.packageRoutingRules.single { it.packageName == "com.bypass.app" }.action,
        )
        assertEquals(
            PackageRoutingAction.VIA_TUN,
            parsed.packageRoutingRules.single { it.packageName == "com.tunnel.app" }.action,
        )
        assertTrue(
            parsed.packageRoutingRules.all {
                it.origin == PackageRoutingRuleOrigin.Subscription(groupId)
            },
        )
    }

    @Test
    fun `rejects unsupported package route before returning partial profiles`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "trojan", "tag": "n", "server": "t.example.com",
                  "server_port": 443, "password": "p" }
              ],
              "route": {
                "rules": [
                  { "package_name": ["com.unsupported.app"], "outbound": "named-proxy" }
                ]
              }
            }
            """.trimIndent()

        val result = SingBoxSubscriptionParser.parse(json, groupId)

        assertTrue("expected error, got $result", result is SingBoxParseResult.Error)
        result as SingBoxParseResult.Error
        assertTrue(result.message.contains("named-proxy"))
    }

    @Test
    fun `unsupported vless transports are typed skips`() {
        val json =
            """
            { "outbounds": [
              { "type": "vless", "tag": "ws-node", "server": "w.example", "server_port": 443,
                "uuid": "11111111-1111-1111-1111-111111111111", "transport": { "type": "ws" } },
              { "type": "vless", "tag": "grpc-node", "server": "g.example", "server_port": 443,
                "uuid": "22222222-2222-2222-2222-222222222222", "transport": { "type": "grpc" } } ] }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertTrue(parsed.profiles.isEmpty())
        assertEquals(listOf("ws", "grpc"), parsed.skipped.map { it.detail })
        assertTrue(parsed.skipped.all { it.reason == SingBoxSkipReason.UNSUPPORTED_TRANSPORT })
    }

    @Test
    fun `unsupported vless fingerprint is a typed skip`() {
        val json =
            """
            { "type": "vless", "tag": "randomized", "server": "edge.example", "server_port": 443,
              "uuid": "11111111-1111-1111-1111-111111111111",
              "tls": { "utls": { "fingerprint": "randomized" } } }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertTrue(parsed.profiles.isEmpty())
        assertEquals(SingBoxSkipReason.UNSUPPORTED_FINGERPRINT, parsed.skipped.single().reason)
    }

    @Test
    fun `wrapped trojan and shadowsocks transports are typed skips`() {
        val json =
            """
            { "outbounds": [
              { "type": "trojan", "tag": "trojan-ws", "server": "t.example", "server_port": 443,
                "password": "p", "transport": { "type": "ws" } },
              { "type": "shadowsocks", "tag": "ss-grpc", "server": "s.example", "server_port": 8388,
                "method": "aes-256-gcm", "password": "p", "transport": { "type": "grpc" } } ] }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertTrue(parsed.profiles.isEmpty())
        assertEquals(listOf("ws", "grpc"), parsed.skipped.map { it.detail })
        assertTrue(parsed.skipped.all { it.reason == SingBoxSkipReason.UNSUPPORTED_TRANSPORT })
    }

    @Test
    fun `standard hysteria identity and salamander fields are imported`() {
        val json =
            """
            { "type": "hysteria2", "tag": "hy2", "server": "203.0.113.9", "server_port": 443,
              "password": "test-value", "tls": { "server_name": "hy.example", "insecure": true },
              "obfs": { "type": "salamander", "password": "obfs-test-value" } }
            """.trimIndent()

        val profile =
            success(
                SingBoxSubscriptionParser.parse(json, groupId),
            ).profiles.single() as ProxyProfile.Hysteria2

        assertEquals("hy.example", profile.serverName)
        assertEquals(true, profile.insecure)
        assertEquals("obfs-test-value", profile.obfsPassword)
    }

    @Test
    fun `unsupported hysteria obfs and port hopping are typed skips`() {
        val json =
            """
            { "outbounds": [
              { "type": "hysteria2", "tag": "gecko", "server": "h.example", "server_port": 443,
                "password": "p", "obfs": { "type": "gecko", "password": "test-value" } },
              { "type": "hysteria2", "tag": "hop", "server": "h.example", "server_port": 443,
                "server_ports": ["20000:30000"], "password": "p" } ] }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertTrue(parsed.profiles.isEmpty())
        assertEquals(SingBoxSkipReason.UNSUPPORTED_OBFUSCATION, parsed.skipped[0].reason)
        assertEquals(SingBoxSkipReason.UNSUPPORTED_PORT_HOPPING, parsed.skipped[1].reason)
    }

    @Test
    fun `blank or unsupported-cipher nodes degrade to RawConfig, not connectable members (P1-10)`() {
        val json =
            """
            { "outbounds": [
              { "type": "shadowsocks", "tag": "ss-empty", "server": "ss.example.com",
                "server_port": 8388, "method": "aes-256-gcm", "password": "" },
              { "type": "trojan", "tag": "trojan-empty", "server": "t.example.com",
                "server_port": 443, "password": "" },
              { "type": "hysteria2", "tag": "hy2-empty", "server": "h.example.com",
                "server_port": 8443, "password": "" },
              { "type": "vless", "tag": "vless-empty", "server": "v.example.com",
                "server_port": 443, "uuid": "" },
              { "type": "shadowsocks", "tag": "ss-badcipher", "server": "ss.example.com",
                "server_port": 8388, "method": "rc4-md5", "password": "x" } ] }
            """.trimIndent()

        val parsed = success(SingBoxSubscriptionParser.parse(json, groupId))

        assertEquals(5, parsed.profiles.size)
        assertTrue(
            "every credential-less / bad-cipher node must be an inert RawConfig",
            parsed.profiles.all { it is ProxyProfile.RawConfig },
        )
    }
}
