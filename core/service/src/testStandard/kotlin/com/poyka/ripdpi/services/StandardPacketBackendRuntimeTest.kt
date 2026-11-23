package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardPacketBackendRuntimeTest {
    @Test
    fun `uses the visual configuration for a saved external command`() {
        assertFalse(StandardPacketBackendRuntime.acceptsCommandLine("nfqws2 --wf-tcp=443"))
        assertFalse(StandardPacketBackendRuntime.acceptsCommandLine("/data/local/tmp/other-proxy --port 1080"))
    }

    @Test
    fun `keeps arguments for the in-process proxy`() {
        assertTrue(StandardPacketBackendRuntime.acceptsCommandLine("--port 1080"))
        assertTrue(StandardPacketBackendRuntime.acceptsCommandLine("/data/local/tmp/ciadpi --port 1080"))
    }
}
