package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-transition round-trip for [VpnProviderState].
 *
 * Valid sequence: Stopped -> Starting -> Running -> Stopping -> Stopped.
 * Invalid skips must be rejected.
 */
class VpnProviderStateTransitionTest {
    @Test
    fun `Stopped can transition to Starting`() {
        assertTrue(VpnProviderState.Stopped.canTransitionTo(VpnProviderState.Starting))
    }

    @Test
    fun `Starting can transition to Running`() {
        assertTrue(VpnProviderState.Starting.canTransitionTo(VpnProviderState.Running))
    }

    @Test
    fun `Running can transition to Stopping`() {
        assertTrue(VpnProviderState.Running.canTransitionTo(VpnProviderState.Stopping))
    }

    @Test
    fun `Stopping can transition to Stopped`() {
        assertTrue(VpnProviderState.Stopping.canTransitionTo(VpnProviderState.Stopped))
    }

    @Test
    fun `Stopped cannot skip to Running`() {
        assertFalse(VpnProviderState.Stopped.canTransitionTo(VpnProviderState.Running))
    }

    @Test
    fun `Stopped cannot skip to Stopping`() {
        assertFalse(VpnProviderState.Stopped.canTransitionTo(VpnProviderState.Stopping))
    }

    @Test
    fun `Starting cannot skip to Stopping`() {
        assertFalse(VpnProviderState.Starting.canTransitionTo(VpnProviderState.Stopping))
    }

    @Test
    fun `Running cannot skip to Stopped`() {
        assertFalse(VpnProviderState.Running.canTransitionTo(VpnProviderState.Stopped))
    }

    @Test
    fun `full round-trip Stopped to Stopped via valid path`() {
        val path =
            listOf(
                VpnProviderState.Stopped,
                VpnProviderState.Starting,
                VpnProviderState.Running,
                VpnProviderState.Stopping,
                VpnProviderState.Stopped,
            )
        for (i in 0 until path.size - 1) {
            assertTrue(
                "Expected ${path[i]} -> ${path[i + 1]} to be valid",
                path[i].canTransitionTo(path[i + 1]),
            )
        }
        assertEquals(VpnProviderState.Stopped, path.last())
    }

    @Test
    fun `Starting can transition to Stopped on error`() {
        // Allow abort from Starting directly to Stopped for error paths.
        assertTrue(VpnProviderState.Starting.canTransitionTo(VpnProviderState.Stopped))
    }
}
