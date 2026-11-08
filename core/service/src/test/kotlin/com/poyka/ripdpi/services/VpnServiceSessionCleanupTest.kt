package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class VpnServiceSessionCleanupTest {
    @Test
    fun pendingRuntimeKeepsProtectionUntilAReleaseRetryCompletes() =
        runTest {
            val cleanup = VpnServiceSessionCleanup()
            val endpoint = File.createTempFile("vpn-protect-owner", ".marker")
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { runtime ->
                var pending = true
                val stop: suspend () -> RuntimeStopOutcome = {
                    if (pending) RuntimeStopOutcome.CleanupPending else RuntimeStopOutcome.FullyReleased
                }
                try {
                    val failure =
                        runCatching {
                            cleanup.revokeSession({ stop() }, runtime::close) { check(endpoint.delete()) }
                        }.exceptionOrNull()
                    assertTrue(failure is RuntimeCleanupPendingException)
                    assertFalse(runtime.isClosed)
                    assertTrue(endpoint.exists())

                    pending = false
                    cleanup.revokeSession({ stop() }, runtime::close) { check(endpoint.delete()) }
                    assertTrue(runtime.isClosed)
                    assertFalse(endpoint.exists())
                } finally {
                    endpoint.delete()
                }
            }
        }

    @Test
    fun unexpectedStopFailureKeepsResourcesForAnExplicitReleaseRetry() {
        val cleanup = VpnServiceSessionCleanup()
        val stopFailure = IllegalStateException("stop failed")
        val endpoint = File.createTempFile("vpn-protect-owner", ".marker")
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { runtime ->
            var stopFails = true
            val stop: suspend () -> RuntimeStopOutcome = {
                if (stopFails) throw stopFailure
                RuntimeStopOutcome.FullyReleased
            }
            try {
                val failure =
                    runCatching {
                        cleanup.destroyRunningSession(
                            stopRuntime = { stop() },
                            destroyCoordinator = runtime::close,
                            cleanupSocketProtection = { check(endpoint.delete()) },
                            timeoutMillis = 1_000L,
                        )
                    }.exceptionOrNull()
                assertEquals(stopFailure.javaClass, failure?.javaClass)
                assertEquals(stopFailure.message, failure?.message)
                assertSame(stopFailure, failure?.cause ?: failure)
                assertFalse(runtime.isClosed)
                assertTrue(endpoint.exists())

                stopFails = false
                cleanup.destroyRunningSession(
                    stopRuntime = { stop() },
                    destroyCoordinator = runtime::close,
                    cleanupSocketProtection = { check(endpoint.delete()) },
                    timeoutMillis = 1_000L,
                )
                assertTrue(runtime.isClosed)
                assertFalse(endpoint.exists())
            } finally {
                endpoint.delete()
            }
        }
    }

    @Test
    fun coordinatorDestroyFailureKeepsProtectionAndCanRetry() {
        val cleanup = VpnServiceSessionCleanup()
        val destroyFailure = IllegalStateException("destroy failed")
        val endpoint = File.createTempFile("vpn-protect-owner", ".marker")
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { runtime ->
            try {
                val failure =
                    runCatching {
                        cleanup.destroySession({ throw destroyFailure }) { check(endpoint.delete()) }
                    }.exceptionOrNull()
                assertSame(destroyFailure, failure)
                assertTrue(endpoint.exists())

                cleanup.destroySession(runtime::close) { check(endpoint.delete()) }
                assertTrue(runtime.isClosed)
                assertFalse(endpoint.exists())
            } finally {
                endpoint.delete()
            }
        }
    }

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
    fun nativeProtectCleanupAttemptsBothOwnersAndRetriesOnlyFailure() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()
        val unregisterFailure = IllegalStateException("unregister failed")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = {
                        calls += "unregister"
                        throw unregisterFailure
                    },
                    stopProtectSocketServer = { calls += "protect-stop" },
                )
            }

        assertSame(unregisterFailure, thrown)
        cleanup.cleanupNativeProtect(
            unregisterNativeProtect = { calls += "unregister-retry" },
            stopProtectSocketServer = { calls += "protect-stop-again" },
        )
        assertEquals(listOf("unregister", "protect-stop", "unregister-retry"), calls)
    }

    @Test
    fun nativeProtectCleanupPreservesBothFailuresAndRetriesBothOwners() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()
        val unregisterFailure = IllegalStateException("unregister failed")
        val stopFailure = IllegalArgumentException("stop failed")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = {
                        calls += "unregister"
                        throw unregisterFailure
                    },
                    stopProtectSocketServer = {
                        calls += "protect-stop"
                        throw stopFailure
                    },
                )
            }

        assertSame(unregisterFailure, thrown)
        assertEquals(listOf(stopFailure), thrown.suppressed.toList())
        cleanup.cleanupNativeProtect(
            unregisterNativeProtect = { calls += "unregister-retry" },
            stopProtectSocketServer = { calls += "protect-stop-retry" },
        )
        assertEquals(
            listOf("unregister", "protect-stop", "unregister-retry", "protect-stop-retry"),
            calls,
        )
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
    fun serviceDestroyStopsRuntimeBeforeRemovingSocketProtection() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()

        cleanup.destroyRunningSession(
            stopRuntime = {
                calls += "runtime-stop"
                RuntimeStopOutcome.FullyReleased
            },
            destroyCoordinator = { calls += "runtime-destroy" },
            cleanupSocketProtection = { calls += "protect-cleanup" },
            timeoutMillis = 1_000L,
        )

        assertEquals(listOf("runtime-stop", "runtime-destroy", "protect-cleanup"), calls)
    }

    @Test
    fun runtimeStopTimeoutRetainsCoordinatorAndSocketProtection() {
        val calls = mutableListOf<String>()
        val cleanup = VpnServiceSessionCleanup()

        val thrown =
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    cleanup.destroyRunningSession(
                        stopRuntime = {
                            calls += "runtime-stop"
                            awaitCancellation()
                        },
                        destroyCoordinator = { calls += "runtime-destroy" },
                        cleanupSocketProtection = { calls += "protect-cleanup" },
                        timeoutMillis = 50L,
                    )
                }
            }

        assertTrue(thrown is TimeoutCancellationException)
        assertEquals(
            "a timed-out runtime has not confirmed release and must keep protection",
            listOf("runtime-stop"),
            calls,
        )
    }

    @Test
    fun sessionRevokeStopsRuntimeBeforeRemovingSocketProtection() =
        runTest {
            val calls = mutableListOf<String>()
            val cleanup = VpnServiceSessionCleanup()

            cleanup.revokeSession(
                stopRuntime = {
                    calls += "runtime-stop"
                    RuntimeStopOutcome.FullyReleased
                },
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
    fun runtimeStopFailureRetainsCoordinatorAndSocketProtection() =
        runTest {
            val calls = mutableListOf<String>()
            val cleanup = VpnServiceSessionCleanup()
            val stopFailure = IllegalStateException("stop failed")

            val result =
                runCatching {
                    cleanup.revokeSession(
                        stopRuntime = {
                            calls += "runtime-stop"
                            throw stopFailure
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

            assertSame(stopFailure, result.exceptionOrNull())
            assertEquals(
                "a failed runtime stop keeps protection until a later confirmed release",
                listOf("runtime-stop"),
                calls,
            )
        }

    @Test
    fun outerCancellationPropagatesWithoutTeardown() =
        runTest {
            val calls = mutableListOf<String>()
            val cleanup = VpnServiceSessionCleanup()

            val result =
                runCatching {
                    cleanup.revokeSession(
                        stopRuntime = {
                            calls += "runtime-stop"
                            throw CancellationException("outer scope cancelled")
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

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(
                "outer cancellation must propagate immediately; destroyRunningSession owns teardown then",
                listOf("runtime-stop"),
                calls,
            )
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
                stopRuntime = {
                    calls += "runtime-stop"
                    RuntimeStopOutcome.FullyReleased
                },
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
