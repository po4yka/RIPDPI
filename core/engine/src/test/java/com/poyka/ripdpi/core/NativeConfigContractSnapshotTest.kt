package com.poyka.ripdpi.core

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
                      "marker": "1"
                    }
                  ],
                  "fakeTtl": 8,
                  "fakeSni": "www.iana.org",
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
                  "fakeOffsetMarker": "0"
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
                dropSack = true,
                fakeOffsetMarker = "endhost-1",
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
                      "marker": "sniext+3"
                    },
                    {
                      "kind": "fake",
                      "marker": "host+2"
                    }
                  ],
                  "fakeTtl": 9,
                  "fakeSni": "alt.example.org",
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
                  "dropSack": true,
                  "fakeOffsetMarker": "endhost-1"
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
