package com.poyka.ripdpi.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTelemetryGoldenTest {
    private val json = Json { explicitNulls = true; encodeDefaults = true }

    @Test
    fun proxyTelemetryFirstAndSecondPollMatchGoldens() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val startedSignal = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    this.startedSignal = startedSignal
                    startBlocker = blocker
                    telemetryJson = proxyTelemetryPayload(includeEvents = true)
                }
            val proxy = RipDpiProxy(bindings)

            val start =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = 1080))
                }

            startedSignal.await()
            GoldenContractSupport.assertJsonGolden(
                "proxy_running_first_poll.json",
                json.encodeToString(NativeRuntimeSnapshot.serializer(), proxy.pollTelemetry()),
            )

            bindings.telemetryJson = proxyTelemetryPayload(includeEvents = false)
            GoldenContractSupport.assertJsonGolden(
                "proxy_running_second_poll.json",
                json.encodeToString(NativeRuntimeSnapshot.serializer(), proxy.pollTelemetry()),
            )

            blocker.complete(Unit)
            start.await()
        }

    @Test
    fun tunnelReadyTelemetryPayloadParsesToGoldenSnapshot() {
        val parsed =
            json.decodeFromString(
                NativeRuntimeSnapshot.serializer(),
                tunnelReadyPayload(),
            )

        GoldenContractSupport.assertJsonGolden(
            "tunnel_ready.json",
            json.encodeToString(NativeRuntimeSnapshot.serializer(), parsed),
        )
    }

    @Test
    fun resolverTelemetryFieldsRoundTripThroughSnapshotJson() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "tunnel",
                state = "running",
                resolverId = "cloudflare",
                resolverProtocol = "doh",
                resolverEndpoint = "https://cloudflare-dns.com/dns-query",
                resolverLatencyMs = 42,
                resolverLatencyAvgMs = 38,
                resolverFallbackActive = true,
                resolverFallbackReason = "UDP DNS showed udp_blocked",
                networkHandoverClass = "transport_switch",
                dnsFailuresTotal = 3,
                lastDnsHost = "example.org",
                lastDnsError = "timeout",
                capturedAt = 99L,
            )

        val parsed =
            json.decodeFromString(
                NativeRuntimeSnapshot.serializer(),
                json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot),
            )

        assertEquals("cloudflare", parsed.resolverId)
        assertEquals("doh", parsed.resolverProtocol)
        assertEquals("https://cloudflare-dns.com/dns-query", parsed.resolverEndpoint)
        assertEquals(42L, parsed.resolverLatencyMs)
        assertEquals(38L, parsed.resolverLatencyAvgMs)
        assertTrue(parsed.resolverFallbackActive)
        assertEquals("UDP DNS showed udp_blocked", parsed.resolverFallbackReason)
        assertEquals("transport_switch", parsed.networkHandoverClass)
        assertEquals(3L, parsed.dnsFailuresTotal)
    }

    @Test
    fun proxyUpstreamRttRoundTripsThroughSnapshotJson() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                upstreamAddress = "203.0.113.10:443",
                upstreamRttMs = 87L,
                capturedAt = 55L,
            )

        val parsed =
            json.decodeFromString(
                NativeRuntimeSnapshot.serializer(),
                json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot),
            )

        assertEquals("203.0.113.10:443", parsed.upstreamAddress)
        assertEquals(87L, parsed.upstreamRttMs)
    }

    @Test
    fun proxyFailureClassificationFieldsRoundTripThroughSnapshotJson() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                lastFailureClass = "dns_tampering",
                lastFallbackAction = "resolver_override_recommended",
                capturedAt = 77L,
            )

        val parsed =
            json.decodeFromString(
                NativeRuntimeSnapshot.serializer(),
                json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot),
            )

        assertEquals("dns_tampering", parsed.lastFailureClass)
        assertEquals("resolver_override_recommended", parsed.lastFallbackAction)
    }

    private fun proxyTelemetryPayload(includeEvents: Boolean): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                health = "healthy",
                autolearnEnabled = true,
                learnedHostCount = 3,
                penalizedHostCount = 1,
                lastAutolearnHost = "example.org",
                lastAutolearnGroup = 2,
                lastAutolearnAction = "host_promoted",
                listenerAddress = "127.0.0.1:<port>",
                nativeEvents =
                    if (includeEvents) {
                        listOf(
                            NativeRuntimeEvent(
                                source = "proxy",
                                level = "info",
                                message = "listener started addr=127.0.0.1:<port> maxClients=512 groups=2",
                                createdAt = 0L,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                capturedAt = 0L,
            ),
        )

    private fun tunnelReadyPayload(): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(
                source = "tunnel",
                state = "idle",
                health = "idle",
                nativeEvents = emptyList(),
                capturedAt = 0L,
            ),
        )
}
