package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.ClashSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClashSubscriptionParser]: maps the `proxies:` array of a
 * Clash / Clash.Meta YAML subscription into [ProxyProfile] records.
 */
class ClashSubscriptionParserTest {
    private val groupId = "sub-clash"

    @Test
    fun `detects clash yaml by the proxies top level key`() {
        assertTrue(ClashSubscriptionParser.looksLikeClash("proxies:\n  - {}"))
        assertFalse(ClashSubscriptionParser.looksLikeClash("{\"outbounds\":[]}"))
        assertFalse(ClashSubscriptionParser.looksLikeClash("vless://uuid@host:443"))
    }

    @Test
    fun `parses ss vmess vless trojan hysteria2 nodes`() {
        val yaml =
            """
            proxies:
              - name: ss-node
                type: ss
                server: ss.example.com
                port: 8388
                cipher: aes-256-gcm
                password: ss-secret
              - name: vmess-node
                type: vmess
                server: vmess.example.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
              - name: vless-node
                type: vless
                server: vless.example.com
                port: 443
                uuid: 22222222-2222-2222-2222-222222222222
                reality-opts:
                  public-key: pk
                  short-id: sid
              - name: trojan-node
                type: trojan
                server: trojan.example.com
                port: 443
                password: trojan-secret
              - name: hy2-node
                type: hysteria2
                server: hy2.example.com
                port: 8443
                password: hy2-secret
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)

        assertTrue(result.errors.isEmpty())
        assertEquals(5, result.profiles.size)

        val ss = result.profiles[0]
        assertTrue(ss is ProxyProfile.Shadowsocks)
        ss as ProxyProfile.Shadowsocks
        assertEquals("ss.example.com", ss.server)
        assertEquals(8388, ss.serverPort)
        assertEquals("aes-256-gcm", ss.method)
        assertEquals("ss-secret", ss.password)

        val vmess = result.profiles[1]
        assertTrue(vmess is ProxyProfile.Vless)
        assertEquals("11111111-1111-1111-1111-111111111111", (vmess as ProxyProfile.Vless).uuid)

        // reality-opts on the node routes it to the REALITY variant.
        val vless = result.profiles[2]
        assertTrue(vless is ProxyProfile.VlessReality)
        assertEquals("22222222-2222-2222-2222-222222222222", (vless as ProxyProfile.VlessReality).uuid)

        val trojan = result.profiles[3]
        assertTrue(trojan is ProxyProfile.Trojan)
        assertEquals("trojan-secret", (trojan as ProxyProfile.Trojan).password)

        val hy2 = result.profiles[4]
        assertTrue(hy2 is ProxyProfile.Hysteria2)
        assertEquals("hy2-secret", (hy2 as ProxyProfile.Hysteria2).password)
    }

    @Test
    fun `ignores unknown fields on a node`() {
        val yaml =
            """
            proxies:
              - name: extra-fields
                type: trojan
                server: trojan.example.com
                port: 443
                password: secret
                udp: true
                sni: trojan.example.com
                skip-cert-verify: true
                some-future-key: whatever
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)

        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.profiles.size)
        assertTrue(result.profiles.single() is ProxyProfile.Trojan)
    }

    @Test
    fun `maps unmappable proxy types to raw config`() {
        val yaml =
            """
            proxies:
              - name: socks-node
                type: socks5
                server: socks.example.com
                port: 1080
              - name: http-node
                type: http
                server: http.example.com
                port: 8080
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)

        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.profiles.size)
        assertTrue(result.profiles.all { it is ProxyProfile.RawConfig })
        assertEquals("socks-node", result.profiles[0].displayName)
    }

    @Test
    fun `maps anytls nodes to anytls profiles`() {
        val yaml =
            """
            proxies:
              - name: anytls-node
                type: anytls
                server: anytls.example.com
                port: 443
                password: anytls-secret
                sni: front.example
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)

        assertTrue(result.errors.isEmpty())
        val profile = result.profiles.single()
        assertTrue(profile is ProxyProfile.AnyTls)
        profile as ProxyProfile.AnyTls
        assertEquals("anytls.example.com", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("front.example", profile.serverName)
        assertEquals("anytls-secret", profile.password)
    }

    @Test
    fun `surfaces a typed error with the failing node index for a malformed node`() {
        val yaml =
            """
            proxies:
              - name: good-node
                type: trojan
                server: trojan.example.com
                port: 443
                password: secret
              - name: broken-node
                type: ss
                server: ss.example.com
                cipher: aes-256-gcm
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)

        assertEquals(1, result.profiles.size)
        assertEquals(1, result.errors.size)
        assertEquals(1, result.errors.single().nodeIndex)
    }

    @Test
    fun `non-clash payload yields an empty result without throwing`() {
        val result = ClashSubscriptionParser.parse("vless://uuid@host:443", groupId)

        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `completely malformed yaml surfaces a parse error rather than throwing`() {
        val result = ClashSubscriptionParser.parse("proxies:\n  - : : : not yaml : :", groupId)

        assertTrue(result.profiles.isEmpty())
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `assigns the supplied group id to every parsed profile`() {
        val yaml =
            """
            proxies:
              - name: n
                type: trojan
                server: t.example.com
                port: 443
                password: p
            """.trimIndent()

        val result = ClashSubscriptionParser.parse(yaml, groupId)

        assertTrue(result.profiles.all { it.groupId == groupId })
    }
}
