package com.poyka.ripdpi.e2e

import android.os.ParcelFileDescriptor
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTelemetryGoldenSmokeTest {
    private val json =
        Json {
            explicitNulls = true
            encodeDefaults = true
        }

    @Test
    fun proxyRuntimeTelemetryMatchesGoldenAndStopsCleanly() {
        runBlocking {
            val proxy = RipDpiProxy(RipDpiProxyNativeBindings())
            val port = reserveLoopbackPort()
            val proxyJob =
                async(Dispatchers.IO) {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = port)))
                }

            try {
                val telemetry = awaitRunningTelemetry(proxy, port)
                GoldenContractSupport.assertJsonAsset(
                    "golden/proxy_running_first_poll.json",
                    json.encodeToString(NativeRuntimeSnapshot.serializer(), telemetry),
                ) { payload ->
                    GoldenContractSupport.scrubCommonTelemetryJson(payload, port)
                }

                proxy.stopProxy()
                assertStoppedExitCode(proxyJob.await())
            } finally {
                if (proxyJob.isActive) {
                    runCatching { proxy.stopProxy() }
                    withTimeoutOrNull(5_000) {
                        runCatching { proxyJob.await() }
                    }
                }
            }
        }
    }

    @Test
    fun tunnelRuntimeIdleTelemetryMatchesGoldenAndTransitionsThroughRunningState() {
        val bindings = Tun2SocksNativeBindings()
        val handle =
            bindings.create(
                Json.encodeToString(
                    Tun2SocksConfig(
                        socks5Port = 1080,
                    ),
                ),
            )
        val pipe = ParcelFileDescriptor.createPipe()

        try {
            GoldenContractSupport.assertJsonAsset(
                "golden/tunnel_ready.json",
                json.encodeToString(
                    NativeRuntimeSnapshot.serializer(),
                    json.decodeFromString(
                        NativeRuntimeSnapshot.serializer(),
                        requireNotNull(bindings.getTelemetry(handle)),
                    ),
                ),
            ) { payload ->
                GoldenContractSupport.scrubCommonTelemetryJson(payload)
            }

            bindings.start(handle, pipe[0].fd)

            awaitTunnelRunningTelemetry(bindings, handle)

            bindings.stop(handle)
            awaitTunnelIdleTelemetry(bindings, handle)
        } finally {
            runCatching { bindings.destroy(handle) }
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    private fun awaitTunnelRunningTelemetry(
        bindings: Tun2SocksNativeBindings,
        handle: Long,
        timeoutMs: Long = 10_000,
    ) {
        awaitUntil(timeoutMs = timeoutMs) {
            val telemetryJson = bindings.getTelemetry(handle) ?: return@awaitUntil false
            val telemetry = json.decodeFromString(NativeRuntimeSnapshot.serializer(), telemetryJson)
            telemetry.upstreamAddress == "127.0.0.1:1080" &&
                telemetry.state == "running" &&
                telemetry.nativeEvents.any { event ->
                    event.source == "tunnel" && event.message.contains("tunnel started upstream=127.0.0.1:1080")
                }
        }
    }

    private fun awaitTunnelIdleTelemetry(
        bindings: Tun2SocksNativeBindings,
        handle: Long,
        timeoutMs: Long = 10_000,
    ) {
        awaitUntil(timeoutMs = timeoutMs) {
            val telemetryJson = bindings.getTelemetry(handle) ?: return@awaitUntil false
            val telemetry = json.decodeFromString(NativeRuntimeSnapshot.serializer(), telemetryJson)
            telemetry.state == "idle"
        }
    }

    private suspend fun awaitRunningTelemetry(
        proxy: RipDpiProxy,
        port: Int,
        timeoutMs: Long = 5_000,
    ): NativeRuntimeSnapshot = withTimeout(timeoutMs) {
        while (true) {
            val telemetry = proxy.pollTelemetry()
            if (telemetry.state == "running" && telemetry.listenerAddress == "127.0.0.1:$port") {
                return@withTimeout telemetry
            }
            delay(50)
        }
        error("unreachable")
    }

    private fun assertStoppedExitCode(exitCode: Int) {
        assertTrue(
            "Expected a clean shutdown code, got $exitCode",
            exitCode == 0 || exitCode == 22,
        )
    }
}
