package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonElement.Companion.serializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeConfigContractSnapshotTest {
    @Test
    fun proxyCommandLinePayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyCmdPreferences(
                arrayOf(
                    "ciadpi",
                    "--ip",
                    "127.0.0.1",
                    "--port",
                    "2080",
                    "--split",
                    "1+s",
                    "--fake",
                    "-1",
                ),
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                """
                {
                  "kind": "command_line",
                  "args": [
                    "ciadpi",
                    "--ip",
                    "127.0.0.1",
                    "--port",
                    "2080",
                    "--split",
                    "1+s",
                    "--fake",
                    "-1"
                  ]
                }
                """,
        )
    }

    @Test
    fun proxyDefaultUiPayloadMatchesSnapshot() {
        val payload = RipDpiProxyUIPreferences().toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                """
                {
                  "kind": "ui",
                  "ip": "127.0.0.1",
                  "port": 1080,
                  "maxConnections": 512,
                  "bufferSize": 16384,
                  "defaultTtl": 0,
                  "customTtl": false,
                  "noDomain": false,
                  "desyncHttp": true,
                  "desyncHttps": true,
                  "desyncUdp": false,
                  "desyncMethod": "disorder",
                  "splitMarker": "1",
                  "tcpChainSteps": [
                    {
                      "kind": "disorder",
                      "marker": "1",
                      "midhostMarker": "",
                      "fakeHostTemplate": "",
                      "fragmentCount": 0,
                      "minFragmentSize": 0,
                      "maxFragmentSize": 0
                    }
                  ],
                  "fakeTtl": 8,
                  "fakeSni": "www.iana.org",
                  "fakeTlsUseOriginal": false,
                  "fakeTlsRandomize": false,
                  "fakeTlsDupSessionId": false,
                  "fakeTlsPadEncap": false,
                  "fakeTlsSize": 0,
                  "fakeTlsSniMode": "fixed",
                  "oobChar": 97,
                  "hostMixedCase": false,
                  "domainMixedCase": false,
                  "hostRemoveSpaces": false,
                  "tlsRecordSplit": false,
                  "tlsRecordSplitMarker": "0",
                  "hostsMode": "disable",
                  "hosts": null,
                  "tcpFastOpen": false,
                  "udpFakeCount": 0,
                  "udpChainSteps": [],
                  "dropSack": false,
                  "fakeOffsetMarker": "0",
                  "quicInitialMode": "route_and_cache",
                  "quicSupportV1": true,
                  "quicSupportV2": true,
                  "quicFakeProfile": "disabled",
                  "quicFakeHost": "",
                  "hostAutolearnEnabled": false,
                  "hostAutolearnPenaltyTtlSecs": 21600,
                  "hostAutolearnMaxHosts": 512,
                  "hostAutolearnStorePath": null
                }
                """,
        )
    }

    @Test
    fun proxyCustomUiPayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyUIPreferences(
                ip = "0.0.0.0",
                port = 2080,
                maxConnections = 1024,
                bufferSize = 32768,
                defaultTtl = 64,
                noDomain = true,
                desyncHttp = false,
                desyncHttps = true,
                desyncUdp = true,
                desyncMethod = RipDpiProxyUIPreferences.DesyncMethod.Fake,
                splitMarker = "host+2",
                fakeTtl = 9,
                fakeSni = "alt.example.org",
                httpFakeProfile = HttpFakeProfileCloudflareGet,
                fakeTlsUseOriginal = true,
                fakeTlsRandomize = true,
                fakeTlsDupSessionId = true,
                fakeTlsPadEncap = true,
                fakeTlsSize = -24,
                fakeTlsSniMode = "randomized",
                tlsFakeProfile = TlsFakeProfileGoogleChrome,
                oobChar = "Z",
                hostMixedCase = true,
                domainMixedCase = true,
                hostRemoveSpaces = true,
                tlsRecordSplit = true,
                tlsRecordSplitMarker = "sniext+3",
                hostsMode = RipDpiProxyUIPreferences.HostsMode.Whitelist,
                hosts = "example.org",
                tcpFastOpen = true,
                udpFakeCount = 4,
                udpFakeProfile = UdpFakeProfileDnsQuery,
                dropSack = true,
                fakeOffsetMarker = "endhost-1",
                quicInitialMode = "route",
                quicSupportV1 = false,
                quicSupportV2 = true,
                quicFakeProfile = "realistic_initial",
                quicFakeHost = "video.example.test",
                hostAutolearnEnabled = true,
                hostAutolearnPenaltyTtlHours = 12,
                hostAutolearnMaxHosts = 2048,
                hostAutolearnStorePath = "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v1.json",
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                """
                {
                  "kind": "ui",
                  "ip": "0.0.0.0",
                  "port": 2080,
                  "maxConnections": 1024,
                  "bufferSize": 32768,
                  "defaultTtl": 64,
                  "customTtl": true,
                  "noDomain": true,
                  "desyncHttp": false,
                  "desyncHttps": true,
                  "desyncUdp": true,
                  "desyncMethod": "fake",
                  "splitMarker": "host+2",
                  "tcpChainSteps": [
                    {
                      "kind": "tlsrec",
                      "marker": "sniext+3",
                      "midhostMarker": "",
                      "fakeHostTemplate": "",
                      "fragmentCount": 0,
                      "minFragmentSize": 0,
                      "maxFragmentSize": 0
                    },
                    {
                      "kind": "fake",
                      "marker": "host+2",
                      "midhostMarker": "",
                      "fakeHostTemplate": "",
                      "fragmentCount": 0,
                      "minFragmentSize": 0,
                      "maxFragmentSize": 0
                    }
                  ],
                  "fakeTtl": 9,
                  "fakeSni": "alt.example.org",
                  "httpFakeProfile": "cloudflare_get",
                  "fakeTlsUseOriginal": true,
                  "fakeTlsRandomize": true,
                  "fakeTlsDupSessionId": true,
                  "fakeTlsPadEncap": true,
                  "fakeTlsSize": -24,
                  "fakeTlsSniMode": "randomized",
                  "tlsFakeProfile": "google_chrome",
                  "oobChar": 90,
                  "hostMixedCase": true,
                  "domainMixedCase": true,
                  "hostRemoveSpaces": true,
                  "tlsRecordSplit": true,
                  "tlsRecordSplitMarker": "sniext+3",
                  "hostsMode": "whitelist",
                  "hosts": "example.org",
                  "tcpFastOpen": true,
                  "udpFakeCount": 4,
                  "udpChainSteps": [
                    {
                      "kind": "fake_burst",
                      "count": 4
                    }
                  ],
                  "udpFakeProfile": "dns_query",
                  "dropSack": true,
                  "fakeOffsetMarker": "endhost-1",
                  "quicInitialMode": "route",
                  "quicSupportV1": false,
                  "quicSupportV2": true,
                  "quicFakeProfile": "realistic_initial",
                  "quicFakeHost": "video.example.test",
                  "hostAutolearnEnabled": true,
                  "hostAutolearnPenaltyTtlSecs": 43200,
                  "hostAutolearnMaxHosts": 2048,
                  "hostAutolearnStorePath": "/data/user/0/com.poyka.ripdpi/no_backup/ripdpi/host-autolearn-v1.json"
                }
                """,
        )
    }

    @Test
    fun proxyHostfakeUiPayloadMatchesSnapshot() {
        val payload =
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
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                """
                {
                  "kind": "ui",
                  "ip": "127.0.0.1",
                  "port": 1080,
                  "maxConnections": 512,
                  "bufferSize": 16384,
                  "defaultTtl": 0,
                  "customTtl": false,
                  "noDomain": false,
                  "desyncHttp": true,
                  "desyncHttps": true,
                  "desyncUdp": false,
                  "desyncMethod": "disorder",
                  "splitMarker": "1",
                  "tcpChainSteps": [
                    {
                      "kind": "hostfake",
                      "marker": "endhost+8",
                      "midhostMarker": "midsld",
                      "fakeHostTemplate": "googlevideo.com",
                      "fragmentCount": 0,
                      "minFragmentSize": 0,
                      "maxFragmentSize": 0
                    }
                  ],
                  "fakeTtl": 8,
                  "fakeSni": "www.iana.org",
                  "fakeTlsUseOriginal": false,
                  "fakeTlsRandomize": false,
                  "fakeTlsDupSessionId": false,
                  "fakeTlsPadEncap": false,
                  "fakeTlsSize": 0,
                  "fakeTlsSniMode": "fixed",
                  "oobChar": 97,
                  "hostMixedCase": false,
                  "domainMixedCase": false,
                  "hostRemoveSpaces": false,
                  "tlsRecordSplit": false,
                  "tlsRecordSplitMarker": "0",
                  "hostsMode": "disable",
                  "hosts": null,
                  "tcpFastOpen": false,
                  "udpFakeCount": 0,
                  "udpChainSteps": [],
                  "dropSack": false,
                  "fakeOffsetMarker": "0",
                  "quicInitialMode": "route_and_cache",
                  "quicSupportV1": true,
                  "quicSupportV2": true,
                  "quicFakeProfile": "disabled",
                  "quicFakeHost": "",
                  "hostAutolearnEnabled": false,
                  "hostAutolearnPenaltyTtlSecs": 21600,
                  "hostAutolearnMaxHosts": 512,
                  "hostAutolearnStorePath": null
                }
                """,
        )
    }

    @Test
    fun proxyTlsRandRecUiPayloadMatchesSnapshot() {
        val payload =
            RipDpiProxyUIPreferences(
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRandRec,
                            marker = "sniext+4",
                            fragmentCount = 5,
                            minFragmentSize = 24,
                            maxFragmentSize = 48,
                        ),
                    ),
            ).toNativeConfigJson()

        assertJsonSnapshot(
            actualJson = payload,
            expectedJson =
                """
                {
                  "kind": "ui",
                  "ip": "127.0.0.1",
                  "port": 1080,
                  "maxConnections": 512,
                  "bufferSize": 16384,
                  "defaultTtl": 0,
                  "customTtl": false,
                  "noDomain": false,
                  "desyncHttp": true,
                  "desyncHttps": true,
                  "desyncUdp": false,
                  "desyncMethod": "disorder",
                  "splitMarker": "1",
                  "tcpChainSteps": [
                    {
                      "kind": "tlsrandrec",
                      "marker": "sniext+4",
                      "midhostMarker": "",
                      "fakeHostTemplate": "",
                      "fragmentCount": 5,
                      "minFragmentSize": 24,
                      "maxFragmentSize": 48
                    }
                  ],
                  "fakeTtl": 8,
                  "fakeSni": "www.iana.org",
                  "fakeTlsUseOriginal": false,
                  "fakeTlsRandomize": false,
                  "fakeTlsDupSessionId": false,
                  "fakeTlsPadEncap": false,
                  "fakeTlsSize": 0,
                  "fakeTlsSniMode": "fixed",
                  "oobChar": 97,
                  "hostMixedCase": false,
                  "domainMixedCase": false,
                  "hostRemoveSpaces": false,
                  "tlsRecordSplit": false,
                  "tlsRecordSplitMarker": "0",
                  "hostsMode": "disable",
                  "hosts": null,
                  "tcpFastOpen": false,
                  "udpFakeCount": 0,
                  "udpChainSteps": [],
                  "dropSack": false,
                  "fakeOffsetMarker": "0",
                  "quicInitialMode": "route_and_cache",
                  "quicSupportV1": true,
                  "quicSupportV2": true,
                  "quicFakeProfile": "disabled",
                  "quicFakeHost": "",
                  "hostAutolearnEnabled": false,
                  "hostAutolearnPenaltyTtlSecs": 21600,
                  "hostAutolearnMaxHosts": 512,
                  "hostAutolearnStorePath": null
                }
                """,
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
                    dohResolverId = "cloudflare",
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                    dohBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
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
                  "dohResolverId": "cloudflare",
                  "dohUrl": "https://cloudflare-dns.com/dns-query",
                  "dohBootstrapIps": [
                    "1.1.1.1",
                    "1.0.0.1"
                  ],
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

    private fun assertJsonSnapshot(
        actualJson: String,
        expectedJson: String,
    ) {
        assertEquals(
            canonicalJson(expectedJson),
            canonicalJson(actualJson),
        )
    }

    private fun canonicalJson(value: String): String =
        snapshotJson.encodeToString(
            serializer(),
            Json.parseToJsonElement(value).sortedKeys(),
        )

    private fun JsonElement.sortedKeys(): JsonElement =
        when (this) {
            is JsonArray -> JsonArray(map { it.sortedKeys() })
            is JsonObject ->
                JsonObject(
                    entries
                        .sortedBy { it.key }
                        .associate { (key, value) -> key to value.sortedKeys() },
                )
            else -> this
        }

    private companion object {
        val snapshotJson =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
    }
}
