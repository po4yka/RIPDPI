package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
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
    fun `selector and urltest entries are skipped by the base parser`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "p0", "server": "p0.example.com",
                  "server_port": 443, "uuid": "11111111-1111-1111-1111-111111111111" },
                { "type": "selector", "tag": "select", "outbounds": ["p0", "auto"], "default": "auto" },
                { "type": "urltest", "tag": "auto", "outbounds": ["p0"],
                  "url": "https://www.gstatic.com/generate_204" }
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
}
