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
    fun `a host from another client is unsupported`() {
        assertEquals(
            SingBoxDeepLinkResult.Error.Unsupported,
            SingBoxDeepLinkParser.parse("clash://import-remote-profile?url=${enc("https://s.example/c")}"),
        )
    }
}
