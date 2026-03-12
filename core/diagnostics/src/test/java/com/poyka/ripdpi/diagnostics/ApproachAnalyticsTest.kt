package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerMethod
import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import com.poyka.ripdpi.proto.ActivationFilter
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.NumericRange
import com.poyka.ripdpi.proto.StrategyTcpStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `deriveBypassStrategySignature preserves hostfake chain without fake tls profile`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(true)
                .setDesyncHttps(true)
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrec")
                        .setMarker("extlen")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .setMidhostMarker("midsld")
                        .setFakeHostTemplate("googlevideo.com")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker("midsld")
                        .build(),
                ).build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "4")

        assertEquals(
            "tcp: tlsrec(extlen) -> hostfake(endhost+8 midhost=midsld host=googlevideo.com) -> split(midsld)",
            signature.chainSummary,
        )
        assertEquals("fake", signature.desyncMethod)
        assertEquals("extlen", signature.tlsRecordMarker)
        assertEquals("endhost+8", signature.splitMarker)
        assertNull(signature.fakeSniMode)
        assertTrue(signature.fakeTlsMods.isEmpty())
        assertNull(signature.fakeTlsBaseMode)
        assertNull(signature.fakeTlsSize)
    }

    @Test
    fun `deriveBypassStrategySignature treats tlsrandrec as tls prelude`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(true)
                .setDesyncHttps(true)
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrandrec")
                        .setMarker("sniext+4")
                        .setFragmentCount(5)
                        .setMinFragmentSize(24)
                        .setMaxFragmentSize(48)
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker("host+1")
                        .build(),
                ).build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "6")

        assertTrue(signature.tlsRecordSplitEnabled)
        assertEquals("sniext+4", signature.tlsRecordMarker)
        assertEquals("host+1", signature.splitMarker)
        assertEquals(
            "tcp: tlsrandrec(sniext+4 count=5 min=24 max=48) -> split(host+1)",
            signature.chainSummary,
        )
    }

    @Test
    fun `deriveBypassStrategySignature includes quic fake profile when active`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .setUdpFakeCount(3)
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example.test")
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "5")

        assertEquals(QuicFakeProfileRealisticInitial, signature.quicFakeProfile)
        assertEquals("video.example.test", signature.quicFakeHost)
    }

    @Test
    fun `deriveBypassStrategySignature includes fake payload profiles when non-default`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(true)
                .setDesyncHttps(true)
                .setDesyncUdp(true)
                .setHttpFakeProfile(HttpFakeProfileCloudflareGet)
                .setTlsFakeProfile(TlsFakeProfileGoogleChrome)
                .setUdpFakeProfile(UdpFakeProfileDnsQuery)
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "9")

        assertEquals(HttpFakeProfileCloudflareGet, signature.httpFakeProfile)
        assertEquals(TlsFakeProfileGoogleChrome, signature.tlsFakeProfile)
        assertEquals(UdpFakeProfileDnsQuery, signature.udpFakeProfile)
        assertNull(signature.fakePayloadSource)
    }

    @Test
    fun `deriveBypassStrategySignature marks command-line raw fake payloads explicitly`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setEnableCmdSettings(true)
                .setCmdArgs("--fake-data :HELLO")
                .setHttpFakeProfile(HttpFakeProfileCloudflareGet)
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "10")

        assertNull(signature.httpFakeProfile)
        assertEquals("custom_raw", signature.fakePayloadSource)
    }

    @Test
    fun `deriveBypassStrategySignature omits quic fake host for compatibility profile`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .setUdpFakeCount(2)
                .setQuicFakeProfile(QuicFakeProfileCompatDefault)
                .setQuicFakeHost("video.example.test")
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "3")

        assertEquals(QuicFakeProfileCompatDefault, signature.quicFakeProfile)
        assertNull(signature.quicFakeHost)
    }

    @Test
    fun `deriveBypassStrategySignature omits quic fake profile in command line mode`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setEnableCmdSettings(true)
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .setUdpFakeCount(2)
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example.test")
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "7")

        assertNull(signature.quicFakeProfile)
        assertNull(signature.quicFakeHost)
    }

    @Test
    fun `deriveBypassStrategySignature includes activation filter ranges`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(2).setEnd(4))
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(2047))
                        .build(),
                ).build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "8")

        assertEquals("2-4", signature.activationRound)
        assertEquals("64-512", signature.activationPayloadSize)
        assertEquals("0-2047", signature.activationStreamBytes)
    }

    @Test
    fun `deriveBypassStrategySignature omits activation filter ranges in command line mode`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .setEnableCmdSettings(true)
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(2).setEnd(4))
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(2047))
                        .build(),
                ).build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "9")

        assertNull(signature.activationRound)
        assertNull(signature.activationPayloadSize)
        assertNull(signature.activationStreamBytes)
    }

    @Test
    fun `deriveBypassStrategySignature keeps raw adaptive markers and humanized chain summary`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRipdpiMode("vpn")
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker(AdaptiveMarkerMethod)
                        .build(),
                ).setSplitMarker(AdaptiveMarkerBalanced)
                .build()

        val signature = deriveBypassStrategySignature(settings = settings, routeGroup = "11")

        assertEquals(AdaptiveMarkerMethod, signature.splitMarker)
        assertEquals("tcp: split(adaptive HTTP method)", signature.chainSummary)
    }
}
