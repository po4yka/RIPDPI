package com.poyka.ripdpi.e2e

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.core.RipDpiProxy
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksNativeBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTelemetryGoldenSmokeTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun proxyRuntimeTelemetryMatchesGoldenAndWritesSemanticLogcat() {
        runBlocking {
            execShell("logcat -c")

            val proxy = RipDpiProxy(RipDpiProxyNativeBindings(ApplicationProvider.getApplicationContext()))
            val port = reserveLoopbackPort()
            val proxyJob =
                async(Dispatchers.IO) {
                    proxy.startProxy(RipDpiProxyUIPreferences(port = port))
                }

            val telemetry = awaitRunningTelemetry(proxy, port)
            GoldenContractSupport.assertJsonAsset(
                "golden/proxy_running_first_poll.json",
                json.encodeToString(telemetry),
            ) { payload ->
                GoldenContractSupport.scrubCommonTelemetryJson(payload, port)
            }

            proxy.stopProxy()
            assertStoppedExitCode(proxyJob.await())

            awaitUntil {
                val logcat = execShell("logcat -d -s ripdpi-native:I")
                logcat.contains("listener started") && logcat.contains("listener stopped")
            }
        }
    }

    @Test
    fun tunnelRuntimeIdleTelemetryMatchesGoldenAndWritesSemanticLogcat() {
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
                requireNotNull(bindings.getTelemetry(handle)),
            ) { payload ->
                GoldenContractSupport.scrubCommonTelemetryJson(payload)
            }

            execShell("logcat -c")
            bindings.start(handle, pipe[0].fd)

            awaitUntil(timeoutMs = 10_000) {
                val logcat = execShell("logcat -d -s hs5t-native:I")
                logcat.contains("tunnel started upstream=127.0.0.1:1080") &&
                    (logcat.contains("tunnel error:") || logcat.contains("tunnel worker exited with error"))
            }

            bindings.stop(handle)
        } finally {
            runCatching { bindings.destroy(handle) }
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    private suspend fun awaitRunningTelemetry(
        proxy: RipDpiProxy,
        port: Int,
        timeoutMs: Long = 5_000,
    ) = withTimeout(timeoutMs) {
        while (true) {
            val telemetry = proxy.pollTelemetry()
            if (telemetry.state == "running" && telemetry.listenerAddress == "127.0.0.1:$port") {
                return@withTimeout telemetry
            }
            delay(50)
        }
    }

    private fun assertStoppedExitCode(exitCode: Int) {
        assertTrue(
            "Expected a clean shutdown code, got $exitCode",
            exitCode == 0 || exitCode == 22,
        )
    }
}
