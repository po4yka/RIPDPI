package com.poyka.ripdpi.testing.awginterop

import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.core.RipDpiAmneziaWgNativeBindings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Uses the same production JNI library and OS protection as the bulk acceptance method. */
internal class AwgFeatureInteropHost(
    private val owner: AwgNativeInteropTest,
) {
    fun run() {
        val args = InstrumentationRegistry.getArguments()
        val scenario = requireNotNull(args.getString("awgScenario"))
        val profile = requireNotNull(args.getString("awgProfile"))
        val mtu = requireNotNull(args.getString("awgMtu")).toInt()
        require(mtu in setOf(1280, 1420) && args.getString("awgCycle") == "0")
        require(scenario in AwgFeatureScenarios.names)
        require(scenario == "small-datagrams" || profile == "v3-cpa-trailers")
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.first())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        owner.assertNoActiveVpn(context)
        owner.awaitIpv4Underlay(context)
        assertNull("The runner must obtain real Android VPN authorization", VpnService.prepare(context))
        val config = owner.config(args, mtu)
        AwgFeatureScenarios.configure(config, scenario)
        val bindings = RipDpiAmneziaWgNativeBindings()
        val protection = AwgNativeInteropTest.RecordingVpnService()
        val handle = bindings.jniCreate(config.toString())
        assertTrue("real JNI create must return a handle", handle != 0L)
        var protectionToken = 0L
        var worker: Thread? = null
        val exit = AtomicInteger(-1)
        val started = System.nanoTime()
        try {
            protectionToken = RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect(protection)
            assertTrue("real JNI protection registration must return a token", protectionToken != 0L)
            worker = thread(name = "awg-feature-native") { exit.set(bindings.jniStart(handle)) }
            val feature =
                if (scenario == "timer-retry-exhaust") {
                    observeExhaustion(bindings, handle, exit)
                    JSONObject().put("phases", JSONArray())
                } else {
                    val listener = owner.awaitListener(bindings, handle, exit)
                    AwgFeatureScenarios { owner.socks(listener, 3, "0.0.0.0", 0) }.run(scenario)
                }
            assertEquals("the feature runtime must remain active until stop", -1, exit.get())
            feature
                .put("scenario", scenario)
                .put("completed", true)
                .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
            val evidence =
                JSONObject()
                    .put("nonce", args.getString("awgNonce"))
                    .put("profile", profile)
                    .put("scenario", scenario)
                    .put("mtu", mtu)
                    .put("cycle", 0)
                    .put("peer_port", requireNotNull(args.getString("awgPeerPort")).toInt())
                    .put("abi", Build.SUPPORTED_ABIS.first())
                    .put("api", Build.VERSION.SDK_INT)
                    .put("socket_protection", protection.observation())
                    .put("measurements", JSONArray())
                    .put("feature", feature)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("awgEvidence", evidence.toString()) },
            )
        } finally {
            try {
                bindings.jniStop(handle)
                worker?.join(5000)
                assertFalse("native shutdown must finish", worker?.isAlive == true)
                bindings.jniDestroy(handle)
            } finally {
                RipDpiAmneziaWgNativeBindings.jniUnregisterVpnProtect(protectionToken)
            }
        }
        assertEquals("native start must exit cleanly", 0, exit.get())
        owner.assertNoActiveVpn(context)
    }

    private fun observeExhaustion(
        bindings: RipDpiAmneziaWgNativeBindings,
        handle: Long,
        exit: AtomicInteger,
    ) {
        val deadline = System.nanoTime() + 7_500_000_000L
        while (System.nanoTime() < deadline) {
            val telemetry = bindings.jniPollTelemetry(handle)?.let(::JSONObject)
            assertFalse("lost responses must not establish readiness", telemetry?.optString("state") == "running")
            assertTrue(
                "lost responses must not publish a listener",
                telemetry == null || telemetry.isNull("listenerAddress"),
            )
            assertEquals("the runtime must keep waiting", -1, exit.get())
            Thread.sleep(25)
        }
    }
}
