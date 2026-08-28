package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.xray.DefaultXrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderDiagnosticsFixtures
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.ui.screens.xray.XrayProviderToolUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsXrayProviderControllerTest {
    @Test
    fun `live provider failure invalidates a previous healthy probe`() =
        runTest {
            val store = FakeServiceStateStore()
            val healthy = XrayProviderDiagnosticsFixtures.healthy
            store.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    xrayProviderSnapshot = healthy.snapshot,
                ),
            )
            val probes = DefaultXrayProviderProbeCoordinator().apply { register { healthy } }
            val controller = DiagnosticsXrayProviderController(backgroundScope, store, probes)
            backgroundScope.launch { controller.snapshot.collect {} }
            backgroundScope.launch { controller.probeReport.collect {} }
            runCurrent()
            controller.runProbe()
            runCurrent()
            assertNotNull(controller.probeReport.value)

            val failure = XrayProviderDiagnosticsFixtures.protectFailure.snapshot
            store.updateTelemetry(store.telemetry.value.copy(xrayProviderSnapshot = failure))
            runCurrent()

            assertNull(controller.probeReport.value)
            val model = XrayProviderToolUiModel(controller.snapshot.value, healthy)
            assertEquals(failure, model.report!!.snapshot)
        }

    @Test
    fun `timestamp refresh preserves probes but restart and stop invalidate them`() =
        runTest {
            val store = FakeServiceStateStore()
            val healthy = XrayProviderDiagnosticsFixtures.healthy
            store.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    xrayProviderSnapshot = healthy.snapshot,
                ),
            )
            val probes = DefaultXrayProviderProbeCoordinator().apply { register { healthy } }
            val controller = DiagnosticsXrayProviderController(backgroundScope, store, probes)
            runCurrent()
            controller.runProbe()
            runCurrent()
            store.updateTelemetry(
                store.telemetry.value.copy(
                    xrayProviderSnapshot = healthy.snapshot.copy(capturedAt = healthy.snapshot.capturedAt + 1),
                ),
            )
            runCurrent()
            assertNotNull(controller.probeReport.value)

            store.updateTelemetry(store.telemetry.value.copy(restartCount = store.telemetry.value.restartCount + 1))
            runCurrent()
            assertNull(controller.probeReport.value)
            controller.runProbe()
            runCurrent()
            assertNotNull(controller.probeReport.value)
            store.updateTelemetry(store.telemetry.value.copy(status = AppStatus.Halted, xrayProviderSnapshot = null))
            runCurrent()
            assertNull(controller.probeReport.value)
            assertEquals(false, XrayProviderToolUiModel(controller.snapshot.value, healthy).isVisible)
        }

    @Test
    fun `probe finishing after service stop cannot publish stale success`() =
        runTest {
            val store = FakeServiceStateStore()
            val healthy = XrayProviderDiagnosticsFixtures.healthy
            store.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    xrayProviderSnapshot = healthy.snapshot,
                ),
            )
            var calls = 0
            val probes =
                DefaultXrayProviderProbeCoordinator().apply {
                    register {
                        calls++
                        store.updateTelemetry(
                            store.telemetry.value.copy(status = AppStatus.Halted, xrayProviderSnapshot = null),
                        )
                        healthy
                    }
                }
            val controller = DiagnosticsXrayProviderController(backgroundScope, store, probes)
            runCurrent()
            controller.runProbe()
            runCurrent()
            assertNull(controller.probeReport.value)
            assertEquals(false, controller.probeRunning.value)
            controller.runProbe()
            runCurrent()
            assertEquals(1, calls)
        }

    @Test
    fun `probe finishing after registration changes cannot publish before telemetry refresh`() =
        runTest {
            for (rebind in listOf(false, true)) {
                val store = FakeServiceStateStore()
                val healthy = XrayProviderDiagnosticsFixtures.healthy
                store.updateTelemetry(
                    ServiceTelemetrySnapshot(
                        mode = Mode.VPN,
                        status = AppStatus.Running,
                        xrayProviderSnapshot = healthy.snapshot,
                    ),
                )
                val probes = DefaultXrayProviderProbeCoordinator()
                lateinit var runner: () -> XrayProviderProbeReport
                runner = {
                    probes.clear()
                    if (rebind) probes.register(runner)
                    healthy
                }
                probes.register(runner)
                val controller = DiagnosticsXrayProviderController(backgroundScope, store, probes)
                runCurrent()
                controller.runProbe()
                runCurrent()
                assertNull(controller.probeReport.value)
                assertEquals(false, controller.probeRunning.value)
                assertEquals(healthy.snapshot, store.telemetry.value.xrayProviderSnapshot)

                probes.register { healthy }
                controller.runProbe()
                runCurrent()
                assertEquals(healthy, controller.probeReport.value)
            }
        }
}
