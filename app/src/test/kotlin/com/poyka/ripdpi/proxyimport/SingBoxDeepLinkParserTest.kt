package com.poyka.ripdpi.proxyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Pure-logic tests for [SingBoxDeepLinkParser]: the
 * `singbox://import-remote-profile?url=<enc>&name=<enc>` deep-link convention shared
 * with sing-box-for-Android / NekoBox. The parser URL-decodes both fields, rejects an
 * absent `url=`, flags a `/bootstrap/` URL path as bootstrap mode, and never throws on
 * malformed percent-encoding.
 */
class SingBoxDeepLinkParserTest {
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    @Test
    fun `unescaped fragment remarks stay supported without repairing bad escapes`() {
        val base = "sing-box://import-remote-profile?url=${enc("https://s.example/c")}"
        for (remark in listOf("Home | Fleet", "Node {1} #backup", "東京 | 1")) {
            assertEquals(
                SingBoxDeepLinkResult.Success("https://s.example/c", remark, false),
                SingBoxDeepLinkParser.parse("$base#$remark"),
            )
        }
        assertEquals(SingBoxDeepLinkResult.Error.BadEncoding, SingBoxDeepLinkParser.parse("$base#bad%ZZ"))
    }

    @Test
    fun `fragment plus is data while query name keeps form encoding`() {
        val base = "sing-box://import-remote-profile?url=${enc("https://s.example/c")}"
        for (fragment in listOf("A+B", "A%2BB")) {
            assertEquals(
                SingBoxDeepLinkResult.Success("https://s.example/c", "A+B", false),
                SingBoxDeepLinkParser.parse("$base#$fragment"),
            )
        }
        assertEquals(
            SingBoxDeepLinkResult.Success("https://s.example/c", "A B", false),
            SingBoxDeepLinkParser.parse("$base&name=A+B#ignored"),
        )
        assertEquals(
            SingBoxDeepLinkResult.Success("https://s.example/c", "Home Fleet", false),
            SingBoxDeepLinkParser.parse("$base#Home Fleet"),
        )
    }

    @Test
    fun `bootstrap detection only uses the target path`() {
        val target = "https://s.example?next=/bootstrap/token"
        assertEquals(
            SingBoxDeepLinkResult.Success(target, "", false),
            SingBoxDeepLinkParser.parse("sing-box://import-remote-profile?url=${enc(target)}"),
        )
    }

    @Test
    fun `invalid and non HTTPS subscription targets are rejected`() {
        for (target in listOf("https://", "http://s.example/c", "ftp://s.example/c")) {
            assertEquals(
                target,
                SingBoxDeepLinkResult.Error.BadEncoding,
                SingBoxDeepLinkParser.parse("sing-box://import-remote-profile?url=${enc(target)}"),
            )
        }
    }

    @Test
    fun `duplicate parameters keep the first value and encoded hiddify paths decode once`() {
        val target = "https://s.example/c?token=a+b"
        assertEquals(
            SingBoxDeepLinkResult.Success(target, "First", false),
            SingBoxDeepLinkParser.parse(
                "sing-box://import-remote-profile?url=${enc(target)}&url=${enc("https://other.example/")}" +
                    "&name=First&name=Second",
            ),
        )
        assertEquals(
            SingBoxDeepLinkResult.Success(target, "A+B", false),
            SingBoxDeepLinkParser.parse("hiddify://import/${enc(target)}#A%2BB"),
        )
    }

    @Test
    fun `singbox scheme deep-link parses url and name`() {
        val link =
            "singbox://import-remote-profile?url=${enc("https://sub.example.com/c")}" +
                "&name=${enc("Home Fleet")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        result as SingBoxDeepLinkResult.Success
        assertEquals("https://sub.example.com/c", result.url)
        assertEquals("Home Fleet", result.name)
        assertFalse(result.bootstrap)
    }

    @Test
    fun `ripdpi scheme deep-link parses identically`() {
        val link = "ripdpi://import-remote-profile?url=${enc("https://sub.example.com/c")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        assertEquals("https://sub.example.com/c", (result as SingBoxDeepLinkResult.Success).url)
    }

    @Test
    fun `sn scheme deep-link parses identically`() {
        val link = "sn://import-remote-profile?url=${enc("https://sub.example.com/c")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
    }

    @Test
    fun `name is optional and defaults to empty`() {
        val link = "singbox://import-remote-profile?url=${enc("https://sub.example.com/c")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        assertEquals("", (result as SingBoxDeepLinkResult.Success).name)
    }

    @Test
    fun `missing url query yields a typed missing-url error`() {
        val link = "singbox://import-remote-profile?name=${enc("Nameless")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertEquals(SingBoxDeepLinkResult.Error.MissingUrl, result)
    }

    @Test
    fun `empty url query yields a typed missing-url error`() {
        val link = "singbox://import-remote-profile?url="

        val result = SingBoxDeepLinkParser.parse(link)

        assertEquals(SingBoxDeepLinkResult.Error.MissingUrl, result)
    }

    @Test
    fun `wrong host yields a typed unsupported error`() {
        val link = "singbox://some-other-host?url=${enc("https://sub.example.com/c")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertEquals(SingBoxDeepLinkResult.Error.Unsupported, result)
    }

    @Test
    fun `mixed-case scheme and host parse identically`() {
        val link = "SingBox://Import-Remote-Profile?url=${enc("https://sub.example.com/c")}"

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        assertEquals("https://sub.example.com/c", (result as SingBoxDeepLinkResult.Success).url)
    }

    @Test
    fun `non subscription scheme yields a typed unsupported error`() {
        val result = SingBoxDeepLinkParser.parse("vless://uuid@example.com:443")

        assertEquals(SingBoxDeepLinkResult.Error.Unsupported, result)
    }

    @Test
    fun `bootstrap url path flips the bootstrap flag`() {
        val link =
            "singbox://import-remote-profile?url=" +
                enc("https://sub.example.com/bootstrap/token-abc")

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        assertTrue((result as SingBoxDeepLinkResult.Success).bootstrap)
    }

    @Test
    fun `non bootstrap path leaves the bootstrap flag false`() {
        val link =
            "singbox://import-remote-profile?url=" +
                enc("https://sub.example.com/sub/regular")

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        assertFalse((result as SingBoxDeepLinkResult.Success).bootstrap)
    }

    @Test
    fun `malformed percent-encoding yields a typed bad-encoding error, never throws`() {
        // A stray '%' that is not a valid percent-escape.
        val link = "singbox://import-remote-profile?url=https%ZZbad"

        val result = SingBoxDeepLinkParser.parse(link)

        assertEquals(SingBoxDeepLinkResult.Error.BadEncoding, result)
    }

    @Test
    fun `unknown extra params are ignored`() {
        val link =
            "singbox://import-remote-profile?url=${enc("https://sub.example.com/c")}" +
                "&name=${enc("Fleet")}&extra=ignored&another=1"

        val result = SingBoxDeepLinkParser.parse(link)

        assertTrue(result is SingBoxDeepLinkResult.Success)
        result as SingBoxDeepLinkResult.Success
        assertEquals("https://sub.example.com/c", result.url)
        assertEquals("Fleet", result.name)
    }

    @Test
    fun `blank input yields a typed unsupported error`() {
        assertEquals(SingBoxDeepLinkResult.Error.Unsupported, SingBoxDeepLinkParser.parse("  "))
    }

    @Test
    fun `official sing-box scheme takes the name from the fragment`() {
        val result =
            SingBoxDeepLinkParser.parse(
                "sing-box://import-remote-profile?url=${enc("https://s.example/c")}#Home%20Fleet",
            )

        assertEquals(SingBoxDeepLinkResult.Success("https://s.example/c", "Home Fleet", false), result)
    }

    @Test
    fun `other clients subscription links parse to the same request`() {
        val url = enc("https://s.example/c")
        val expected = SingBoxDeepLinkResult.Success("https://s.example/c", "Fleet", false)

        assertEquals(expected, SingBoxDeepLinkParser.parse("sn://subscription?url=$url&name=Fleet"))
        assertEquals(expected, SingBoxDeepLinkParser.parse("clash://install-config?url=$url&name=Fleet"))
        assertEquals(expected, SingBoxDeepLinkParser.parse("clashmeta://install-config?url=$url&name=Fleet"))
        assertEquals(expected, SingBoxDeepLinkParser.parse("v2rayng://install-sub?url=$url#Fleet"))
        assertEquals(expected, SingBoxDeepLinkParser.parse("v2rayng://install-config?url=$url#Fleet"))
        assertEquals(expected, SingBoxDeepLinkParser.parse("hiddify://install-config?url=$url#Fleet"))
    }

    @Test
    fun `hiddify import path carries the raw url with its own query`() {
        assertEquals(
            SingBoxDeepLinkResult.Success("https://s.example/c?token=t", "Fleet", false),
            SingBoxDeepLinkParser.parse("hiddify://import/https://s.example/c?token=t#Fleet"),
        )
    }

    @Test
    fun `hiddify IPv6 targets preserve query escapes and bootstrap intent`() {
        val targets =
            listOf(
                "https://[2001:db8::1]/sub" to false,
                "https://[2001:db8::1]:8443/bootstrap/token?escaped=a%2Bb&literal=a+b" to true,
            )
        for ((target, bootstrap) in targets) {
            for (path in listOf(target, enc(target))) {
                for (prefix in listOf("hiddify://import/", "HiDdIfY://IMPORT/")) {
                    assertEquals(
                        SingBoxDeepLinkResult.Success(target, "Fleet+東京", bootstrap),
                        SingBoxDeepLinkParser.parse("$prefix$path#Fleet%2B東京"),
                    )
                }
            }
        }
    }

    @Test
    fun `hiddify nested targets still reject invalid URLs and percent escapes`() {
        for (target in listOf(
            "http://[2001:db8::1]/sub",
            "https://[not-ipv6]/sub",
            "https://[2001:db8::1/sub",
            "https://[2001:db8::1]/sub?token=%ZZ",
        )) {
            assertEquals(
                target,
                SingBoxDeepLinkResult.Error.BadEncoding,
                SingBoxDeepLinkParser.parse("hiddify://import/$target#Fleet"),
            )
        }
        assertEquals(
            SingBoxDeepLinkResult.Error.BadEncoding,
            SingBoxDeepLinkParser.parse("hiddify://import/https://[2001:db8::1]/sub#bad%ZZ"),
        )
    }

    @Test
    fun `a host from another client is unsupported`() {
        assertEquals(
            SingBoxDeepLinkResult.Error.Unsupported,
            SingBoxDeepLinkParser.parse("clash://import-remote-profile?url=${enc("https://s.example/c")}"),
        )
    }
}
