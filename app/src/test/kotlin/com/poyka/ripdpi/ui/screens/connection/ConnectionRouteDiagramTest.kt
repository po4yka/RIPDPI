package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.Mode
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRouteDiagramTest {
    @Test
    fun `the diagram is off and unblocked for a stopped service with no test`() {
        assertEquals(
            RouteDiagramUiState(scope = Mode.VPN, path = ConnectionPath.Direct),
            ConnectionUiState().toRouteDiagram(ConnectionPath.Direct),
        )
    }

    @Test
    fun `a running service powers the diagram and a reconnect marks it busy`() {
        val state =
            ConnectionUiState(runningMode = Mode.Proxy, runtimeOperation = ConnectionRuntimeOperation.Reconnecting)
                .toRouteDiagram(ConnectionPath.Relay)

        assertTrue(state.powered)
        assertTrue(state.busy)
        assertFalse(state.relayReady)
    }

    @Test
    fun `direct is blocked only when the test on this network has no passing lane`() {
        val blocked = lanes(StrategyLaneOutcome.Blocked, StrategyLaneOutcome.Skipped)

        assertTrue(ConnectionUiState(strategyLanes = blocked).toRouteDiagram(ConnectionPath.Direct).directBlocked)
        assertFalse(
            ConnectionUiState(strategyLanes = blocked.copy(otherNetwork = true))
                .toRouteDiagram(ConnectionPath.Direct)
                .directBlocked,
        )
        assertFalse(
            ConnectionUiState(strategyLanes = lanes(StrategyLaneOutcome.Blocked, StrategyLaneOutcome.Partial))
                .toRouteDiagram(ConnectionPath.Direct)
                .directBlocked,
        )
    }

    @Test
    fun `the plug travels to the other socket, powers up, and comes to rest`() {
        val motion = RouteMotion(target = 60f, power = 0f)
        var settled = false
        var frames = 0
        while (!settled && frames < 600) {
            settled = motion.step(dt = 0.016f, target = 160f, wantedPower = 1f)
            frames++
        }

        assertTrue("still moving after $frames frames", settled)
        assertEquals(160f, motion.plugY, 0.1f)
        assertEquals(0f, motion.plugX, 0.1f)
        assertEquals(1f, motion.power, 0f)
    }

    @Test
    fun `a dragged plug stays where the finger put it`() {
        val motion = RouteMotion(target = 60f, power = 0f)
        motion.dragging = true
        motion.plugY = 120f

        assertFalse(motion.step(dt = 0.016f, target = 60f, wantedPower = 0f))
        assertEquals(120f, motion.plugY, 0f)
    }

    private fun lanes(vararg outcomes: StrategyLaneOutcome) =
        StrategyLanesUiState(
            lanes =
                persistentListOf(
                    *outcomes
                        .map { StrategyLaneUiState("lane", it, 0, 6, null, recommended = false) }
                        .toTypedArray(),
                ),
            finishedAt = 1L,
        )
}
