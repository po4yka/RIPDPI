package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonElement.Companion.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeConfigContractSnapshotTest {
    @Test
    fun proxyCommandLinePayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyCmdPreferences(
                arrayOf(
                    "ripdpi",
                    "--ip",
                    "127.0.0.1",
                    "--port",
                    "2080",
                    "--split",
                    "host+1",
                    "--fake",
                    "-1",
                ),
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                buildJsonObject {
                    put("kind", JsonPrimitive("command_line"))
                    put(
                        "args",
                        buildJsonArray {
                            add(JsonPrimitive("ripdpi"))
                            add(JsonPrimitive("--ip"))
                            add(JsonPrimitive("127.0.0.1"))
                            add(JsonPrimitive("--port"))
                            add(JsonPrimitive("2080"))
                            add(JsonPrimitive("--split"))
                            add(JsonPrimitive("host+1"))
                            add(JsonPrimitive("--fake"))
                            add(JsonPrimitive("-1"))
                        },
                    )
                    put("hostAutolearnStorePath", JsonNull)
                    put("logContext", JsonNull)
                    put("runtimeContext", JsonNull)
                },
        )
    }

    @Test
    fun proxyDefaultUiPayloadMatchesSnapshot() {
        val payload = RipDpiProxyUIPreferences().toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson = defaultUiExpected(),
        )
    }

    @Test
    fun proxyCustomUiPayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyUIPreferences(
                listen =
                    RipDpiListenConfig(
                        ip = "0.0.0.0",
                        port = 2080,
                        maxConnections = 1024,
                        bufferSize = 32768,
                        defaultTtl = 64,
                        customTtl = true,
                        tcpFastOpen = true,
                    ),
                protocols =
                    RipDpiProtocolConfig(
                        resolveDomains = false,
                        desyncHttp = false,
                        desyncHttps = true,
                        desyncUdp = true,
                    ),
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.TlsRec,
                                    marker = "sniext+3",
                                ),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.Fake,
                                    marker = "host+2",
                                ),
                            ),
                        udpSteps =
                            listOf(
                                UdpChainStepModel(
                                    kind = UdpChainStepKind.FakeBurst,
                                    count = 4,
                                ),
                            ),
                    ),
                fakePackets =
                    RipDpiFakePacketConfig(
                        fakeTtl = 9,
                        fakeSni = "alt.example.org",
                        httpFakeProfile = HttpFakeProfileCloudflareGet,
                        fakeTlsUseOriginal = true,
                        fakeTlsRandomize = true,
                        fakeTlsDupSessionId = true,
                        fakeTlsPadEncap = true,
                        fakeTlsSize = -24,
                        fakeTlsSniMode = FakeTlsSniModeRandomized,
                        tlsFakeProfile = TlsFakeProfileGoogleChrome,
                        udpFakeProfile = UdpFakeProfileDnsQuery,
                        fakeOffsetMarker = "endhost-1",
                        oobChar = 'Z',
                        dropSack = true,
                    ),
                parserEvasions =
                    RipDpiParserEvasionConfig(
                        hostMixedCase = true,
                        domainMixedCase = true,
                        hostRemoveSpaces = true,
                        httpMethodEol = true,
                        httpUnixEol = true,
                    ),
                quic =
                    RipDpiQuicConfig(
                        initialMode = "route",
                        supportV1 = false,
                        supportV2 = true,
                        fakeProfile = QuicFakeProfileRealisticInitial,
                        fakeHost = "video.example.test",
                    ),
                hosts =
                    RipDpiHostsConfig(
                        mode = RipDpiHostsConfig.Mode.Whitelist,
                        entries = "example.org",
                    ),
                hostAutolearn =
                    RipDpiHostAutolearnConfig(
                        enabled = true,
                        penaltyTtlHours = 12,
                        maxHosts = 2048,
                        storePath = "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v2.json",
                    ),
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                defaultUiExpected(
                    listen =
                        listenExpected(
                            ip = "0.0.0.0",
                            port = 2080,
                            maxConnections = 1024,
                            bufferSize = 32768,
                            tcpFastOpen = true,
                            defaultTtl = 64,
                            customTtl = true,
                        ),
                    protocols =
                        protocolsExpected(
                            resolveDomains = false,
                            desyncHttp = false,
                            desyncHttps = true,
                            desyncUdp = true,
                        ),
                    chains =
                        chainsExpected(
                            tcpSteps =
                                listOf(
                                    tcpStepExpected(kind = "tlsrec", marker = "sniext+3"),
                                    tcpStepExpected(kind = "fake", marker = "host+2"),
                                ),
                            udpSteps =
                                listOf(
                                    udpStepExpected(kind = "fake_burst", count = 4),
                                ),
                        ),
                    fakePackets =
                        fakePacketsExpected(
                            fakeTtl = 9,
                            fakeSni = "alt.example.org",
                            httpFakeProfile = HttpFakeProfileCloudflareGet,
                            fakeTlsUseOriginal = true,
                            fakeTlsRandomize = true,
                            fakeTlsDupSessionId = true,
                            fakeTlsPadEncap = true,
                            fakeTlsSize = -24,
                            fakeTlsSniMode = FakeTlsSniModeRandomized,
                            tlsFakeProfile = TlsFakeProfileGoogleChrome,
                            udpFakeProfile = UdpFakeProfileDnsQuery,
                            fakeOffsetMarker = "endhost-1",
                            oobChar = 'Z'.code,
                            dropSack = true,
                        ),
                    parserEvasions =
                        parserEvasionsExpected(
                            hostMixedCase = true,
                            domainMixedCase = true,
                            hostRemoveSpaces = true,
                            httpMethodEol = true,
                            httpUnixEol = true,
                        ),
                    quic =
                        quicExpected(
                            initialMode = "route",
                            supportV1 = false,
                            supportV2 = true,
                            fakeProfile = QuicFakeProfileRealisticInitial,
                            fakeHost = "video.example.test",
                        ),
                    hosts =
                        hostsExpected(
                            mode = RipDpiHostsConfig.Mode.Whitelist.wireName,
                            entries = "example.org",
                        ),
                    hostAutolearn =
                        hostAutolearnExpected(
                            enabled = true,
                            penaltyTtlHours = 12,
                            maxHosts = 2048,
                            storePath = "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v2.json",
                        ),
                ),
        )
    }

    @Test
    fun proxyHostfakeUiPayloadMatchesSnapshot() {
        val payload =
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
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                defaultUiExpected(
                    chains =
                        chainsExpected(
                            tcpSteps =
                                listOf(
                                    tcpStepExpected(
                                        kind = "hostfake",
                                        marker = "endhost+8",
                                        midhostMarker = "midsld",
                                        fakeHostTemplate = "googlevideo.com",
                                    ),
                                ),
                        ),
                ),
        )
    }

    @Test
    fun proxyActivationFilterUiPayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        groupActivationFilter =
                            ActivationFilterModel(
                                round = NumericRangeModel(start = 2, end = 4),
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
                                            payloadSize = NumericRangeModel(start = 32, end = 256),
                                        ),
                                ),
                            ),
                    ),
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                defaultUiExpected(
                    chains =
                        chainsExpected(
                            groupActivationFilter =
                                activationFilterExpected(
                                    round = numericRangeExpected(start = 2, end = 4),
                                    payloadSize = numericRangeExpected(start = 64, end = 512),
                                    streamBytes = numericRangeExpected(start = 0, end = 2047),
                                ),
                            tcpSteps =
                                listOf(
                                    tcpStepExpected(
                                        kind = "fake",
                                        marker = "host",
                                        activationFilter =
                                            activationFilterExpected(
                                                round = numericRangeExpected(start = 1, end = 2),
                                                payloadSize = numericRangeExpected(start = 32, end = 256),
                                            ),
                                    ),
                                ),
                        ),
                ),
        )
    }

    @Test
    fun proxyTlsRandRecUiPayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyUIPreferences(
                chains =
                    RipDpiChainConfig(
                        tcpSteps =
                            listOf(
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.TlsRandRec,
                                    marker = "sniext+4",
                                    fragmentCount = 5,
                                    minFragmentSize = 24,
                                    maxFragmentSize = 48,
                                ),
                            ),
                    ),
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                defaultUiExpected(
                    chains =
                        chainsExpected(
                            tcpSteps =
                                listOf(
                                    tcpStepExpected(
                                        kind = "tlsrandrec",
                                        marker = "sniext+4",
                                        fragmentCount = 5,
                                        minFragmentSize = 24,
                                        maxFragmentSize = 48,
                                    ),
                                ),
                        ),
                ),
        )
    }

    @Test
    fun tunnelPayloadMatchesSnapshot() {
        val payload =
            Json.encodeToString(
                Tun2SocksConfig(
                    tunnelName = "tun-app",
                    tunnelMtu = 9000,
                    multiQueue = true,
                    tunnelIpv4 = "10.0.0.2",
                    tunnelIpv6 = "fd00::2",
                    socks5Address = "127.0.0.2",
                    socks5Port = 2080,
                    socks5Udp = "udp-relay",
                    socks5UdpAddress = "127.0.0.3",
                    socks5Pipeline = true,
                    username = "user",
                    password = "secret",
                    mapdnsAddress = "10.0.0.53",
                    mapdnsPort = 5353,
                    mapdnsNetwork = "10.0.0.0",
                    mapdnsNetmask = "255.255.255.0",
                    mapdnsCacheSize = 4096,
                    encryptedDnsResolverId = "cloudflare",
                    encryptedDnsProtocol = "doh",
                    encryptedDnsHost = "cloudflare-dns.com",
                    encryptedDnsPort = 443,
                    encryptedDnsTlsServerName = "cloudflare-dns.com",
                    encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
                    dnsQueryTimeoutMs = 4000,
                    taskStackSize = 65536,
                    tcpBufferSize = 32768,
                    udpRecvBufferSize = 16384,
                    udpCopyBufferNums = 8,
                    maxSessionCount = 2048,
                    connectTimeoutMs = 3000,
                    tcpReadWriteTimeoutMs = 6000,
                    udpReadWriteTimeoutMs = 7000,
                    logLevel = "info",
                    limitNofile = 4096,
                ),
            )

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                """
                {
                  "tunnelName": "tun-app",
                  "tunnelMtu": 9000,
                  "multiQueue": true,
                  "tunnelIpv4": "10.0.0.2",
                  "tunnelIpv6": "fd00::2",
                  "socks5Address": "127.0.0.2",
                  "socks5Port": 2080,
                  "socks5Udp": "udp-relay",
                  "socks5UdpAddress": "127.0.0.3",
                  "socks5Pipeline": true,
                  "username": "user",
                  "password": "secret",
                  "mapdnsAddress": "10.0.0.53",
                  "mapdnsPort": 5353,
                  "mapdnsNetwork": "10.0.0.0",
                  "mapdnsNetmask": "255.255.255.0",
                  "mapdnsCacheSize": 4096,
                  "encryptedDnsResolverId": "cloudflare",
                  "encryptedDnsProtocol": "doh",
                  "encryptedDnsHost": "cloudflare-dns.com",
                  "encryptedDnsPort": 443,
                  "encryptedDnsTlsServerName": "cloudflare-dns.com",
                  "encryptedDnsBootstrapIps": [
                    "1.1.1.1",
                    "1.0.0.1"
                  ],
                  "encryptedDnsDohUrl": "https://cloudflare-dns.com/dns-query",
                  "dnsQueryTimeoutMs": 4000,
                  "taskStackSize": 65536,
                  "tcpBufferSize": 32768,
                  "udpRecvBufferSize": 16384,
                  "udpCopyBufferNums": 8,
                  "maxSessionCount": 2048,
                  "connectTimeoutMs": 3000,
                  "tcpReadWriteTimeoutMs": 6000,
                  "udpReadWriteTimeoutMs": 7000,
                  "logLevel": "info",
                  "limitNofile": 4096
                }
                """,
        )
    }

    private fun defaultUiExpected(
        listen: JsonObject = listenExpected(),
        protocols: JsonObject = protocolsExpected(),
        chains: JsonObject = chainsExpected(),
        fakePackets: JsonObject = fakePacketsExpected(),
        parserEvasions: JsonObject = parserEvasionsExpected(),
        quic: JsonObject = quicExpected(),
        hosts: JsonObject = hostsExpected(),
        hostAutolearn: JsonObject = hostAutolearnExpected(),
        wsTunnel: JsonObject = wsTunnelExpected(),
    ): JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive("ui"))
            put("strategyPreset", JsonNull)
            put("listen", listen)
            put("protocols", protocols)
            put("chains", chains)
            put("fakePackets", fakePackets)
            put("parserEvasions", parserEvasions)
            put("quic", quic)
            put("hosts", hosts)
            put("hostAutolearn", hostAutolearn)
            put("wsTunnel", wsTunnel)
            put("logContext", JsonNull)
            put("runtimeContext", JsonNull)
        }

    private fun listenExpected(
        ip: String = "127.0.0.1",
        port: Int = 1080,
        maxConnections: Int = 512,
        bufferSize: Int = 16384,
        tcpFastOpen: Boolean = false,
        defaultTtl: Int = 0,
        customTtl: Boolean = false,
        freezeDetectionEnabled: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("ip", JsonPrimitive(ip))
            put("port", JsonPrimitive(port))
            put("maxConnections", JsonPrimitive(maxConnections))
            put("bufferSize", JsonPrimitive(bufferSize))
            put("tcpFastOpen", JsonPrimitive(tcpFastOpen))
            put("defaultTtl", JsonPrimitive(defaultTtl))
            put("customTtl", JsonPrimitive(customTtl))
            put("freezeDetectionEnabled", JsonPrimitive(freezeDetectionEnabled))
        }

    private fun protocolsExpected(
        resolveDomains: Boolean = true,
        desyncHttp: Boolean = true,
        desyncHttps: Boolean = true,
        desyncUdp: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("resolveDomains", JsonPrimitive(resolveDomains))
            put("desyncHttp", JsonPrimitive(desyncHttp))
            put("desyncHttps", JsonPrimitive(desyncHttps))
            put("desyncUdp", JsonPrimitive(desyncUdp))
        }

    private fun chainsExpected(
        groupActivationFilter: JsonObject? = null,
        tcpSteps: List<JsonObject> = listOf(tcpStepExpected(kind = "split", marker = "host+1")),
        udpSteps: List<JsonObject> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("groupActivationFilter", groupActivationFilter ?: JsonNull)
            put("tcpSteps", JsonArray(tcpSteps))
            put("udpSteps", JsonArray(udpSteps))
        }

    private fun tcpStepExpected(
        kind: String,
        marker: String,
        midhostMarker: String = "",
        fakeHostTemplate: String = "",
        fragmentCount: Int = 0,
        minFragmentSize: Int = 0,
        maxFragmentSize: Int = 0,
        activationFilter: JsonObject? = null,
    ): JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("marker", JsonPrimitive(marker))
            put("midhostMarker", JsonPrimitive(midhostMarker))
            put("fakeHostTemplate", JsonPrimitive(fakeHostTemplate))
            put("fragmentCount", JsonPrimitive(fragmentCount))
            put("minFragmentSize", JsonPrimitive(minFragmentSize))
            put("maxFragmentSize", JsonPrimitive(maxFragmentSize))
            put("activationFilter", activationFilter ?: JsonNull)
        }

    private fun udpStepExpected(
        kind: String,
        count: Int,
        activationFilter: JsonObject? = null,
    ): JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("count", JsonPrimitive(count))
            put("activationFilter", activationFilter ?: JsonNull)
        }

    private fun fakePacketsExpected(
        fakeTtl: Int = 8,
        adaptiveFakeTtlEnabled: Boolean = false,
        adaptiveFakeTtlDelta: Int = -1,
        adaptiveFakeTtlMin: Int = 3,
        adaptiveFakeTtlMax: Int = 12,
        adaptiveFakeTtlFallback: Int = 8,
        fakeSni: String = "www.iana.org",
        httpFakeProfile: String = "compat_default",
        fakeTlsUseOriginal: Boolean = false,
        fakeTlsRandomize: Boolean = false,
        fakeTlsDupSessionId: Boolean = false,
        fakeTlsPadEncap: Boolean = false,
        fakeTlsSize: Int = 0,
        fakeTlsSniMode: String = "fixed",
        tlsFakeProfile: String = "compat_default",
        udpFakeProfile: String = "compat_default",
        fakeOffsetMarker: String = "0",
        oobChar: Int = 'a'.code,
        dropSack: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("fakeTtl", JsonPrimitive(fakeTtl))
            put("adaptiveFakeTtlEnabled", JsonPrimitive(adaptiveFakeTtlEnabled))
            put("adaptiveFakeTtlDelta", JsonPrimitive(adaptiveFakeTtlDelta))
            put("adaptiveFakeTtlMin", JsonPrimitive(adaptiveFakeTtlMin))
            put("adaptiveFakeTtlMax", JsonPrimitive(adaptiveFakeTtlMax))
            put("adaptiveFakeTtlFallback", JsonPrimitive(adaptiveFakeTtlFallback))
            put("fakeSni", JsonPrimitive(fakeSni))
            put("httpFakeProfile", JsonPrimitive(httpFakeProfile))
            put("fakeTlsUseOriginal", JsonPrimitive(fakeTlsUseOriginal))
            put("fakeTlsRandomize", JsonPrimitive(fakeTlsRandomize))
            put("fakeTlsDupSessionId", JsonPrimitive(fakeTlsDupSessionId))
            put("fakeTlsPadEncap", JsonPrimitive(fakeTlsPadEncap))
            put("fakeTlsSize", JsonPrimitive(fakeTlsSize))
            put("fakeTlsSniMode", JsonPrimitive(fakeTlsSniMode))
            put("tlsFakeProfile", JsonPrimitive(tlsFakeProfile))
            put("udpFakeProfile", JsonPrimitive(udpFakeProfile))
            put("fakeOffsetMarker", JsonPrimitive(fakeOffsetMarker))
            put("oobChar", JsonPrimitive(oobChar))
            put("dropSack", JsonPrimitive(dropSack))
        }

    private fun parserEvasionsExpected(
        hostMixedCase: Boolean = false,
        domainMixedCase: Boolean = false,
        hostRemoveSpaces: Boolean = false,
        httpMethodEol: Boolean = false,
        httpUnixEol: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("hostMixedCase", JsonPrimitive(hostMixedCase))
            put("domainMixedCase", JsonPrimitive(domainMixedCase))
            put("hostRemoveSpaces", JsonPrimitive(hostRemoveSpaces))
            put("httpMethodEol", JsonPrimitive(httpMethodEol))
            put("httpUnixEol", JsonPrimitive(httpUnixEol))
        }

    private fun quicExpected(
        initialMode: String = "route_and_cache",
        supportV1: Boolean = true,
        supportV2: Boolean = true,
        fakeProfile: String = "disabled",
        fakeHost: String = "",
    ): JsonObject =
        buildJsonObject {
            put("initialMode", JsonPrimitive(initialMode))
            put("supportV1", JsonPrimitive(supportV1))
            put("supportV2", JsonPrimitive(supportV2))
            put("fakeProfile", JsonPrimitive(fakeProfile))
            put("fakeHost", JsonPrimitive(fakeHost))
        }

    private fun hostsExpected(
        mode: String = RipDpiHostsConfig.Mode.Disable.wireName,
        entries: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("mode", JsonPrimitive(mode))
            put("entries", entries?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun hostAutolearnExpected(
        enabled: Boolean = false,
        penaltyTtlHours: Int = 6,
        maxHosts: Int = 512,
        storePath: String? = null,
        networkScopeKey: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            put("penaltyTtlHours", JsonPrimitive(penaltyTtlHours))
            put("maxHosts", JsonPrimitive(maxHosts))
            put("storePath", storePath?.let(::JsonPrimitive) ?: JsonNull)
            put("networkScopeKey", networkScopeKey?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun wsTunnelExpected(
        enabled: Boolean = false,
        mode: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            put("mode", mode?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun activationFilterExpected(
        round: JsonObject? = null,
        payloadSize: JsonObject? = null,
        streamBytes: JsonObject? = null,
    ): JsonObject =
        buildJsonObject {
            put("round", round ?: JsonNull)
            put("payloadSize", payloadSize ?: JsonNull)
            put("streamBytes", streamBytes ?: JsonNull)
        }

    private fun numericRangeExpected(
        start: Long? = null,
        end: Long? = null,
    ): JsonObject =
        buildJsonObject {
            put("start", start?.let(::JsonPrimitive) ?: JsonNull)
            put("end", end?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun assertJsonSnapshot(
        actualJson: String,
        expectedJson: JsonElement,
    ) {
        assertEquals(
            canonicalJson(expectedJson),
            canonicalJson(actualJson),
        )
    }

    private fun assertJsonSnapshot(
        actualJson: String,
        expectedJson: String,
    ) {
        assertEquals(
            canonicalJson(expectedJson),
            canonicalJson(actualJson),
        )
    }

    private fun canonicalJson(value: String): String = canonicalJson(Json.parseToJsonElement(value))

    private fun canonicalJson(value: JsonElement): String =
        snapshotJson.encodeToString(
            serializer(),
            value.sortedKeys(),
        )

    private fun JsonElement.sortedKeys(): JsonElement =
        when (this) {
            is JsonArray -> {
                JsonArray(map { it.sortedKeys() })
            }

            is JsonObject -> {
                JsonObject(
                    entries
                        .sortedBy { it.key }
                        .associate { (key, currentValue) -> key to currentValue.sortedKeys() },
                )
            }

            else -> {
                this
            }
        }

    private companion object {
        val snapshotJson =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
    }
}
