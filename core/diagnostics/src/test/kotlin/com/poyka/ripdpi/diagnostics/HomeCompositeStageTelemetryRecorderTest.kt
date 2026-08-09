package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositeStageTelemetryRecorderTest {
    @Test
    fun `record persists runtime telemetry with explicit home stage scope`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    tunnelStats =
                        TunnelStats(
                            txPackets = 11,
                            txBytes = 12,
                            rxPackets = 13,
                            rxBytes = 14,
                        ),
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            resolverId = "resolver-1",
                            resolverProtocol = "doh",
                            resolverLatencyMs = 37,
                        ),
                ),
            )
            val recorder = HomeCompositeStageTelemetryRecorder(stores, serviceStateStore)

            recorder.record(
                runId = "home-run-1",
                stageKey = "automatic_audit",
                sessionId = "scan-session-1",
                state = DiagnosticsHomeCompositeStageStatus.COMPLETED,
                createdAt = 1234L,
            )

            val sample = stores.telemetryState.value.single()
            assertEquals("home-run-1", sample.diagnosticsRunId)
            assertEquals("automatic_audit", sample.diagnosticsStageKey)
            assertEquals("scan-session-1", sample.sessionId)
            assertEquals("COMPLETED", sample.connectionState)
            assertEquals("resolver-1", sample.resolverId)
            assertEquals(11L, sample.txPackets)
            assertEquals(14L, sample.rxBytes)
            assertEquals(1234L, sample.createdAt)
        }
}
