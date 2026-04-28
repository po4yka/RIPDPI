package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.EntropyModeCombined
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

@Suppress("LargeClass")
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
                    put("sessionOverrides", JsonNull)
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
    @Suppress("LongMethod")
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
                        quicBindLowPort = true,
                        quicMigrateAfterHandshake = true,
                        entropyMode = EntropyModeCombined,
                        entropyPaddingTargetPermil = 3600,
                        entropyPaddingMax = 384,
                        shannonEntropyTargetPermil = 7900,
                    ),
                parserEvasions =
                    RipDpiParserEvasionConfig(
                        hostMixedCase = true,
                        domainMixedCase = true,
                        hostRemoveSpaces = true,
                        httpMethodSpace = true,
                        httpMethodEol = true,
                        httpHostPad = true,
                        httpUnixEol = true,
                    ),
                adaptiveFallback =
                    RipDpiAdaptiveFallbackConfig(
                        enabled = true,
                        torst = true,
                        tlsErr = false,
                        httpRedirect = true,
                        connectFailure = false,
                        autoSort = false,
                        cacheTtlSeconds = 180,
                        cachePrefixV4 = 28,
                        strategyEvolution = true,
                        evolutionEpsilon = 0.2,
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
                            quicBindLowPort = true,
                            quicMigrateAfterHandshake = true,
                            entropyMode = EntropyModeCombined,
                            entropyPaddingTargetPermil = 3600,
                            entropyPaddingMax = 384,
                            shannonEntropyTargetPermil = 7900,
                        ),
                    parserEvasions =
                        parserEvasionsExpected(
                            hostMixedCase = true,
                            domainMixedCase = true,
                            hostRemoveSpaces = true,
                            httpMethodSpace = true,
                            httpMethodEol = true,
                            httpHostPad = true,
                            httpUnixEol = true,
                        ),
                    adaptiveFallback =
                        adaptiveFallbackExpected(
                            enabled = true,
                            torst = true,
                            tlsErr = false,
                            httpRedirect = true,
                            connectFailure = false,
                            autoSort = false,
                            cacheTtlSeconds = 180,
                            cachePrefixV4 = 28,
                            strategyEvolution = true,
                            evolutionEpsilon = 0.2,
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
    fun proxySeqOverlapUiPayloadMatchesSnapshot() {
        val payload =
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
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                defaultUiExpected(
                    chains =
                        chainsExpected(
                            tcpSteps =
                                listOf(
                                    tcpStepExpected(kind = "tlsrec", marker = "extlen"),
                                    tcpStepExpected(
                                        kind = "seqovl",
                                        marker = "midsld",
                                        overlapSize = 16,
                                        fakeMode = "rand",
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
    fun proxyEchMarkerUiPayloadMatchesSnapshot() {
        val payload =
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
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                defaultUiExpected(
                    chains =
                        chainsExpected(
                            tcpSteps =
                                listOf(
                                    tcpStepExpected(kind = "tlsrec", marker = "echext"),
                                    tcpStepExpected(kind = "split", marker = "echext+4"),
                                ),
                        ),
                ),
        )
    }

    @Test
    @Suppress("LongMethod")
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
        adaptiveFallback: JsonObject = adaptiveFallbackExpected(),
        quic: JsonObject = quicExpected(),
        hosts: JsonObject = hostsExpected(),
        upstreamRelay: JsonObject = relayExpected(),
        warp: JsonObject = warpExpected(),
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
            put("adaptiveFallback", adaptiveFallback)
            put("quic", quic)
            put("hosts", hosts)
            put("upstreamRelay", upstreamRelay)
            put("warp", warp)
            put("hostAutolearn", hostAutolearn)
            put("wsTunnel", wsTunnel)
            put("rootMode", JsonPrimitive(false))
            put("logContext", JsonNull)
            put("runtimeContext", JsonNull)
            put("sessionOverrides", JsonNull)
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
        authToken: String? = null,
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
            put("authToken", authToken?.let(::JsonPrimitive) ?: JsonNull)
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
        anyProtocol: Boolean = false,
        groupActivationFilter: JsonObject? = null,
        payloadDisable: List<String> = emptyList(),
        tcpRotation: JsonObject? = null,
        tcpSteps: List<JsonObject> = listOf(tcpStepExpected(kind = "split", marker = "host+1")),
        udpSteps: List<JsonObject> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("anyProtocol", JsonPrimitive(anyProtocol))
            put("groupActivationFilter", groupActivationFilter ?: JsonNull)
            put("payloadDisable", JsonArray(payloadDisable.map(::JsonPrimitive)))
            put("tcpRotation", tcpRotation ?: JsonNull)
            put("tcpSteps", JsonArray(tcpSteps))
            put("udpSteps", JsonArray(udpSteps))
        }

    private data class TcpStepFlagOverrides(
        val tcpFlagsSet: String = "",
        val tcpFlagsUnset: String = "",
        val tcpFlagsOrigSet: String = "",
        val tcpFlagsOrigUnset: String = "",
    )

    private fun tcpStepExpected(
        kind: String,
        marker: String,
        midhostMarker: String = "",
        fakeHostTemplate: String = "",
        overlapSize: Int = 0,
        fakeMode: String = "",
        fakeOrder: String = "0",
        fakeSeqMode: String = "duplicate",
        fragmentCount: Int = 0,
        minFragmentSize: Int = 0,
        maxFragmentSize: Int = 0,
        activationFilter: JsonObject? = null,
        ipv6ExtensionProfile: String = "none",
        flags: TcpStepFlagOverrides = TcpStepFlagOverrides(),
    ): JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("marker", JsonPrimitive(marker))
            put("midhostMarker", JsonPrimitive(midhostMarker))
            put("fakeHostTemplate", JsonPrimitive(fakeHostTemplate))
            put("overlapSize", JsonPrimitive(overlapSize))
            put("fakeMode", JsonPrimitive(fakeMode))
            put("fakeOrder", JsonPrimitive(fakeOrder))
            put("fakeSeqMode", JsonPrimitive(fakeSeqMode))
            put("fragmentCount", JsonPrimitive(fragmentCount))
            put("minFragmentSize", JsonPrimitive(minFragmentSize))
            put("maxFragmentSize", JsonPrimitive(maxFragmentSize))
            put("activationFilter", activationFilter ?: JsonNull)
            put("ipv6ExtensionProfile", JsonPrimitive(ipv6ExtensionProfile))
            put("tcpFlagsSet", JsonPrimitive(flags.tcpFlagsSet))
            put("tcpFlagsUnset", JsonPrimitive(flags.tcpFlagsUnset))
            put("tcpFlagsOrigSet", JsonPrimitive(flags.tcpFlagsOrigSet))
            put("tcpFlagsOrigUnset", JsonPrimitive(flags.tcpFlagsOrigUnset))
        }

    private fun udpStepExpected(
        kind: String,
        count: Int,
        splitBytes: Int = 0,
        activationFilter: JsonObject? = null,
        ipv6ExtensionProfile: String = "none",
    ): JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("count", JsonPrimitive(count))
            put("splitBytes", JsonPrimitive(splitBytes))
            put("activationFilter", activationFilter ?: JsonNull)
            put("ipv6ExtensionProfile", JsonPrimitive(ipv6ExtensionProfile))
        }

    @Suppress("LongParameterList")
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
        quicBindLowPort: Boolean = false,
        quicMigrateAfterHandshake: Boolean = false,
        entropyMode: String = "disabled",
        entropyPaddingTargetPermil: Int = 3400,
        entropyPaddingMax: Int = 256,
        shannonEntropyTargetPermil: Int = 7920,
        tlsFingerprintProfile: String = "chrome_stable",
        fakeTlsSource: String = "profile",
        fakeTlsSecondaryProfile: String = "",
        fakeTcpTimestampEnabled: Boolean = false,
        fakeTcpTimestampDeltaTicks: Int = 0,
        stripTimestamps: Boolean = false,
        ipIdMode: String = "",
        windowClamp: Int? = null,
        wsizeWindow: Int? = null,
        wsizeScale: Int? = null,
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
            put("fakeTlsSource", JsonPrimitive(fakeTlsSource))
            put("fakeTlsSecondaryProfile", JsonPrimitive(fakeTlsSecondaryProfile))
            put("fakeTcpTimestampEnabled", JsonPrimitive(fakeTcpTimestampEnabled))
            put("fakeTcpTimestampDeltaTicks", JsonPrimitive(fakeTcpTimestampDeltaTicks))
            put("oobChar", JsonPrimitive(oobChar))
            put("dropSack", JsonPrimitive(dropSack))
            put("quicBindLowPort", JsonPrimitive(quicBindLowPort))
            put("quicMigrateAfterHandshake", JsonPrimitive(quicMigrateAfterHandshake))
            put("entropyMode", JsonPrimitive(entropyMode))
            put("entropyPaddingTargetPermil", JsonPrimitive(entropyPaddingTargetPermil))
            put("entropyPaddingMax", JsonPrimitive(entropyPaddingMax))
            put("shannonEntropyTargetPermil", JsonPrimitive(shannonEntropyTargetPermil))
            put("tlsFingerprintProfile", JsonPrimitive(tlsFingerprintProfile))
            put("stripTimestamps", stripTimestamps.let(::JsonPrimitive))
            put("ipIdMode", JsonPrimitive(ipIdMode))
            put("windowClamp", windowClamp?.let(::JsonPrimitive) ?: JsonNull)
            put("wsizeWindow", wsizeWindow?.let(::JsonPrimitive) ?: JsonNull)
            put("wsizeScale", wsizeScale?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun parserEvasionsExpected(
        hostMixedCase: Boolean = false,
        domainMixedCase: Boolean = false,
        hostRemoveSpaces: Boolean = false,
        httpMethodEol: Boolean = false,
        httpMethodSpace: Boolean = false,
        httpUnixEol: Boolean = false,
        httpHostPad: Boolean = false,
        httpHostExtraSpace: Boolean = false,
        httpHostTab: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("hostMixedCase", JsonPrimitive(hostMixedCase))
            put("domainMixedCase", JsonPrimitive(domainMixedCase))
            put("hostRemoveSpaces", JsonPrimitive(hostRemoveSpaces))
            put("httpMethodEol", JsonPrimitive(httpMethodEol))
            put("httpMethodSpace", JsonPrimitive(httpMethodSpace))
            put("httpUnixEol", JsonPrimitive(httpUnixEol))
            put("httpHostPad", JsonPrimitive(httpHostPad))
            put("httpHostExtraSpace", JsonPrimitive(httpHostExtraSpace))
            put("httpHostTab", JsonPrimitive(httpHostTab))
        }

    private fun adaptiveFallbackExpected(
        enabled: Boolean = true,
        torst: Boolean = true,
        tlsErr: Boolean = true,
        httpRedirect: Boolean = true,
        connectFailure: Boolean = true,
        autoSort: Boolean = true,
        cacheTtlSeconds: Int = 90,
        cachePrefixV4: Int = 24,
        strategyEvolution: Boolean = false,
        evolutionEpsilon: Double = 0.1,
        evolutionExperimentTtlMs: Long = 30_000L,
        evolutionDecayHalfLifeMs: Long = 3_600_000L,
        evolutionCooldownAfterFailures: Int = 3,
        evolutionCooldownMs: Long = 300_000L,
    ): JsonObject =
        buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            put("torst", JsonPrimitive(torst))
            put("tlsErr", JsonPrimitive(tlsErr))
            put("httpRedirect", JsonPrimitive(httpRedirect))
            put("connectFailure", JsonPrimitive(connectFailure))
            put("autoSort", JsonPrimitive(autoSort))
            put("cacheTtlSeconds", JsonPrimitive(cacheTtlSeconds))
            put("cachePrefixV4", JsonPrimitive(cachePrefixV4))
            put("strategyEvolution", JsonPrimitive(strategyEvolution))
            put("evolutionEpsilon", JsonPrimitive(evolutionEpsilon))
            put("evolutionExperimentTtlMs", JsonPrimitive(evolutionExperimentTtlMs))
            put("evolutionDecayHalfLifeMs", JsonPrimitive(evolutionDecayHalfLifeMs))
            put("evolutionCooldownAfterFailures", JsonPrimitive(evolutionCooldownAfterFailures))
            put("evolutionCooldownMs", JsonPrimitive(evolutionCooldownMs))
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

    @Suppress("LongParameterList")
    private fun relayExpected(
        enabled: Boolean = false,
        kind: String = "off",
        profileId: String = "default",
        outboundBindIp: String = "",
        server: String = "",
        serverPort: Int = 443,
        serverName: String = "",
        realityPublicKey: String = "",
        realityShortId: String = "",
        vlessTransport: String = "reality_tcp",
        xhttpPath: String = "",
        xhttpHost: String = "",
        cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
        cloudflarePublishLocalOriginUrl: String = "",
        cloudflareCredentialsRef: String = "",
        chainEntryServer: String = "",
        chainEntryPort: Int = 443,
        chainEntryServerName: String = "",
        chainEntryPublicKey: String = "",
        chainEntryShortId: String = "",
        chainEntryProfileId: String = "",
        chainExitServer: String = "",
        chainExitPort: Int = 443,
        chainExitServerName: String = "",
        chainExitPublicKey: String = "",
        chainExitShortId: String = "",
        chainExitProfileId: String = "",
        masqueUrl: String = "",
        masqueUseHttp2Fallback: Boolean = true,
        masqueCloudflareGeohashEnabled: Boolean = false,
        tuicZeroRtt: Boolean = false,
        tuicCongestionControl: String = "bbr",
        shadowTlsInnerProfileId: String = "",
        naivePath: String = "",
        appsScriptScriptIds: List<String> = emptyList(),
        appsScriptGoogleIp: String = "",
        appsScriptFrontDomain: String = "",
        appsScriptSniHosts: List<String> = emptyList(),
        appsScriptVerifySsl: Boolean = com.poyka.ripdpi.data.DefaultRelayAppsScriptVerifySsl,
        appsScriptParallelRelay: Boolean = false,
        appsScriptDirectHosts: List<String> = emptyList(),
        localSocksHost: String = "127.0.0.1",
        localSocksPort: Int = 11980,
        udpEnabled: Boolean = false,
        tcpFallbackEnabled: Boolean = true,
        finalmask: JsonObject = finalmaskExpected(),
    ): JsonObject =
        buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            put("kind", JsonPrimitive(kind))
            put("profileId", JsonPrimitive(profileId))
            put("outboundBindIp", JsonPrimitive(outboundBindIp))
            put("server", JsonPrimitive(server))
            put("serverPort", JsonPrimitive(serverPort))
            put("serverName", JsonPrimitive(serverName))
            put("realityPublicKey", JsonPrimitive(realityPublicKey))
            put("realityShortId", JsonPrimitive(realityShortId))
            put("vlessTransport", JsonPrimitive(vlessTransport))
            put("xhttpPath", JsonPrimitive(xhttpPath))
            put("xhttpHost", JsonPrimitive(xhttpHost))
            put("cloudflareTunnelMode", JsonPrimitive(cloudflareTunnelMode))
            put("cloudflarePublishLocalOriginUrl", JsonPrimitive(cloudflarePublishLocalOriginUrl))
            put("cloudflareCredentialsRef", JsonPrimitive(cloudflareCredentialsRef))
            put("chainEntryServer", JsonPrimitive(chainEntryServer))
            put("chainEntryPort", JsonPrimitive(chainEntryPort))
            put("chainEntryServerName", JsonPrimitive(chainEntryServerName))
            put("chainEntryPublicKey", JsonPrimitive(chainEntryPublicKey))
            put("chainEntryShortId", JsonPrimitive(chainEntryShortId))
            put("chainEntryProfileId", JsonPrimitive(chainEntryProfileId))
            put("chainExitServer", JsonPrimitive(chainExitServer))
            put("chainExitPort", JsonPrimitive(chainExitPort))
            put("chainExitServerName", JsonPrimitive(chainExitServerName))
            put("chainExitPublicKey", JsonPrimitive(chainExitPublicKey))
            put("chainExitShortId", JsonPrimitive(chainExitShortId))
            put("chainExitProfileId", JsonPrimitive(chainExitProfileId))
            put("masqueUrl", JsonPrimitive(masqueUrl))
            put("masqueUseHttp2Fallback", JsonPrimitive(masqueUseHttp2Fallback))
            put("masqueCloudflareGeohashEnabled", JsonPrimitive(masqueCloudflareGeohashEnabled))
            put("tuicZeroRtt", JsonPrimitive(tuicZeroRtt))
            put("tuicCongestionControl", JsonPrimitive(tuicCongestionControl))
            put("shadowTlsInnerProfileId", JsonPrimitive(shadowTlsInnerProfileId))
            put("naivePath", JsonPrimitive(naivePath))
            put("appsScriptScriptIds", JsonArray(appsScriptScriptIds.map(::JsonPrimitive)))
            put("appsScriptGoogleIp", JsonPrimitive(appsScriptGoogleIp))
            put("appsScriptFrontDomain", JsonPrimitive(appsScriptFrontDomain))
            put("appsScriptSniHosts", JsonArray(appsScriptSniHosts.map(::JsonPrimitive)))
            put("appsScriptVerifySsl", JsonPrimitive(appsScriptVerifySsl))
            put("appsScriptParallelRelay", JsonPrimitive(appsScriptParallelRelay))
            put("appsScriptDirectHosts", JsonArray(appsScriptDirectHosts.map(::JsonPrimitive)))
            put("localSocksHost", JsonPrimitive(localSocksHost))
            put("localSocksPort", JsonPrimitive(localSocksPort))
            put("udpEnabled", JsonPrimitive(udpEnabled))
            put("tcpFallbackEnabled", JsonPrimitive(tcpFallbackEnabled))
            put("finalmask", finalmask)
        }

    @Suppress("LongParameterList")
    private fun warpExpected(
        enabled: Boolean = false,
        routeMode: String = "off",
        routeHosts: String = "",
        builtInRulesEnabled: Boolean = true,
        endpointSelectionMode: String = "automatic",
        manualEndpointHost: String = "",
        manualEndpointIpv4: String = "",
        manualEndpointIpv6: String = "",
        manualEndpointPort: Int = 2408,
        scannerEnabled: Boolean = true,
        scannerParallelism: Int = 10,
        scannerMaxRttMs: Int = 1500,
        amneziaEnabled: Boolean = false,
        amneziaJc: Int = 0,
        amneziaJmin: Int = 0,
        amneziaJmax: Int = 0,
        amneziaH1: Long = 0L,
        amneziaH2: Long = 0L,
        amneziaH3: Long = 0L,
        amneziaH4: Long = 0L,
        amneziaS1: Int = 0,
        amneziaS2: Int = 0,
        amneziaS3: Int = 0,
        amneziaS4: Int = 0,
        amneziaPreset: String = "off",
        localSocksHost: String = "127.0.0.1",
        localSocksPort: Int = 11888,
    ): JsonObject =
        buildJsonObject {
            put("enabled", JsonPrimitive(enabled))
            put("routeMode", JsonPrimitive(routeMode))
            put("routeHosts", JsonPrimitive(routeHosts))
            put("builtInRulesEnabled", JsonPrimitive(builtInRulesEnabled))
            put("endpointSelectionMode", JsonPrimitive(endpointSelectionMode))
            put(
                "manualEndpoint",
                buildJsonObject {
                    put("host", JsonPrimitive(manualEndpointHost))
                    put("ipv4", JsonPrimitive(manualEndpointIpv4))
                    put("ipv6", JsonPrimitive(manualEndpointIpv6))
                    put("port", JsonPrimitive(manualEndpointPort))
                },
            )
            put("scannerEnabled", JsonPrimitive(scannerEnabled))
            put("scannerParallelism", JsonPrimitive(scannerParallelism))
            put("scannerMaxRttMs", JsonPrimitive(scannerMaxRttMs))
            put(
                "amnezia",
                buildJsonObject {
                    put("enabled", JsonPrimitive(amneziaEnabled))
                    put("jc", JsonPrimitive(amneziaJc))
                    put("jmin", JsonPrimitive(amneziaJmin))
                    put("jmax", JsonPrimitive(amneziaJmax))
                    put("h1", JsonPrimitive(amneziaH1))
                    put("h2", JsonPrimitive(amneziaH2))
                    put("h3", JsonPrimitive(amneziaH3))
                    put("h4", JsonPrimitive(amneziaH4))
                    put("s1", JsonPrimitive(amneziaS1))
                    put("s2", JsonPrimitive(amneziaS2))
                    put("s3", JsonPrimitive(amneziaS3))
                    put("s4", JsonPrimitive(amneziaS4))
                },
            )
            put("amneziaPreset", JsonPrimitive(amneziaPreset))
            put("localSocksHost", JsonPrimitive(localSocksHost))
            put("localSocksPort", JsonPrimitive(localSocksPort))
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

    private fun finalmaskExpected(
        type: String = com.poyka.ripdpi.data.RelayFinalmaskTypeOff,
        headerHex: String = "",
        trailerHex: String = "",
        randRange: String = "",
        sudokuSeed: String = "",
        fragmentPackets: Int = 0,
        fragmentMinBytes: Int = 0,
        fragmentMaxBytes: Int = 0,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            put("headerHex", JsonPrimitive(headerHex))
            put("trailerHex", JsonPrimitive(trailerHex))
            put("randRange", JsonPrimitive(randRange))
            put("sudokuSeed", JsonPrimitive(sudokuSeed))
            put("fragmentPackets", JsonPrimitive(fragmentPackets))
            put("fragmentMinBytes", JsonPrimitive(fragmentMinBytes))
            put("fragmentMaxBytes", JsonPrimitive(fragmentMaxBytes))
        }

    private fun activationFilterExpected(
        round: JsonObject? = null,
        payloadSize: JsonObject? = null,
        streamBytes: JsonObject? = null,
        tcpHasTimestamp: Boolean? = null,
        tcpHasEch: Boolean? = null,
        tcpWindowBelow: Int? = null,
        tcpMssBelow: Int? = null,
    ): JsonObject =
        buildJsonObject {
            put("round", round ?: JsonNull)
            put("payloadSize", payloadSize ?: JsonNull)
            put("streamBytes", streamBytes ?: JsonNull)
            put("tcpHasTimestamp", tcpHasTimestamp?.let(::JsonPrimitive) ?: JsonNull)
            put("tcpHasEch", tcpHasEch?.let(::JsonPrimitive) ?: JsonNull)
            put("tcpWindowBelow", tcpWindowBelow?.let(::JsonPrimitive) ?: JsonNull)
            put("tcpMssBelow", tcpMssBelow?.let(::JsonPrimitive) ?: JsonNull)
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
