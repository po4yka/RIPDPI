package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RipDpiProxyPreferencesTest {
    private companion object {
        const val LegacyCommandLineProgram = "cia" + "dpi"
        const val LegacyStrategyPreset = "bye" + "dpi_default"
    }

    @Test
    fun commandLinePreferencesEncodeSingleJsonPayload() {
        val preferences = RipDpiProxyCmdPreferences("--port 1081 --no-domain")

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("command_line", payload.string("kind"))
        val args = payload.array("args")
        assertEquals("ripdpi", args[0].jsonPrimitive.content)
        assertTrue("--port" in args.map { it.jsonPrimitive.content })
        assertTrue("--no-domain" in args.map { it.jsonPrimitive.content })
    }

    @Test
    fun legacyCommandLinePayloadsAreRejectedOnRewrite() {
        val legacyJson =
            RipDpiProxyCmdPreferences("--port 1081")
                .toNativeConfigJson()
                .replace("\"ripdpi\"", "\"$LegacyCommandLineProgram\"")

        try {
            RipDpiProxyJsonPreferences(legacyJson).toNativeConfigJson()
            fail("Expected legacy command-line program alias to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun legacyUiPayloadsRejectStrategyPresetAliasOnRewrite() {
        val legacyJson =
            RipDpiProxyUIPreferences()
                .toNativeConfigJson()
                .withTopLevelString("strategyPreset", LegacyStrategyPreset)

        try {
            RipDpiProxyJsonPreferences(legacyJson).toNativeConfigJson()
            fail("Expected legacy strategy preset alias to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun uiPreferencesEncodeGroupedSections() {
        val preferences =
            RipDpiProxyUIPreferences(
                listen = RipDpiListenConfig(ip = "127.0.0.1", port = 1080, maxConnections = 1024),
                hosts = RipDpiHostsConfig(mode = RipDpiHostsConfig.Mode.Blacklist, entries = "example.com"),
                fakePackets =
                    RipDpiFakePacketConfig(
                        fakeSni = "www.example.com",
                        httpFakeProfile = HttpFakeProfileCloudflareGet,
                        fakeTlsUseOriginal = true,
                        fakeTlsRandomize = true,
                        fakeTlsDupSessionId = true,
                        fakeTlsPadEncap = true,
                        fakeTlsSize = 192,
                        fakeTlsSniMode = FakeTlsSniModeRandomized,
                        tlsFakeProfile = TlsFakeProfileGoogleChrome,
                        udpFakeProfile = UdpFakeProfileDnsQuery,
                        adaptiveFakeTtlEnabled = true,
                        adaptiveFakeTtlDelta = -1,
                        adaptiveFakeTtlMin = 3,
                        adaptiveFakeTtlMax = 12,
                        adaptiveFakeTtlFallback = 9,
                    ),
                quic =
                    RipDpiQuicConfig(
                        fakeProfile = QuicFakeProfileRealisticInitial,
                        fakeHost = "video.example.test",
                    ),
                hostAutolearn =
                    RipDpiHostAutolearnConfig(
                        enabled = true,
                        penaltyTtlHours = 12,
                        maxHosts = 1024,
                        storePath = "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v2.json",
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()
        val listen = payload.objectAt("listen")
        val protocols = payload.objectAt("protocols")
        val chains = payload.objectAt("chains")
        val fakePackets = payload.objectAt("fakePackets")
        val hosts = payload.objectAt("hosts")
        val quic = payload.objectAt("quic")
        val hostAutolearn = payload.objectAt("hostAutolearn")

        assertEquals("ui", payload.string("kind"))
        assertEquals("127.0.0.1", listen.string("ip"))
        assertEquals(1024, listen.int("maxConnections"))
        assertEquals("true", protocols.string("desyncHttp"))
        assertEquals("split", chains.array("tcpSteps")[0].jsonObject.string("kind"))
        assertEquals("blacklist", hosts.string("mode"))
        assertEquals("example.com", hosts.string("entries"))
        assertEquals("www.example.com", fakePackets.string("fakeSni"))
        assertEquals(HttpFakeProfileCloudflareGet, fakePackets.string("httpFakeProfile"))
        assertEquals("true", fakePackets.string("fakeTlsUseOriginal"))
        assertEquals(FakeTlsSniModeRandomized, fakePackets.string("fakeTlsSniMode"))
        assertEquals(TlsFakeProfileGoogleChrome, fakePackets.string("tlsFakeProfile"))
        assertEquals(UdpFakeProfileDnsQuery, fakePackets.string("udpFakeProfile"))
        assertEquals(9, fakePackets.int("adaptiveFakeTtlFallback"))
        assertEquals(QuicFakeProfileRealisticInitial, quic.string("fakeProfile"))
        assertEquals("video.example.test", quic.string("fakeHost"))
        assertEquals("true", hostAutolearn.string("enabled"))
        assertEquals(12, hostAutolearn.int("penaltyTtlHours"))
        assertEquals(1024, hostAutolearn.int("maxHosts"))
        assertEquals(
            "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v2.json",
            hostAutolearn.string("storePath"),
        )
        assertFalse("desyncMethod" in payload)
        assertFalse("splitMarker" in payload)
        assertFalse("hostsMode" in payload)
        assertFalse("udpFakeCount" in payload)
    }

    @Test
    fun decodeUiPreferencesRejectsLegacyFlatUiPayload() {
        val decoded =
            decodeRipDpiProxyUiPreferences(
                """
                {
                  "kind":"ui",
                  "ip":"127.0.0.1",
                  "port":1080,
                  "desyncMethod":"disorder"
                }
                """.trimIndent(),
            )

        assertNull(decoded)
    }

    @Test
    fun uiPreferencesDropHostsWhenModeDisabled() {
        val preferences =
            RipDpiProxyUIPreferences(
                hosts = RipDpiHostsConfig(mode = RipDpiHostsConfig.Mode.Disable, entries = "example.com"),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()
        val hosts = payload.objectAt("hosts")

        assertEquals("disable", hosts.string("mode"))
        assertEquals(null, hosts["entries"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun uiPreferencesEncodeQuicOverrides() {
        val preferences =
            RipDpiProxyUIPreferences(
                quic =
                    RipDpiQuicConfig(
                        initialMode = "route",
                        supportV1 = false,
                        supportV2 = true,
                        fakeProfile = "compat_default",
                        fakeHost = "Video.Example.TEST.",
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject().objectAt("quic")

        assertEquals("route", payload.string("initialMode"))
        assertEquals("false", payload.string("supportV1"))
        assertEquals("true", payload.string("supportV2"))
        assertEquals("compat_default", payload.string("fakeProfile"))
        assertEquals("video.example.test", payload.string("fakeHost"))
    }

    @Test
    fun uiPreferencesEncodeExtendedHttpParserEvasions() {
        val preferences =
            RipDpiProxyUIPreferences(
                parserEvasions =
                    RipDpiParserEvasionConfig(
                        hostMixedCase = true,
                        domainMixedCase = true,
                        hostRemoveSpaces = true,
                        httpMethodEol = true,
                        httpUnixEol = true,
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject().objectAt("parserEvasions")

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
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.HostFake,
                                    marker = "endhost+8",
                                    midhostMarker = "midsld",
                                    fakeHostTemplate = "googlevideo.com",
                                ),
                            ),
                    ),
            )

        val step =
            preferences
                .toNativeConfigJson()
                .parseJsonObject()
                .objectAt("chains")
                .array("tcpSteps")[0]
                .jsonObject

        assertEquals("hostfake", step.string("kind"))
        assertEquals("endhost+8", step.string("marker"))
        assertEquals("midsld", step.string("midhostMarker"))
        assertEquals("googlevideo.com", step.string("fakeHostTemplate"))
        assertEquals(0, step.int("fragmentCount"))
        assertEquals(0, step.int("minFragmentSize"))
        assertEquals(0, step.int("maxFragmentSize"))
    }

    @Test
    fun uiPreferencesEncodeAndRoundTripSeqOverlapChainOptions() {
        val original =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.TlsRec,
                                    marker = "extlen",
                                ),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.SeqOverlap,
                                    marker = "midsld",
                                    overlapSize = 16,
                                    fakeMode = "rand",
                                ),
                            ),
                    ),
            )

        val steps =
            original
                .toNativeConfigJson()
                .parseJsonObject()
                .objectAt("chains")
                .array("tcpSteps")
                .map { it.jsonObject }
        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals("tlsrec", steps[0].string("kind"))
        assertEquals("extlen", steps[0].string("marker"))
        assertEquals("seqovl", steps[1].string("kind"))
        assertEquals("midsld", steps[1].string("marker"))
        assertEquals(16, steps[1].int("overlapSize"))
        assertEquals("rand", steps[1].string("fakeMode"))
        assertEquals(original.chains.tcpSteps, decoded?.chains?.tcpSteps)
    }

    @Test
    fun uiPreferencesEncodeAndRoundTripMultidisorderChainOptions() {
        val original =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.TlsRec,
                                    marker = "extlen",
                                ),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.MultiDisorder,
                                    marker = "sniext",
                                ),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.MultiDisorder,
                                    marker = "host",
                                ),
                            ),
                    ),
            )

        val steps =
            original
                .toNativeConfigJson()
                .parseJsonObject()
                .objectAt("chains")
                .array("tcpSteps")
                .map { it.jsonObject }
        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals("tlsrec", steps[0].string("kind"))
        assertEquals("multidisorder", steps[1].string("kind"))
        assertEquals("sniext", steps[1].string("marker"))
        assertEquals("multidisorder", steps[2].string("kind"))
        assertEquals("host", steps[2].string("marker"))
        assertEquals(original.chains.tcpSteps, decoded?.chains?.tcpSteps)
    }

    @Test
    fun decodeUiPreferencesRoundTripsHostfakeAndQuicProfile() {
        val original =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
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
                    ),
                quic =
                    RipDpiQuicConfig(
                        fakeProfile = QuicFakeProfileRealisticInitial,
                        fakeHost = "Video.Example.TEST.",
                    ),
                fakePackets =
                    RipDpiFakePacketConfig(
                        httpFakeProfile = HttpFakeProfileCloudflareGet,
                        tlsFakeProfile = TlsFakeProfileGoogleChrome,
                        udpFakeProfile = UdpFakeProfileDnsQuery,
                    ),
                hostAutolearn =
                    RipDpiHostAutolearnConfig(
                        enabled = true,
                        penaltyTtlHours = 6,
                        maxHosts = 512,
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(original.chainSummary, decoded?.chainSummary)
        assertEquals(QuicFakeProfileRealisticInitial, decoded?.quic?.fakeProfile)
        assertEquals("video.example.test", decoded?.quic?.fakeHost)
        assertEquals(HttpFakeProfileCloudflareGet, decoded?.fakePackets?.httpFakeProfile)
        assertEquals(TlsFakeProfileGoogleChrome, decoded?.fakePackets?.tlsFakeProfile)
        assertEquals(UdpFakeProfileDnsQuery, decoded?.fakePackets?.udpFakeProfile)
        assertEquals(true, decoded?.hostAutolearn?.enabled)
    }

    @Test
    fun decodeUiPreferencesRoundTripsAdaptiveFakeTtl() {
        val original =
            RipDpiProxyUIPreferences(
                fakePackets =
                    RipDpiFakePacketConfig(
                        fakeTtl = 10,
                        adaptiveFakeTtlEnabled = true,
                        adaptiveFakeTtlDelta = 2,
                        adaptiveFakeTtlMin = 4,
                        adaptiveFakeTtlMax = 16,
                        adaptiveFakeTtlFallback = 11,
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(true, decoded?.fakePackets?.adaptiveFakeTtlEnabled)
        assertEquals(2, decoded?.fakePackets?.adaptiveFakeTtlDelta)
        assertEquals(4, decoded?.fakePackets?.adaptiveFakeTtlMin)
        assertEquals(16, decoded?.fakePackets?.adaptiveFakeTtlMax)
        assertEquals(11, decoded?.fakePackets?.adaptiveFakeTtlFallback)
    }

    @Test
    fun uiPreferencesAdaptiveFakeTtlDefaultsUseUiBias() {
        val json =
            RipDpiProxyUIPreferences(
                fakePackets = RipDpiFakePacketConfig(adaptiveFakeTtlEnabled = true),
            ).toNativeConfigJson()
        val payload = json.parseJsonObject().objectAt("fakePackets")
        val decoded = decodeRipDpiProxyUiPreferences(json)

        assertEquals("true", payload.string("adaptiveFakeTtlEnabled"))
        assertEquals(DefaultAdaptiveFakeTtlDelta, payload.int("adaptiveFakeTtlDelta"))
        assertEquals(3, payload.int("adaptiveFakeTtlMin"))
        assertEquals(12, payload.int("adaptiveFakeTtlMax"))
        assertEquals(8, payload.int("adaptiveFakeTtlFallback"))
        assertEquals(true, decoded?.fakePackets?.adaptiveFakeTtlEnabled)
        assertEquals(DefaultAdaptiveFakeTtlDelta, decoded?.fakePackets?.adaptiveFakeTtlDelta)
    }

    @Test
    fun uiPreferencesNormalizeTlsRandRecDefaults() {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.TlsRandRec,
                                    marker = "sniext+4",
                                ),
                            ),
                    ),
            )

        val step =
            preferences
                .toNativeConfigJson()
                .parseJsonObject()
                .objectAt("chains")
                .array("tcpSteps")[0]
                .jsonObject

        assertEquals("tlsrandrec", step.string("kind"))
        assertEquals("sniext+4", step.string("marker"))
        assertEquals(4, step.int("fragmentCount"))
        assertEquals(16, step.int("minFragmentSize"))
        assertEquals(96, step.int("maxFragmentSize"))
    }

    @Test
    fun uiPreferencesRoundTripEchMarkersWithoutSchemaChanges() {
        val original =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.TlsRec,
                                    marker = "echext",
                                ),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.Split,
                                    marker = "echext+4",
                                ),
                            ),
                    ),
            )

        val payload = original.toNativeConfigJson().parseJsonObject().objectAt("chains")
        val steps = payload.array("tcpSteps").map { it.jsonObject }
        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals("tlsrec", steps[0].string("kind"))
        assertEquals("echext", steps[0].string("marker"))
        assertEquals("split", steps[1].string("kind"))
        assertEquals("echext+4", steps[1].string("marker"))
        assertEquals(original.chains.tcpSteps, decoded?.chains?.tcpSteps)
    }

    @Test
    fun uiPreferencesEncodeActivationFilters() {
        val preferences =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        groupActivationFilter =
                            ActivationFilterModel(
                                round = NumericRangeModel(start = 1, end = 2),
                                payloadSize = NumericRangeModel(start = 64, end = 512),
                                streamBytes = NumericRangeModel(start = 0, end = 2047),
                            ),
                        tcpSteps =
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
                    ),
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject().objectAt("chains")
        val group = payload.objectAt("groupActivationFilter")
        val step = payload.array("tcpSteps")[0].jsonObject
        val filter = step.objectAt("activationFilter")

        assertEquals("1", group.objectAt("round").string("start"))
        assertEquals("2", group.objectAt("round").string("end"))
        assertEquals("64", group.objectAt("payloadSize").string("start"))
        assertEquals("2047", group.objectAt("streamBytes").string("end"))
        assertEquals("1", filter.objectAt("round").string("start"))
        assertEquals("512", filter.objectAt("payloadSize").string("end"))
    }

    @Test
    fun decodeUiPreferencesRoundTripsActivationFilters() {
        val original =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        groupActivationFilter =
                            ActivationFilterModel(
                                round = NumericRangeModel(start = 2, end = 4),
                                streamBytes = NumericRangeModel(start = 0, end = 1199),
                            ),
                        tcpSteps =
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
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(original.chains.groupActivationFilter, decoded?.chains?.groupActivationFilter)
        assertEquals(
            original.chains.tcpSteps
                .first()
                .activationFilter,
            decoded
                ?.chains
                ?.tcpSteps
                ?.first()
                ?.activationFilter,
        )
    }

    @Test
    fun decodeUiPreferencesRoundTripsExtendedHttpParserEvasions() {
        val original =
            RipDpiProxyUIPreferences(
                parserEvasions =
                    RipDpiParserEvasionConfig(
                        hostMixedCase = true,
                        domainMixedCase = true,
                        hostRemoveSpaces = true,
                        httpMethodEol = true,
                        httpUnixEol = true,
                    ),
            )

        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(true, decoded?.parserEvasions?.hostMixedCase)
        assertEquals(true, decoded?.parserEvasions?.domainMixedCase)
        assertEquals(true, decoded?.parserEvasions?.hostRemoveSpaces)
        assertEquals(true, decoded?.parserEvasions?.httpMethodEol)
        assertEquals(true, decoded?.parserEvasions?.httpUnixEol)
    }

    @Test
    fun decodeUiPreferencesRoundTripsAdaptiveMarkersUnchanged() {
        val original =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                                TcpChainStepModel(TcpChainStepKind.Split, AdaptiveMarkerMethod),
                            ),
                    ),
            )

        val payload = original.toNativeConfigJson().parseJsonObject().objectAt("chains")
        val decoded = decodeRipDpiProxyUiPreferences(original.toNativeConfigJson())

        assertEquals(AdaptiveMarkerMethod, payload.array("tcpSteps")[1].jsonObject.string("marker"))
        assertEquals(
            AdaptiveMarkerMethod,
            decoded
                ?.chains
                ?.tcpSteps
                ?.get(1)
                ?.marker,
        )
    }
}

private fun String.parseJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun String.withTopLevelString(
    name: String,
    value: String,
): String =
    JsonObject(
        parseJsonObject()
            .toMutableMap()
            .apply { put(name, JsonPrimitive(value)) },
    ).toString()

private fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).content

private fun JsonObject.int(name: String): Int = (getValue(name) as JsonPrimitive).content.toInt()

private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
