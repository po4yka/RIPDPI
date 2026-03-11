package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApproachAnalyticsTest {
    @Test
    fun `deriveBypassStrategySignature includes fake tls profile when active`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(true)
                .setDesyncHttps(true)
                .setDesyncMethod("fake")
                .setFakeTlsUseOriginal(true)
                .setFakeTlsRandomize(true)
                .setFakeTlsDupSessionId(true)
                .setFakeTlsPadEncap(true)
                .setFakeTlsSize(-24)
                .setFakeTlsSniMode(FakeTlsSniModeRandomized)
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "2")

        assertEquals("randomized", signature.fakeSniMode)
        assertNull(signature.fakeSniValue)
        assertEquals("original", signature.fakeTlsBaseMode)
        assertEquals(listOf("rand", "dupsid", "padencap"), signature.fakeTlsMods)
        assertEquals(-24, signature.fakeTlsSize)
    }

    @Test
    fun `deriveBypassStrategySignature includes fixed fake sni value when active`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(true)
                .setDesyncHttps(true)
                .setDesyncMethod("fake")
                .setFakeSni("alt.example.org")
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "1")

        assertEquals("fixed", signature.fakeSniMode)
        assertEquals("alt.example.org", signature.fakeSniValue)
    }

    @Test
    fun `deriveBypassStrategySignature omits fake tls profile when inactive`() {
        val signature =
            deriveBypassStrategySignature(
                settings =
                    AppSettings
                        .newBuilder()
                        .setRipdpiMode("vpn")
                        .setDesyncHttp(true)
                        .setDesyncHttps(true)
                        .setDesyncMethod("disorder")
                        .build(),
                routeGroup = null,
            )

        assertNull(signature.fakeSniMode)
        assertNull(signature.fakeSniValue)
        assertNull(signature.fakeTlsBaseMode)
        assertEquals(emptyList<String>(), signature.fakeTlsMods)
        assertNull(signature.fakeTlsSize)
    }
}
