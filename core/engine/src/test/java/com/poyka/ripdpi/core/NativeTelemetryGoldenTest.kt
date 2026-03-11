package com.poyka.ripdpi.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private fun proxyTelemetryPayload(includeEvents: Boolean): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                health = "healthy",
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
