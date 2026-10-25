package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.stableId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class HomeStrategyLabelTest {
    private val signature =
        BypassStrategySignature(
            mode = "VPN",
            configSource = "ui",
            hostAutolearn = "enabled",
            desyncMethod = "split",
            chainSummary = "tcp: split(host+1)",
            tcpStrategyFamily = "split",
            quicStrategyFamily = "quic_disabled",
            dnsStrategyLabel = "Cloudflare DoH",
            protocolToggles = listOf("HTTP", "HTTPS", "UDP"),
            tlsRecordSplitEnabled = true,
            routeGroup = "7",
        )

    private fun BypassStrategySignature.facetMap() =
        localizedHomeStrategyFacets(ResourceStringResolver()).associate { it.label to it.value }

    @Test
    fun `each setting is a labeled row in display order`() {
        val facets = signature.localizedHomeStrategyFacets(ResourceStringResolver())
        assertEquals(
            listOf(
                "Protocols" to "HTTP, HTTPS, UDP",
                "Host learning" to "On",
                "QUIC" to "Disabled",
                "DNS" to "Cloudflare DoH",
                "TLS record split" to "On",
                "Route" to "7",
            ),
            facets.map { it.label to it.value },
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `Russian labels translate states but keep protocol tokens and stable identity`() {
        val stableId = signature.stableId()
        val facets = signature.facetMap()
        assertEquals("HTTP, HTTPS, UDP", facets["Протоколы"])
        assertEquals("Отключено", facets["QUIC"])
        assertEquals("Cloudflare DoH", facets["DNS"])
        assertEquals("7", facets["Маршрут"])
        assertEquals("Вкл.", facets["Разделение TLS-записи"])
        assertFalse(facets.keys.any { it.contains("Autolearn") })
        assertEquals(stableId, signature.stableId())
    }

    @Test
    @Config(qualifiers = "fa")
    fun `Persian labels keep provider text raw`() {
        val facets = signature.facetMap()
        assertEquals("غیرفعال", facets["QUIC"])
        assertEquals("Cloudflare DoH", facets["DNS"])
        assertEquals("7", facets["مسیر"])
    }

    @Test
    fun `host learning states and optional settings`() {
        assertEquals("Off", signature.copy(hostAutolearn = "disabled").facetMap()["Host learning"])
        assertEquals(
            "Controlled by command line",
            signature.copy(hostAutolearn = "command_line").facetMap()["Host learning"],
        )
        val facets =
            signature
                .copy(quicStrategyFamily = "quic_sni_split", tlsRecordSplitEnabled = false, routeGroup = null)
                .facetMap()
        assertEquals("QUIC SNI split", facets["QUIC"])
        assertFalse("TLS record split" in facets)
        assertFalse("Route" in facets)
    }
}
