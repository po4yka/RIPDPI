package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `:core:engine` -> `:core:engine-api` split.
 *
 * The runtime contracts ([RipDpiRelayRuntime], [RipDpiWarpRuntime],
 * [NetworkDiagnosticsBridge]) and the pure value objects exercised below now
 * live in `:core:engine-api`; their JNI-backed implementations stay in
 * `:core:engine`. Each assignment to a moved interface type fails to compile if
 * an implementation stops satisfying that contract, and the value-object cases
 * fail at run time if a relocated DTO can no longer be constructed from
 * `:core:engine`. Together they prove the moved interfaces still bind to the
 * same implementations after the module split.
 */
class EngineApiContractBindingTest {
    @Test
    fun ripDpiRelayImplementsMovedRuntimeContract() {
        val runtime: RipDpiRelayRuntime = RipDpiRelay(FakeRipDpiRelayBindings())
        assertTrue(runtime is RipDpiRelay)
    }

    @Test
    fun ripDpiWarpImplementsMovedRuntimeContract() {
        val runtime: RipDpiWarpRuntime = RipDpiWarp(FakeRipDpiWarpBindings())
        assertTrue(runtime is RipDpiWarp)
    }

    @Test
    fun networkDiagnosticsImplementsMovedBridgeContract() {
        val bridge: NetworkDiagnosticsBridge = NetworkDiagnostics(FakeNetworkDiagnosticsBindings())
        assertTrue(bridge is NetworkDiagnostics)
    }

    @Test
    fun movedValueObjectsRemainConstructibleFromEngine() {
        val endpoint = ResolvedRipDpiWarpEndpoint(host = "example.org", port = 443)
        assertEquals(443, endpoint.port)
        assertEquals(RipDpiRelayConfig(), RipDpiRelayConfig())
        assertEquals(RipDpiWarpConfig(), RipDpiWarpConfig())
    }
}
