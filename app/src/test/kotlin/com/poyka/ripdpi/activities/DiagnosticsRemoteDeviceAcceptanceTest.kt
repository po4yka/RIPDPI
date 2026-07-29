package com.poyka.ripdpi.activities

import com.poyka.ripdpi.services.RemoteDeviceAcceptanceDevice
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceGate
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DiagnosticsRemoteDeviceAcceptanceTest {
    @Test
    fun `binding starts gate and emits its redacted report`() {
        val gate = RecordingAcceptanceGate()
        val binding = DiagnosticsRemoteDeviceAcceptance(gate, FakeStringResolver())
        val scope = TestScope()
        var effect: DiagnosticsEffect? = null

        binding.start(scope)
        binding.share {
            effect = it
            true
        }

        assertSame(scope, gate.startedScope)
        assertEquals(
            "{\"format\":\"ripdpi_remote_device_acceptance_v2\"}",
            (effect as DiagnosticsEffect.ShareSummaryRequested).body,
        )
    }

    private class RecordingAcceptanceGate : RemoteDeviceAcceptanceGate {
        override val report =
            MutableStateFlow(
                RemoteDeviceAcceptanceReport(
                    device = RemoteDeviceAcceptanceDevice("SM-S928B", "XSG", 35, "arm64-v8a"),
                ),
            )
        var startedScope: CoroutineScope? = null

        override fun start(scope: CoroutineScope) {
            startedScope = scope
        }

        override fun renderRedactedReport(): String = "{\"format\":\"ripdpi_remote_device_acceptance_v2\"}"
    }
}
