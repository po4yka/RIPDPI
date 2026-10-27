package com.poyka.ripdpi.diagnostics.rkn

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

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
    fun gzipStubPageBodyReachesTheDetectorDecoded() =
        runTest {
            val stub = "<html><body>Доступ к информационному ресурсу ограничен</body></html>"
            val compressed =
                ByteArrayOutputStream().use { output ->
                    GZIPOutputStream(output).use { it.write(stub.encodeToByteArray()) }
                    output.toByteArray()
                }
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Content-Encoding", "gzip")
                        .body(Buffer().write(compressed))
                        .build(),
                )
                server.start()

                val result =
                    OkHttpRknHttpProbe().get(
                        url = server.url("/").toString(),
                        headers = RknProbeHeaders.build(identify = false),
                        timeoutMs = 5_000,
                    )

                assertEquals(stub, result.bodyPreview)
            }
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
