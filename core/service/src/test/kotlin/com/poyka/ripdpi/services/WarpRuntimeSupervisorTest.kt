package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.WarpRouteModeRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WarpRuntimeSupervisorTest {
    @Test
    fun explicitStopReportsExpectedWarpExitCause() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val warpFactory = TestRipDpiWarpFactory()
            val supervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = warpFactory,
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(sampleWarpConfig()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            assertEquals(listOf(SupervisorExitCause.ExpectedStop), exits)
        }

    @Test
    fun nonZeroExitReportsWarpCrashCause() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val warpFactory = TestRipDpiWarpFactory()
            val supervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = warpFactory,
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(sampleWarpConfig()) { exits += it }
            warpFactory.lastRuntime.complete(29)
            runCurrent()
            advanceUntilIdle()

            assertEquals(listOf(SupervisorExitCause.Crash(29)), exits)
        }

    @Test
    fun repeatedStartStopRecoversAfterScriptedWarpCrash() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val warpFactory = TestRipDpiWarpFactory()
            val supervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = warpFactory,
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(sampleWarpConfig()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            supervisor.start(sampleWarpConfig()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            assertEquals(2, warpFactory.runtimes.size)
            assertEquals(1, warpFactory.runtimes[0].stopCount)
            assertEquals(1, warpFactory.runtimes[1].stopCount)
            assertEquals(
                listOf(
                    SupervisorExitCause.ExpectedStop,
                    SupervisorExitCause.ExpectedStop,
                ),
                exits,
            )
        }

    @Test
    fun pollTelemetryReturnsEngineErrorWhenWarpRuntimeThrows() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val warpFactory = TestRipDpiWarpFactory()
            val supervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = warpFactory,
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )

            supervisor.start(sampleWarpConfig()) {}
            warpFactory.lastRuntime.telemetryFailure = IOException("warp telemetry crash")

            val telemetry = supervisor.pollTelemetry()

            assertTrue(telemetry is RuntimeTelemetryOutcome.EngineError)
            assertEquals("warp telemetry crash", (telemetry as RuntimeTelemetryOutcome.EngineError).message)
        }

    @Test
    fun pollTelemetryReturnsNoDataWhenWarpRuntimeIsMissing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val supervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = TestRipDpiWarpFactory(),
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )

            assertEquals(RuntimeTelemetryOutcome.NoData, supervisor.pollTelemetry())
        }

    private fun sampleWarpConfig(): RipDpiWarpConfig =
        RipDpiWarpConfig(
            enabled = true,
            routeMode = WarpRouteModeRules,
            routeHosts = "example.com",
        )
}
