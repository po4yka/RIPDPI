package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnServiceSessionCleanupTest {
    @Test
    fun nativeProtectCleanupIsIdempotent() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()

        repeat(2) {
            cleanup.cleanupNativeProtect(
                unregisterNativeProtect = { calls += "unregister" },
                stopProtectSocketServer = { calls += "protect-stop" },
            )
        }

        assertEquals(listOf("unregister", "protect-stop"), calls)
    }

    @Test
    fun coordinatorDestroyIsIdempotentAcrossRevokeAndDestroy() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()

        cleanup.destroyCoordinator { calls += "destroy" }
        cleanup.destroyCoordinator { calls += "destroy" }

        assertEquals(listOf("destroy"), calls)
    }
}
