package com.poyka.ripdpi.diagnostics.rkn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RknProbeHeadersTest {
    @Test
    fun defaultUserAgentIsGenericChrome() {
        val headers = RknProbeHeaders.build(identify = false)

        assertTrue(headers.getValue("User-Agent").startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
        assertTrue(headers.getValue("User-Agent").contains("Chrome/${RknProbeHeaders.CHROME_UA_VERSION}"))
    }

    @Test
    fun defaultIncludesAllBrowserHeaders() {
        val headers = RknProbeHeaders.build(identify = false)

        assertEquals(
            listOf(
                "User-Agent",
                "Accept",
                "Accept-Language",
                "Accept-Encoding",
                "Sec-Fetch-Dest",
                "Sec-Fetch-Mode",
                "Sec-Fetch-Site",
                "Sec-Fetch-User",
                "Upgrade-Insecure-Requests",
            ),
            headers.keys.toList(),
        )
    }

    @Test
    fun identifyModeUsesRipdpiUserAgent() {
        val headers = RknProbeHeaders.build(identify = true, versionName = "1.2.3")

        assertEquals("RIPDPI/1.2.3 (+https://github.com/po4yka/RIPDPI)", headers.getValue("User-Agent"))
    }

    @Test
    fun identifyModePreservesBrowserHeaders() {
        val defaultHeaders = RknProbeHeaders.build(identify = false)
        val identifyHeaders = RknProbeHeaders.build(identify = true, versionName = "1.2.3")

        assertEquals(
            defaultHeaders.filterKeys { it != "User-Agent" },
            identifyHeaders.filterKeys { it != "User-Agent" },
        )
    }

    @Test
    fun noCookieRefererAuthorizationInDefault() {
        val headers = RknProbeHeaders.build(identify = false)

        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("Referer"))
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun chromeVersionConstantExposed() {
        assertTrue(RknProbeHeaders.CHROME_UA_VERSION.isNotBlank())
    }
}
