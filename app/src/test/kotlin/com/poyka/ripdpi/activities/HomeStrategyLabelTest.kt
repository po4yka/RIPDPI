package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.stableId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    @Config(qualifiers = "ru")
    fun `Russian state labels preserve protocol tokens and stable identity`() {
        val stableId = signature.stableId()
        val label = signature.localizedHomeStrategyLabel(ResourceStringResolver())
        assertTrue(label.startsWith("VPN tcp: split(host+1) · HTTP/HTTPS/UDP · "))
        assertTrue(label.contains("Вкл."))
        assertTrue(label.contains("QUIC Отключено"))
        assertTrue(label.contains("DNS Cloudflare DoH"))
        assertTrue(label.contains("разделение TLS-записи"))
        assertTrue(label.endsWith("Маршрут 7"))
        assertFalse(label.contains("Autolearn"))
        assertFalse(label.contains("QUIC disabled"))
        assertEquals(stableId, signature.stableId())
    }

    @Test
    @Config(qualifiers = "fa")
    fun `Persian state labels preserve raw strategy and provider text`() {
        val label = signature.localizedHomeStrategyLabel(ResourceStringResolver())
        assertTrue(label.contains("روشن"))
        assertTrue(label.contains("QUIC غیرفعال"))
        assertTrue(label.contains("tcp: split(host+1)"))
        assertTrue(label.contains("DNS Cloudflare DoH"))
        assertTrue(label.endsWith("مسیر 7"))
    }

    @Test
    fun `enabled disabled and command line states have resource backed labels`() {
        val strings = ResourceStringResolver()
        assertTrue(signature.localizedHomeStrategyLabel(strings).contains("Host learning On"))
        assertTrue(
            signature
                .copy(
                    hostAutolearn = "disabled",
                ).localizedHomeStrategyLabel(strings)
                .contains("Host learning Off"),
        )
        assertTrue(
            signature
                .copy(
                    hostAutolearn = "command_line",
                ).localizedHomeStrategyLabel(strings)
                .contains("Controlled by command line"),
        )
        val label =
            signature
                .copy(
                    quicStrategyFamily = "quic_sni_split",
                    tlsRecordSplitEnabled = false,
                    routeGroup = null,
                ).localizedHomeStrategyLabel(strings)
        assertTrue(label.contains("QUIC QUIC SNI split"))
        assertFalse(label.contains("TLS record split"))
        assertFalse(label.contains("Route"))
    }
}
