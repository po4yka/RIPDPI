package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.LatencyDistributions
import com.poyka.ripdpi.data.LatencyPercentiles
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NativeWifiSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language binary contract tests.
 *
 * These tests read golden fixture files from `contract-fixtures/` at the repository root.
 * The Rust side generates these fixtures; the Kotlin side validates against them.
 * If a field is added or removed on either side, these tests catch the drift at CI time.
 */
class NativeBinaryContractTest {
    private val contractJson =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    // -- Telemetry field manifests --

    @Test
    fun `kotlin snapshot fields are superset of proxy contract fixture`() {
        val proxyFields = readFieldManifest("proxy_snapshot_fields.json")
        val kotlinFields = extractKotlinSnapshotFields()

        val missing = proxyFields - kotlinFields
        assertTrue(
            "Kotlin NativeRuntimeSnapshot is missing proxy fields: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `kotlin snapshot fields are superset of tunnel contract fixture`() {
        val tunnelFields = readFieldManifest("tunnel_snapshot_fields.json")
        val kotlinFields = extractKotlinSnapshotFields()

        val missing = tunnelFields - kotlinFields
        assertTrue(
            "Kotlin NativeRuntimeSnapshot is missing tunnel fields: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `kotlin snapshot has no fields absent from both proxy and tunnel fixtures`() {
        val proxyFields = readFieldManifest("proxy_snapshot_fields.json")
        val tunnelFields = readFieldManifest("tunnel_snapshot_fields.json")
        val rustUnion = proxyFields + tunnelFields
        val kotlinFields = extractKotlinSnapshotFields()

        val extra = kotlinFields - rustUnion
        assertTrue(
            "Kotlin NativeRuntimeSnapshot has fields not emitted by any Rust source: $extra",
            extra.isEmpty(),
        )
    }

    @Test
    fun `kotlin event fields match proxy event contract fixture`() {
        val proxyEventFields = readFieldManifest("proxy_event_fields.json")
        val kotlinEventFields = extractKotlinEventFields()

        assertEquals(
            "NativeRuntimeEvent field mismatch between Rust and Kotlin",
            proxyEventFields.sorted(),
            kotlinEventFields.sorted(),
        )
    }

    // -- Tunnel stats array layout --

    @Test
    fun `tunnel stats fromNative matches contract fixture index mapping`() {
        val fixture = GoldenContractSupport.readSharedFixture("tunnel_stats_layout.json")
        val layout = Json.decodeFromString<JsonObject>(fixture.trim())
        val indices = (layout["indices"] as JsonObject)

        val txPacketsIdx = (indices["txPackets"] as JsonPrimitive).content.toInt()
        val txBytesIdx = (indices["txBytes"] as JsonPrimitive).content.toInt()
        val rxPacketsIdx = (indices["rxPackets"] as JsonPrimitive).content.toInt()
        val rxBytesIdx = (indices["rxBytes"] as JsonPrimitive).content.toInt()
        val arrayLength = (layout["arrayLength"] as JsonPrimitive).content.toInt()

        assertEquals("array length", 4, arrayLength)
        assertEquals("txPackets index", 0, txPacketsIdx)
        assertEquals("txBytes index", 1, txBytesIdx)
        assertEquals("rxPackets index", 2, rxPacketsIdx)
        assertEquals("rxBytes index", 3, rxBytesIdx)

        val stats = TunnelStats.fromNative(longArrayOf(10, 20, 30, 40))
        assertEquals("txPackets value", 10L, stats.txPackets)
        assertEquals("txBytes value", 20L, stats.txBytes)
        assertEquals("rxPackets value", 30L, stats.rxPackets)
        assertEquals("rxBytes value", 40L, stats.rxBytes)
    }

    // -- Error exception mapping --

    @Test
    fun `error exception classes from contract fixture are resolvable`() {
        val fixture = GoldenContractSupport.readSharedFixture("error_exception_mapping.json")
        val mappings = Json.decodeFromString<JsonArray>(fixture.trim())

        for (entry in mappings) {
            val obj = entry as JsonObject
            val javaClass = (obj["javaClass"] as JsonPrimitive).content
            val variant = (obj["variant"] as JsonPrimitive).content

            val clazz = Class.forName(javaClass)
            assertTrue(
                "$variant maps to $javaClass which should be a Throwable",
                Throwable::class.java.isAssignableFrom(clazz),
            )
        }
    }

    // -- Handle sentinel --

    @Test
    fun `handle sentinel matches contract fixture`() {
        val fixture = GoldenContractSupport.readSharedFixture("handle_contract.json")
        val contract = Json.decodeFromString<JsonObject>(fixture.trim())

        val invalidSentinel = (contract["invalidSentinel"] as JsonPrimitive).content.toLong()
        assertEquals("invalid handle sentinel", 0L, invalidSentinel)
    }

    // -- Proxy start return codes --

    @Test
    fun `proxy start return codes match contract fixture`() {
        val fixture = GoldenContractSupport.readSharedFixture("proxy_start_codes.json")
        val contract = Json.decodeFromString<JsonObject>(fixture.trim())

        val success = (contract["success"] as JsonPrimitive).content.toInt()
        val semantics = (contract["semantics"] as JsonPrimitive).content

        assertEquals("success code", 0, success)
        assertEquals("error semantics", "positive_errno", semantics)
    }

    // -- Network snapshot field manifest --

    @Test
    fun `kotlin network snapshot fields match contract fixture`() {
        val rustFields = readFieldManifest("network_snapshot_fields.json")
        val kotlinFields = extractKotlinNetworkSnapshotFields()
        assertEquals("NativeNetworkSnapshot field mismatch", rustFields.sorted(), kotlinFields.sorted())
    }

    // -- Tunnel config field manifest --

    @Test
    fun `kotlin tunnel config fields are subset of contract fixture`() {
        val rustFields = readFieldManifest("tunnel_config_fields.json")
        val kotlinFields = extractKotlinTunnelConfigFields()
        val extraInKotlin = kotlinFields - rustFields
        assertTrue("Kotlin Tun2SocksConfig has fields Rust does not expect: $extraInKotlin", extraInKotlin.isEmpty())
    }

    // -- Tunnel exception messages --

    @Test
    fun `tunnel exception message classes from contract fixture are resolvable`() {
        val fixture = GoldenContractSupport.readSharedFixture("tunnel_exception_messages.json")
        val mappings = Json.decodeFromString<JsonArray>(fixture.trim())
        for (entry in mappings) {
            val obj = entry as JsonObject
            val javaClass = (obj["javaClass"] as JsonPrimitive).content
            val message = (obj["message"] as JsonPrimitive).content
            val clazz = Class.forName(javaClass)
            assertTrue("'$message' maps to non-Throwable $javaClass", Throwable::class.java.isAssignableFrom(clazz))
        }
    }

    // -- Helpers --

    private fun readFieldManifest(filename: String): Set<String> {
        val fixture = GoldenContractSupport.readSharedFixture(filename)
        val array = Json.decodeFromString<JsonArray>(fixture.trim())
        return array.map { (it as JsonPrimitive).content }.toSet()
    }

    @Suppress("LongMethod")
    private fun extractKotlinSnapshotFields(): Set<String> {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "test",
                state = "running",
                health = "healthy",
                activeSessions = 1,
                totalSessions = 10,
                totalErrors = 2,
                networkErrors = 1,
                routeChanges = 3,
                retryPacedCount = 1,
                lastRetryBackoffMs = 500,
                lastRetryReason = "backoff",
                candidateDiversificationCount = 1,
                lastRouteGroup = 0,
                listenerAddress = "127.0.0.1:1080",
                upstreamAddress = "203.0.113.10:443",
                upstreamRttMs = 42,
                resolverId = "cloudflare",
                resolverProtocol = "doh",
                resolverEndpoint = "1.1.1.1:443",
                resolverLatencyMs = 15,
                resolverLatencyAvgMs = 12,
                resolverFallbackActive = true,
                resolverFallbackReason = "timeout",
                networkHandoverClass = "wifi_to_cellular",
                lastTarget = "203.0.113.10:443",
                lastHost = "example.org",
                lastError = "connection reset",
                lastFailureClass = "tcp_reset",
                lastFallbackAction = "retry_with_matching_group",
                dnsQueriesTotal = 100,
                dnsCacheHits = 50,
                dnsCacheMisses = 30,
                dnsFailuresTotal = 5,
                lastDnsHost = "example.org",
                lastDnsError = "SERVFAIL",
                autolearnEnabled = true,
                learnedHostCount = 5,
                penalizedHostCount = 1,
                lastAutolearnHost = "example.org",
                lastAutolearnGroup = 0,
                lastAutolearnAction = "group_penalized",
                slotExhaustions = 1,
                tunnelStats = TunnelStats(txPackets = 100, txBytes = 5000, rxPackets = 80, rxBytes = 4000),
                nativeEvents =
                    listOf(
                        NativeRuntimeEvent(
                            source = "test",
                            level = "info",
                            message = "test",
                            createdAt = 1000,
                            runtimeId = "rt-1",
                            mode = "auto",
                            policySignature = "sig",
                            fingerprintHash = "hash",
                            subsystem = "proxy",
                        ),
                    ),
                latencyDistributions =
                    LatencyDistributions(
                        dnsResolution =
                            LatencyPercentiles(
                                p50 = 10,
                                p95 = 20,
                                p99 = 30,
                                min = 1,
                                max = 50,
                                count = 100,
                            ),
                        tcpConnect =
                            LatencyPercentiles(
                                p50 = 15,
                                p95 = 25,
                                p99 = 35,
                                min = 2,
                                max = 60,
                                count = 200,
                            ),
                        tlsHandshake = LatencyPercentiles(p50 = 20, p95 = 30, p99 = 40, min = 5, max = 80, count = 150),
                    ),
                capturedAt = 1000,
            )

        val json = contractJson.encodeToJsonElement(snapshot)
        return extractFieldPaths(json)
    }

    private fun extractKotlinEventFields(): Set<String> {
        val event =
            NativeRuntimeEvent(
                source = "test",
                level = "info",
                message = "test",
                createdAt = 1000,
                runtimeId = "rt-1",
                mode = "auto",
                policySignature = "sig",
                fingerprintHash = "hash",
                subsystem = "proxy",
            )

        val json = contractJson.encodeToJsonElement(event)
        return extractFieldPaths(json)
    }

    private fun extractFieldPaths(
        element: JsonElement,
        prefix: String = "",
    ): Set<String> {
        if (element !is JsonObject) return emptySet()
        val paths = mutableSetOf<String>()
        for ((key, child) in element) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            paths.addAll(extractChildPaths(child, path))
        }
        return paths
    }

    private fun extractChildPaths(
        child: JsonElement,
        path: String,
    ): Set<String> =
        when (child) {
            is JsonObject -> {
                extractFieldPaths(child, path)
            }

            is JsonArray -> {
                val first = child.firstOrNull()
                if (first is JsonObject) extractFieldPaths(first, "$path[]") else setOf("$path[]")
            }

            else -> {
                setOf(path)
            }
        }

    private fun extractKotlinNetworkSnapshotFields(): Set<String> {
        val snapshot =
            NativeNetworkSnapshot(
                transport = "wifi",
                validated = true,
                captivePortal = false,
                metered = false,
                privateDnsMode = "system",
                dnsServers = listOf("8.8.8.8"),
                cellular =
                    NativeCellularSnapshot(
                        generation = "4g",
                        roaming = false,
                        operatorCode = "310260",
                        dataNetworkType = "LTE",
                        serviceState = "in_service",
                        carrierId = 1,
                        signalLevel = 3,
                        signalDbm = -85,
                    ),
                wifi =
                    NativeWifiSnapshot(
                        frequencyBand = "5ghz",
                        ssidHash = "abc123",
                        frequencyMhz = 5180,
                        rssiDbm = -55,
                        linkSpeedMbps = 866,
                        rxLinkSpeedMbps = 400,
                        txLinkSpeedMbps = 866,
                        channelWidth = "80 MHz",
                        wifiStandard = "802.11ax",
                    ),
                mtu = 1500,
                trafficTxBytes = 100000,
                trafficRxBytes = 200000,
                capturedAtMs = 1000,
            )
        return extractFieldPaths(contractJson.encodeToJsonElement(snapshot))
    }

    private fun extractKotlinTunnelConfigFields(): Set<String> {
        val config =
            Tun2SocksConfig(
                socks5Port = 1080,
                tunnelIpv4 = "10.0.0.2",
                tunnelIpv6 = "fd00::2",
                socks5Udp = "udp",
                socks5UdpAddress = "127.0.0.2",
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
                encryptedDnsBootstrapIps = listOf("1.0.0.1"),
                encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
                encryptedDnsDnscryptProviderName = "provider",
                encryptedDnsDnscryptPublicKey = "key",
                dnsQueryTimeoutMs = 4000,
                resolverFallbackActive = true,
                resolverFallbackReason = "timeout",
                tcpBufferSize = 32768,
                udpRecvBufferSize = 16384,
                udpCopyBufferNums = 8,
                maxSessionCount = 2048,
                connectTimeoutMs = 3000,
                tcpReadWriteTimeoutMs = 6000,
                udpReadWriteTimeoutMs = 7000,
                logLevel = "info",
                limitNofile = 4096,
                logContext =
                    RipDpiLogContext(
                        runtimeId = "rt-1",
                        mode = "auto",
                        policySignature = "sig",
                        fingerprintHash = "hash",
                        diagnosticsSessionId = "diag-1",
                    ),
            )
        return extractFieldPaths(contractJson.encodeToJsonElement(config))
    }
}
