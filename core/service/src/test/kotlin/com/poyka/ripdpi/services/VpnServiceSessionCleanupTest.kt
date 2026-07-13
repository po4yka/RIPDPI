package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun sessionDestroyReleasesRuntimeCoordinatorBeforeRemovingSocketProtection() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()

        cleanup.destroySession(
            destroyCoordinator = { calls += "runtime-destroy" },
            cleanupSocketProtection = {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = { calls += "protect-unregister" },
                    stopProtectSocketServer = { calls += "protect-stop" },
                )
            },
        )

        assertEquals(
            listOf("runtime-destroy", "protect-unregister", "protect-stop"),
            calls,
        )
    }

    @Test
    fun sessionRevokeStopsRuntimeBeforeRemovingSocketProtection() =
        runTest {
            val calls = mutableListOf<String>()
            val cleanup = VpnServiceSessionCleanup()

            cleanup.revokeSession(
                stopRuntime = { calls += "runtime-stop" },
                destroyCoordinator = { calls += "runtime-destroy" },
                cleanupSocketProtection = {
                    cleanup.cleanupNativeProtect(
                        unregisterNativeProtect = { calls += "protect-unregister" },
                        stopProtectSocketServer = { calls += "protect-stop" },
                    )
                },
            )

            assertEquals(
                listOf("runtime-stop", "runtime-destroy", "protect-unregister", "protect-stop"),
                calls,
            )
        }

    @Test
    fun runtimeStopFailureKeepsSocketProtectionRegistered() =
        runTest {
            val calls = mutableListOf<String>()
            val cleanup = VpnServiceSessionCleanup()

            val result =
                runCatching {
                    cleanup.revokeSession(
                        stopRuntime = {
                            calls += "runtime-stop"
                            error("stop failed")
                        },
                        destroyCoordinator = { calls += "runtime-destroy" },
                        cleanupSocketProtection = {
                            cleanup.cleanupNativeProtect(
                                unregisterNativeProtect = { calls += "protect-unregister" },
                                stopProtectSocketServer = { calls += "protect-stop" },
                            )
                        },
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(listOf("runtime-stop"), calls)
        }

    @Test
    fun revokeFollowedByDestroyDoesNotRepeatRuntimeOrProtectionCleanup() =
        runTest {
            val calls = mutableListOf<String>()
            val cleanup = VpnServiceSessionCleanup()
            val cleanupSocketProtection = {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = { calls += "protect-unregister" },
                    stopProtectSocketServer = { calls += "protect-stop" },
                )
            }

            cleanup.revokeSession(
                stopRuntime = { calls += "runtime-stop" },
                destroyCoordinator = { calls += "runtime-destroy" },
                cleanupSocketProtection = cleanupSocketProtection,
            )
            cleanup.destroySession(
                destroyCoordinator = { calls += "runtime-destroy" },
                cleanupSocketProtection = cleanupSocketProtection,
            )

            assertEquals(
                listOf("runtime-stop", "runtime-destroy", "protect-unregister", "protect-stop"),
                calls,
            )
        }
}
