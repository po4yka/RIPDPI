package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTelemetryGoldenTest {
    private val json =
        Json {
            explicitNulls = true
            encodeDefaults = true
        }

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
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1080)))
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
    fun proxyTelemetryTelegramWsEventsMatchGolden() {
        GoldenContractSupport.assertJsonGolden(
            "proxy_running_telegram_ws_events.json",
            json.encodeToString(NativeRuntimeSnapshot.serializer(), proxyTelemetryWithTelegramWsEvents()),
        )
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

    @Test
    fun proxyRetryStealthFieldsRoundTripThroughSnapshotJson() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                retryPacedCount = 3,
                lastRetryBackoffMs = 1500L,
                lastRetryReason = "same_signature_retry",
                candidateDiversificationCount = 2,
                capturedAt = 88L,
            )

        val parsed =
            json.decodeFromString(
                NativeRuntimeSnapshot.serializer(),
                json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot),
            )

        assertEquals(3L, parsed.retryPacedCount)
        assertEquals(1500L, parsed.lastRetryBackoffMs)
        assertEquals("same_signature_retry", parsed.lastRetryReason)
        assertEquals(2L, parsed.candidateDiversificationCount)
    }

    @Test
    fun proxyBlockingFieldsRoundTripThroughSnapshotJson() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                blockedHostCount = 2,
                lastBlockSignal = "tcp_reset",
                lastBlockProvider = "rkn",
                lastAutolearnAction = "host_blocked",
                capturedAt = 91L,
            )

        val parsed =
            json.decodeFromString(
                NativeRuntimeSnapshot.serializer(),
                json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot),
            )

        assertEquals(2, parsed.blockedHostCount)
        assertEquals("tcp_reset", parsed.lastBlockSignal)
        assertEquals("rkn", parsed.lastBlockProvider)
        assertEquals("host_blocked", parsed.lastAutolearnAction)
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
                blockedHostCount = 2,
                lastBlockSignal = "tcp_reset",
                lastBlockProvider = "rkn",
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

    private fun proxyTelemetryWithTelegramWsEvents(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "proxy",
            state = "running",
            health = "healthy",
            listenerAddress = "127.0.0.1:<port>",
            lastTarget = "149.154.167.91:443",
            nativeEvents =
                listOf(
                    NativeRuntimeEvent(
                        source = "proxy",
                        level = "info",
                        message = "listener started addr=127.0.0.1:<port> maxClients=512 groups=2",
                        createdAt = 0L,
                    ),
                    NativeRuntimeEvent(
                        source = "proxy",
                        level = "info",
                        message = "telegram dc detected target=149.154.167.91:443 dc=2",
                        createdAt = 1L,
                    ),
                    NativeRuntimeEvent(
                        source = "proxy",
                        level = "info",
                        message = "ws tunnel escalation target=149.154.167.91:443 dc=2 result=fallback",
                        createdAt = 2L,
                    ),
                ),
            capturedAt = 2L,
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
