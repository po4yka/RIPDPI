package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Base64SubscriptionParser]: the fallback path for
 * subscription payloads that are base64-encoded URI lists or already-decoded
 * plain URI lists.
 */
class Base64SubscriptionParserTest {
    private val groupId = "sub-base64"

    private fun trojanUri(tag: String) = "trojan://pass-$tag@$tag.example.com:443#$tag"

    private fun plainList(): String =
        listOf(
            trojanUri("a"),
            trojanUri("b"),
            "vless://00000000-0000-0000-0000-000000000000@v.example.com:443#vnode",
        ).joinToString("\n")

    @Test
    fun `parses a pure base64 encoded uri list`() {
        val encoded =
            java.util.Base64
                .getUrlEncoder()
                .encodeToString(plainList().toByteArray())

        val result = Base64SubscriptionParser.parse(encoded, groupId)

        assertEquals(3, result.profiles.size)
        assertTrue(result.warnings.isEmpty())
        assertEquals(listOf("a.example.com", "b.example.com", "v.example.com"), result.profiles.map(::serverOf))
    }

    @Test
    fun `parses a plain text uri list when base64 decode fails`() {
        val result = Base64SubscriptionParser.parse(plainList(), groupId)

        assertEquals(3, result.profiles.size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `skips blank lines and comment lines`() {
        val payload =
            buildString {
                appendLine("# this is a comment")
                appendLine("")
                appendLine("   ")
                appendLine(trojanUri("only"))
                appendLine("\t# trailing comment")
            }

        val result = Base64SubscriptionParser.parse(payload, groupId)

        assertEquals(1, result.profiles.size)
        assertEquals("only.example.com", serverOf(result.profiles.single()))
    }

    @Test
    fun `trims surrounding whitespace per line`() {
        val payload = "   ${trojanUri("trimmed")}   \r\n"

        val result = Base64SubscriptionParser.parse(payload, groupId)

        assertEquals(1, result.profiles.size)
        assertEquals("trimmed.example.com", serverOf(result.profiles.single()))
    }

    @Test
    fun `splits on CR LF and CRLF line endings`() {
        val payload = trojanUri("cr") + "\r" + trojanUri("lf") + "\n" + trojanUri("crlf") + "\r\n"

        val result = Base64SubscriptionParser.parse(payload, groupId)

        assertEquals(3, result.profiles.size)
    }

    @Test
    fun `unknown scheme emits a typed warning and skips the line`() {
        val payload =
            listOf(
                trojanUri("good"),
                "wireguard://nope@host:51820",
                "garbage-line",
            ).joinToString("\n")

        val result = Base64SubscriptionParser.parse(payload, groupId)

        assertEquals(1, result.profiles.size)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.all { it.lineNumber > 0 })
        assertTrue(result.warnings.any { it.line == "wireguard://nope@host:51820" })
    }

    @Test
    fun `whitespace-only payload yields no profiles and no warnings`() {
        val result = Base64SubscriptionParser.parse("   \n\t\r\n  ", groupId)

        assertTrue(result.profiles.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `mixed payload where a line is accidentally re-encoded is reported as a warning`() {
        // One line is itself a base64 blob (not a URI) sitting inside a plain list.
        val reEncoded =
            java.util.Base64
                .getUrlEncoder()
                .encodeToString(trojanUri("inner").toByteArray())
        val payload = listOf(trojanUri("outer"), reEncoded).joinToString("\n")

        val result = Base64SubscriptionParser.parse(payload, groupId)

        assertEquals(1, result.profiles.size)
        assertEquals("outer.example.com", serverOf(result.profiles.single()))
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `assigns the supplied group id to every parsed profile`() {
        val result = Base64SubscriptionParser.parse(plainList(), groupId)

        assertTrue(result.profiles.all { it.groupId == groupId })
    }

    private fun serverOf(profile: ProxyProfile): String =
        when (profile) {
            is ProxyProfile.Vless -> profile.server
            is ProxyProfile.VlessReality -> profile.server
            is ProxyProfile.Shadowsocks -> profile.server
            is ProxyProfile.Trojan -> profile.server
            is ProxyProfile.Hysteria2 -> profile.server
            is ProxyProfile.AnyTls -> profile.server
            is ProxyProfile.Mieru -> profile.server
            is ProxyProfile.RawConfig -> profile.config
        }
}
