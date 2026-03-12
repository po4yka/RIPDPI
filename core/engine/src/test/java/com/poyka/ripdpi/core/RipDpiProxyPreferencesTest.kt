package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerMethod
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiProxyPreferencesTest {
    @Test
    fun commandLinePreferencesEncodeSingleJsonPayload() {
        val preferences = RipDpiProxyCmdPreferences("--port 1081 --no-domain")

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("command_line", payload.string("kind"))
        val args = payload["args"] as JsonArray
        assertEquals("ciadpi", args[0].jsonPrimitive.content)
        assertTrue("--port" in args.map { it.jsonPrimitive.content })
        assertTrue("--no-domain" in args.map { it.jsonPrimitive.content })
    }

    @Test
    fun uiPreferencesEncodeCamelCaseFields() {
        val preferences =
            RipDpiProxyUIPreferences(
                ip = "127.0.0.1",
                port = 1080,
                maxConnections = 1024,
                hostsMode = RipDpiProxyUIPreferences.HostsMode.Blacklist,
                hosts = "example.com",
                fakeSni = "www.example.com",
                httpFakeProfile = HttpFakeProfileCloudflareGet,
                fakeTlsUseOriginal = true,
                fakeTlsRandomize = true,
                fakeTlsDupSessionId = true,
                fakeTlsPadEncap = true,
                fakeTlsSize = 192,
                fakeTlsSniMode = FakeTlsSniModeRandomized,
                tlsFakeProfile = TlsFakeProfileGoogleChrome,
                quicFakeProfile = QuicFakeProfileRealisticInitial,
                quicFakeHost = "video.example.test",
                udpFakeProfile = UdpFakeProfileDnsQuery,
                adaptiveFakeTtlEnabled = true,
                adaptiveFakeTtlDelta = -1,
                adaptiveFakeTtlMin = 3,
                adaptiveFakeTtlMax = 12,
                adaptiveFakeTtlFallback = 9,
                hostAutolearnEnabled = true,
                hostAutolearnPenaltyTtlHours = 12,
                hostAutolearnMaxHosts = 1024,
                hostAutolearnStorePath = "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v1.json",
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("ui", payload.string("kind"))
        assertEquals("127.0.0.1", payload.string("ip"))
        assertEquals(1024, payload.int("maxConnections"))
        assertEquals("blacklist", payload.string("hostsMode"))
        assertEquals("example.com", payload.string("hosts"))
        assertEquals("www.example.com", payload.string("fakeSni"))
        assertEquals(HttpFakeProfileCloudflareGet, payload.string("httpFakeProfile"))
        assertEquals("true", payload.string("fakeTlsUseOriginal"))
        assertEquals("true", payload.string("fakeTlsRandomize"))
        assertEquals("true", payload.string("fakeTlsDupSessionId"))
        assertEquals("true", payload.string("fakeTlsPadEncap"))
        assertEquals(192, payload.int("fakeTlsSize"))
        assertEquals(FakeTlsSniModeRandomized, payload.string("fakeTlsSniMode"))
        assertEquals(TlsFakeProfileGoogleChrome, payload.string("tlsFakeProfile"))
        assertEquals("1", payload.string("splitMarker"))
        assertEquals("disorder", payload.array("tcpChainSteps")[0].jsonObject.string("kind"))
        assertEquals("1", payload.array("tcpChainSteps")[0].jsonObject.string("marker"))
        assertEquals(0, payload.array("tcpChainSteps")[0].jsonObject.int("fragmentCount"))
        assertEquals(0, payload.array("tcpChainSteps")[0].jsonObject.int("minFragmentSize"))
        assertEquals(0, payload.array("tcpChainSteps")[0].jsonObject.int("maxFragmentSize"))
        assertEquals("route_and_cache", payload.string("quicInitialMode"))
        assertEquals("true", payload.string("quicSupportV1"))
        assertEquals("true", payload.string("quicSupportV2"))
        assertEquals(UdpFakeProfileDnsQuery, payload.string("udpFakeProfile"))
        assertEquals(QuicFakeProfileRealisticInitial, payload.string("quicFakeProfile"))
        assertEquals("video.example.test", payload.string("quicFakeHost"))
        assertEquals("true", payload.string("adaptiveFakeTtlEnabled"))
        assertEquals(9, payload.int("adaptiveFakeTtlFallback"))
        assertEquals(null, payload["adaptiveFakeTtlDelta"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, payload["adaptiveFakeTtlMin"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, payload["adaptiveFakeTtlMax"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", payload.string("hostAutolearnEnabled"))
        assertEquals(43_200, payload.int("hostAutolearnPenaltyTtlSecs"))
        assertEquals(1024, payload.int("hostAutolearnMaxHosts"))
        assertEquals(
            "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v1.json",
            payload.string("hostAutolearnStorePath"),
        )
    }

    @Test
    fun uiPreferencesDropHostsWhenModeDisabled() {
        val preferences =
            RipDpiProxyUIPreferences(
                hostsMode = RipDpiProxyUIPreferences.HostsMode.Disable,
                hosts = "example.com",
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("ui", payload.string("kind"))
        assertEquals("disable", payload.string("hostsMode"))
        assertEquals(null, payload["hosts"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun uiPreferencesEncodeQuicOverrides() {
        val preferences =
            RipDpiProxyUIPreferences(
                quicInitialMode = "route",
                quicSupportV1 = false,
                quicSupportV2 = true,
                quicFakeProfile = "compat_default",
                quicFakeHost = "Video.Example.TEST.",
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("route", payload.string("quicInitialMode"))
        assertEquals("false", payload.string("quicSupportV1"))
        assertEquals("true", payload.string("quicSupportV2"))
        assertEquals("compat_default", payload.string("quicFakeProfile"))
        assertEquals("video.example.test", payload.string("quicFakeHost"))
    }

    @Test
    fun uiPreferencesEncodeExtendedHttpParserEvasions() {
        val preferences =
            RipDpiProxyUIPreferences(
                hostMixedCase = true,
                domainMixedCase = true,
                hostRemoveSpaces = true,
                httpMethodEol = true,
                httpUnixEol = true,
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("true", payload.string("hostMixedCase"))
        assertEquals("true", payload.string("domainMixedCase"))
        assertEquals("true", payload.string("hostRemoveSpaces"))
        assertEquals("true", payload.string("httpMethodEol"))
        assertEquals("true", payload.string("httpUnixEol"))
    }

    @Test
    fun uiPreferencesEncodeHostfakeChainOptions() {
        val preferences =
            RipDpiProxyUIPreferences(
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.HostFake,
                            marker = "endhost+8",
                            midhostMarker = "midsld",
                            fakeHostTemplate = "googlevideo.com",
                        ),
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()
        val step = payload.array("tcpChainSteps")[0].jsonObject

        assertEquals("hostfake", step.string("kind"))
        assertEquals("endhost+8", step.string("marker"))
        assertEquals("midsld", step.string("midhostMarker"))
        assertEquals("googlevideo.com", step.string("fakeHostTemplate"))
        assertEquals(0, step.int("fragmentCount"))
        assertEquals(0, step.int("minFragmentSize"))
        assertEquals(0, step.int("maxFragmentSize"))
    }

    @Test
    fun decodeUiPreferencesRoundTripsHostfakeAndQuicProfile() {
        val original =
            RipDpiProxyUIPreferences(
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRec,
                            marker = "extlen",
                        ),
                        TcpChainStepModel(
                            kind = TcpChainStepKind.HostFake,
                            marker = "endhost+8",
                            midhostMarker = "midsld",
                            fakeHostTemplate = "googlevideo.com",
                        ),
                    ),
                quicFakeProfile = QuicFakeProfileRealisticInitial,
                quicFakeHost = "Video.Example.TEST.",
                httpFakeProfile = HttpFakeProfileCloudflareGet,
                tlsFakeProfile = TlsFakeProfileGoogleChrome,
                udpFakeProfile = UdpFakeProfileDnsQuery,
                hostAutolearnEnabled = true,
                hostAutolearnPenaltyTtlHours = 6,
                hostAutolearnMaxHosts = 512,
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(original.chainSummary, decoded?.chainSummary)
        assertEquals(QuicFakeProfileRealisticInitial, decoded?.quicFakeProfile)
        assertEquals("video.example.test", decoded?.quicFakeHost)
        assertEquals(HttpFakeProfileCloudflareGet, decoded?.httpFakeProfile)
        assertEquals(TlsFakeProfileGoogleChrome, decoded?.tlsFakeProfile)
        assertEquals(UdpFakeProfileDnsQuery, decoded?.udpFakeProfile)
        assertEquals(true, decoded?.hostAutolearnEnabled)
    }

    @Test
    fun decodeUiPreferencesRoundTripsAdaptiveFakeTtl() {
        val original =
            RipDpiProxyUIPreferences(
                fakeTtl = 10,
                adaptiveFakeTtlEnabled = true,
                adaptiveFakeTtlDelta = 2,
                adaptiveFakeTtlMin = 4,
                adaptiveFakeTtlMax = 16,
                adaptiveFakeTtlFallback = 11,
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(true, decoded?.adaptiveFakeTtlEnabled)
        assertEquals(2, decoded?.adaptiveFakeTtlDelta)
        assertEquals(4, decoded?.adaptiveFakeTtlMin)
        assertEquals(16, decoded?.adaptiveFakeTtlMax)
        assertEquals(11, decoded?.adaptiveFakeTtlFallback)
    }

    @Test
    fun uiPreferencesAdaptiveFakeTtlDefaultsUseUiBias() {
        val json = RipDpiProxyUIPreferences(adaptiveFakeTtlEnabled = true).toNativeConfigJson()
        val payload = json.parseJsonObject()
        val decoded = decodeRipDpiProxyUiPreferences(json)

        assertEquals("true", payload.string("adaptiveFakeTtlEnabled"))
        assertEquals(null, payload["adaptiveFakeTtlDelta"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, payload["adaptiveFakeTtlMin"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, payload["adaptiveFakeTtlMax"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, payload["adaptiveFakeTtlFallback"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, decoded?.adaptiveFakeTtlEnabled)
        assertEquals(DefaultAdaptiveFakeTtlDelta, decoded?.adaptiveFakeTtlDelta)
    }

    @Test
    fun uiPreferencesNormalizeTlsRandRecDefaults() {
        val preferences =
            RipDpiProxyUIPreferences(
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRandRec,
                            marker = "sniext+4",
                        ),
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()
        val step = payload.array("tcpChainSteps")[0].jsonObject

        assertEquals("tlsrandrec", step.string("kind"))
        assertEquals("sniext+4", step.string("marker"))
        assertEquals(4, step.int("fragmentCount"))
        assertEquals(16, step.int("minFragmentSize"))
        assertEquals(96, step.int("maxFragmentSize"))
    }

    @Test
    fun uiPreferencesEncodeActivationFilters() {
        val preferences =
            RipDpiProxyUIPreferences(
                groupActivationFilter =
                    ActivationFilterModel(
                        round = NumericRangeModel(start = 1, end = 2),
                        payloadSize = NumericRangeModel(start = 64, end = 512),
                        streamBytes = NumericRangeModel(start = 0, end = 2047),
                    ),
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.Fake,
                            marker = "host",
                            activationFilter =
                                ActivationFilterModel(
                                    round = NumericRangeModel(start = 1, end = 2),
                                    payloadSize = NumericRangeModel(start = 64, end = 512),
                                ),
                        ),
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()
        val group = payload["groupActivationFilter"]?.jsonObject
        val step = payload.array("tcpChainSteps")[0].jsonObject
        val filter = step["activationFilter"]?.jsonObject

        assertEquals("1", group?.getValue("round")?.jsonObject?.string("start"))
        assertEquals("2", group?.getValue("round")?.jsonObject?.string("end"))
        assertEquals("64", group?.getValue("payloadSize")?.jsonObject?.string("start"))
        assertEquals("2047", group?.getValue("streamBytes")?.jsonObject?.string("end"))
        assertEquals("1", filter?.getValue("round")?.jsonObject?.string("start"))
        assertEquals("512", filter?.getValue("payloadSize")?.jsonObject?.string("end"))
    }

    @Test
    fun decodeUiPreferencesRoundTripsActivationFilters() {
        val original =
            RipDpiProxyUIPreferences(
                groupActivationFilter =
                    ActivationFilterModel(
                        round = NumericRangeModel(start = 2, end = 4),
                        streamBytes = NumericRangeModel(start = 0, end = 1199),
                    ),
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.Fake,
                            marker = "host",
                            activationFilter =
                                ActivationFilterModel(
                                    round = NumericRangeModel(start = 1, end = 1),
                                    payloadSize = NumericRangeModel(start = 32, end = 256),
                                ),
                        ),
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(original.groupActivationFilter, decoded?.groupActivationFilter)
        assertEquals(original.tcpChainSteps.first().activationFilter, decoded?.tcpChainSteps?.first()?.activationFilter)
    }

    @Test
    fun decodeUiPreferencesRoundTripsExtendedHttpParserEvasions() {
        val original =
            RipDpiProxyUIPreferences(
                hostMixedCase = true,
                domainMixedCase = true,
                hostRemoveSpaces = true,
                httpMethodEol = true,
                httpUnixEol = true,
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(true, decoded?.hostMixedCase)
        assertEquals(true, decoded?.domainMixedCase)
        assertEquals(true, decoded?.hostRemoveSpaces)
        assertEquals(true, decoded?.httpMethodEol)
        assertEquals(true, decoded?.httpUnixEol)
    }

    @Test
    fun decodeUiPreferencesRoundTripsAdaptiveMarkersUnchanged() {
        val original =
            RipDpiProxyUIPreferences(
                splitMarker = AdaptiveMarkerBalanced,
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                        TcpChainStepModel(TcpChainStepKind.Split, AdaptiveMarkerMethod),
                    ),
            )

        val payload = original.toNativeConfigJson().parseJsonObject()
        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(AdaptiveMarkerBalanced, payload.string("splitMarker"))
        assertEquals(AdaptiveMarkerMethod, payload.array("tcpChainSteps")[1].jsonObject.string("marker"))
        assertEquals(AdaptiveMarkerBalanced, decoded?.splitMarker)
        assertEquals(AdaptiveMarkerMethod, decoded?.tcpChainSteps?.get(1)?.marker)
    }
}

private fun String.parseJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).content

private fun JsonObject.int(name: String): Int = (getValue(name) as JsonPrimitive).content.toInt()

private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
