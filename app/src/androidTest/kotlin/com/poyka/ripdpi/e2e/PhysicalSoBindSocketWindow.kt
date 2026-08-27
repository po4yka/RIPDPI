package com.poyka.ripdpi.e2e

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Host captures both TCP tables while these sockets are live; files are private and never exported. */
internal fun observePhysicalSoBindDenial(
    context: Context,
    host: String,
    controlPort: Int,
    deniedPort: Int,
    probe: () -> AppProcessTcpProbeResult,
): AppProcessTcpProbeResult {
    val runId = InstrumentationRegistry.getArguments().getString("ripdpi.soBindRunId").orEmpty()
    assertTrue(runId.matches(Regex("[0-9a-f]{32}")))
    assertTrue("Positive control must use a distinct endpoint", controlPort != deniedPort)
    val address = InetAddress.getByName(host)
    val family = if (address.address.size == 16) "ipv6" else "ipv4"
    val window = File(context.filesDir, "so-bind-socket-window.json")
    val temporary = File(context.filesDir, "so-bind-socket-window.tmp")
    val acknowledgement = File(context.filesDir, "so-bind-socket-ack.txt")
    val identity =
        JSONObject()
            .put("runId", runId)
            .put("family", family)
            .put("uid", Process.myUid())
            .put("host", address.hostAddress)
            .put("controlPort", controlPort)
            .put("deniedPort", deniedPort)

    fun publish(phase: String) {
        temporary.writeText(identity.put("phase", phase).toString())
        check(temporary.renameTo(window)) { "Could not publish private socket window" }
    }

    fun awaitAcknowledgement(phase: String) {
        awaitUntil(timeoutMs = 15_000L, failureMessage = { "Host socket capture missing or rejected: $phase" }) {
            acknowledgement.exists() && acknowledgement.readText() == "$runId:$family:$phase"
        }
    }

    Socket().use { control ->
        control.connect(InetSocketAddress(address, controlPort), 5_000)
        control.soTimeout = 5_000
        // Fixture SOCKS greeting confirms the established positive control reaches its peer.
        control.getOutputStream().write(byteArrayOf(5, 1, 0))
        assertEquals(5, control.getInputStream().read())
        assertEquals(0, control.getInputStream().read())
        publish("ready")
        awaitAcknowledgement("start")
        publish("active")
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        var result: AppProcessTcpProbeResult
        do {
            result = probe()
            assertFalse("Denied socket unexpectedly connected during host observation", result.ok)
            assertEquals("tun0", result.boundDevice)
            assertTrue(result.failureStage in setOf("connect", "send", "receive"))
        } while (SystemClock.elapsedRealtime() < deadline)
        publish("done")
        awaitAcknowledgement("done")
        return result
    }
}
