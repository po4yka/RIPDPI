package com.poyka.ripdpi.data.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class SupportSettingsDeepLinkParserTest {
    @Test
    fun `ripdpi support config link decodes payload json`() {
        val payload = """{"schema":1,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}"""
        val encoded = encode(payload)

        val result = SupportSettingsDeepLinkParser.parse("ripdpi://support-config?payload=$encoded")

        assertEquals(SupportSettingsDeepLinkParseResult.Success(payload), result)
    }

    @Test
    fun `https app link decodes fragment payload`() {
        val payload = """{"schema":1,"operations":[{"op":"set","path":"settings.proxy_port","value":1080}]}"""

        val result =
            SupportSettingsDeepLinkParser.parse(
                "https://po4yka.github.io/RIPDPI/support-config#${encode(payload)}",
            )

        assertEquals(SupportSettingsDeepLinkParseResult.Success(payload), result)
    }

    @Test
    fun `query payload may be percent encoded`() {
        val payload = """{"schema":1,"operations":[{"op":"set","path":"settings.ripdpi_mode","value":"vpn"}]}"""
        val encoded = URLEncoder.encode(encode(payload), "UTF-8")

        val result = SupportSettingsDeepLinkParser.parse("ripdpi://support-config?payload=$encoded")

        assertEquals(SupportSettingsDeepLinkParseResult.Success(payload), result)
    }

    @Test
    fun `unsupported host is rejected`() {
        val result = SupportSettingsDeepLinkParser.parse("ripdpi://import?payload=abc")

        assertEquals(SupportSettingsDeepLinkParseResult.Error.Unsupported, result)
    }

    @Test
    fun `bad base64 is rejected`() {
        val result = SupportSettingsDeepLinkParser.parse("ripdpi://support-config?payload=%%%")

        assertTrue(result is SupportSettingsDeepLinkParseResult.Error)
    }

    private fun encode(payload: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
}
