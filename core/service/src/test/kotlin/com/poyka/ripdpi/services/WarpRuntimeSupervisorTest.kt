package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.WarpRouteModeRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private fun sampleWarpConfig(): RipDpiWarpConfig =
        RipDpiWarpConfig(
            enabled = true,
            routeMode = WarpRouteModeRules,
            routeHosts = "example.com",
        )
}
