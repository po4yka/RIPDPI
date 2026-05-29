package com.poyka.ripdpi.proxyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Pure-logic tests for [RipDpiImportDeepLinkParser]: the `ripdpi://import?sub=<enc>` and
 * `ripdpi://import?url=<enc>` one-tap import deep-link convention.
 *
 * - `sub=` selects [RipDpiImportDeepLinkResult.Success.Kind.Subscription] (enroll).
 * - `url=` selects [RipDpiImportDeepLinkResult.Success.Kind.OneShot] (fetch once, no enrollment).
 * - Non-https values, malformed percent-encoding, and missing parameters are rejected with
 *   typed errors; the parser never throws.
 */
class RipDpiImportDeepLinkParserTest {
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // --- sub= (subscription enroll) ---

    @Test
    fun `sub parameter decodes to subscription URL and selects Subscription kind`() {
        val link = "ripdpi://import?sub=${enc("https://host.example/sub/tok")}"

        val result = RipDpiImportDeepLinkParser.parse(link)

        assertTrue(result is RipDpiImportDeepLinkResult.Success)
        result as RipDpiImportDeepLinkResult.Success
        assertEquals("https://host.example/sub/tok", result.targetUrl)
        assertEquals(RipDpiImportDeepLinkResult.Success.Kind.Subscription, result.kind)
    }

    @Test
    fun `sub parameter with complex path and query is decoded correctly`() {
        val subscriptionUrl = "https://host.example/sub/tok?v=2&region=eu"
        val link = "ripdpi://import?sub=${enc(subscriptionUrl)}"

        val result = RipDpiImportDeepLinkParser.parse(link)

        assertTrue(result is RipDpiImportDeepLinkResult.Success)
        assertEquals(subscriptionUrl, (result as RipDpiImportDeepLinkResult.Success).targetUrl)
        assertEquals(RipDpiImportDeepLinkResult.Success.Kind.Subscription, result.kind)
    }

    // --- url= (one-shot remote import) ---

    @Test
    fun `url parameter decodes to import URL and selects OneShot kind`() {
        val link = "ripdpi://import?url=${enc("https://host.example/bundle.json")}"

        val result = RipDpiImportDeepLinkParser.parse(link)

        assertTrue(result is RipDpiImportDeepLinkResult.Success)
        result as RipDpiImportDeepLinkResult.Success
        assertEquals("https://host.example/bundle.json", result.targetUrl)
        assertEquals(RipDpiImportDeepLinkResult.Success.Kind.OneShot, result.kind)
    }

    @Test
    fun `url parameter with complex path is decoded correctly`() {
        val importUrl = "https://host.example/configs/vless.json?token=abc"
        val link = "ripdpi://import?url=${enc(importUrl)}"

        val result = RipDpiImportDeepLinkParser.parse(link)

        assertTrue(result is RipDpiImportDeepLinkResult.Success)
        assertEquals(importUrl, (result as RipDpiImportDeepLinkResult.Success).targetUrl)
    }

    // --- https enforcement ---

    @Test
    fun `non-https sub value is rejected with NonHttps error`() {
        val link = "ripdpi://import?sub=${enc("http://host.example/sub/tok")}"

        assertEquals(RipDpiImportDeepLinkResult.Error.NonHttps, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `non-https url value is rejected with NonHttps error`() {
        val link = "ripdpi://import?url=${enc("http://host.example/bundle.json")}"

        assertEquals(RipDpiImportDeepLinkResult.Error.NonHttps, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `ftp sub value is rejected with NonHttps error`() {
        val link = "ripdpi://import?sub=${enc("ftp://host.example/file")}"

        assertEquals(RipDpiImportDeepLinkResult.Error.NonHttps, RipDpiImportDeepLinkParser.parse(link))
    }

    // --- missing / empty parameters ---

    @Test
    fun `missing both sub and url yields MissingTarget error`() {
        val link = "ripdpi://import"

        assertEquals(RipDpiImportDeepLinkResult.Error.MissingTarget, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `empty sub value yields MissingTarget error`() {
        val link = "ripdpi://import?sub="

        assertEquals(RipDpiImportDeepLinkResult.Error.MissingTarget, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `empty url value yields MissingTarget error`() {
        val link = "ripdpi://import?url="

        assertEquals(RipDpiImportDeepLinkResult.Error.MissingTarget, RipDpiImportDeepLinkParser.parse(link))
    }

    // --- malformed percent-encoding ---

    @Test
    fun `malformed percent-encoding in sub yields BadEncoding error, never throws`() {
        val link = "ripdpi://import?sub=https%ZZbad"

        assertEquals(RipDpiImportDeepLinkResult.Error.BadEncoding, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `malformed percent-encoding in url yields BadEncoding error, never throws`() {
        val link = "ripdpi://import?url=https%ZZbad"

        assertEquals(RipDpiImportDeepLinkResult.Error.BadEncoding, RipDpiImportDeepLinkParser.parse(link))
    }

    // --- wrong scheme / host ---

    @Test
    fun `wrong host yields Unsupported error`() {
        val link = "ripdpi://import-remote-profile?sub=${enc("https://host.example/sub/tok")}"

        assertEquals(RipDpiImportDeepLinkResult.Error.Unsupported, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `wrong scheme yields Unsupported error`() {
        val link = "singbox://import?sub=${enc("https://host.example/sub/tok")}"

        assertEquals(RipDpiImportDeepLinkResult.Error.Unsupported, RipDpiImportDeepLinkParser.parse(link))
    }

    @Test
    fun `vless uri yields Unsupported error`() {
        assertEquals(
            RipDpiImportDeepLinkResult.Error.Unsupported,
            RipDpiImportDeepLinkParser.parse("vless://uuid@example.com:443"),
        )
    }

    @Test
    fun `blank input yields Unsupported error, never throws`() {
        assertEquals(RipDpiImportDeepLinkResult.Error.Unsupported, RipDpiImportDeepLinkParser.parse("  "))
    }

    // --- sub= takes precedence when both parameters present ---

    @Test
    fun `sub takes precedence over url when both are present`() {
        val link =
            "ripdpi://import?sub=${enc("https://host.example/sub/tok")}" +
                "&url=${enc("https://host.example/bundle.json")}"

        val result = RipDpiImportDeepLinkParser.parse(link)

        assertTrue(result is RipDpiImportDeepLinkResult.Success)
        result as RipDpiImportDeepLinkResult.Success
        assertEquals(RipDpiImportDeepLinkResult.Success.Kind.Subscription, result.kind)
        assertEquals("https://host.example/sub/tok", result.targetUrl)
    }

    // --- extra parameters are ignored ---

    @Test
    fun `unknown extra parameters are ignored`() {
        val link =
            "ripdpi://import?sub=${enc("https://host.example/sub/tok")}&name=ignored&v=2"

        val result = RipDpiImportDeepLinkParser.parse(link)

        assertTrue(result is RipDpiImportDeepLinkResult.Success)
        assertFalse((result as RipDpiImportDeepLinkResult.Success).targetUrl.contains("ignored"))
    }
}
