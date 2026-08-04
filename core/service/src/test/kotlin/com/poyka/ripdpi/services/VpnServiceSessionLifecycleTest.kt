package com.poyka.ripdpi.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the VPN protect-path lifecycle ORDERING enforced by
 * [VpnServiceSessionLifecycle] (audit finding TEST-1).
 *
 * The lifecycle obtains its [VpnProtectSocketServer] and runtime coordinator
 * through a Hilt `EntryPoint`, and the server binds a real Unix domain socket on
 * `start()`. Constructing the full session under Robolectric would bind a real
 * UDS and drag in the whole Hilt graph — both of which would muddy the ordering
 * signal this test exists to protect. So the two orderable sequences live in the
 * small same-package seams [advertiseProtectPath] and [withdrawProtectPath],
 * which the lifecycle delegates to verbatim. This test drives those seams with
 * the SAME wiring the production call sites use, plus the real
 * [ActiveProtectSocketPathProvider] and [VpnServiceSessionCleanup], so the
 * recorded order is the order that ships.
 *
 * Two invariants:
 *  - start: server starts listening BEFORE the env path is advertised.
 *  - teardown: env path is withdrawn BEFORE the server stops.
 *
 * No Robolectric and no new test dependency are required — the seams and the two
 * collaborators are directly constructible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnServiceSessionLifecycleTest {
    private sealed interface Event {
        data object ServerStart : Event

        data object PathSet : Event

        data object PathClear : Event

        data object ServerStop : Event

        data object Unregister : Event

        data object Register : Event

        data object ProfileRecover : Event

        data object UnderlayReady : Event

        data object RuntimeStart : Event

        data object StartupReady : Event

        data object StartupRejected : Event
    }

    private val events = mutableListOf<Event>()

    @Test
    fun `sticky recovery waits for app startup before reading persisted profiles`() =
        runTest {
            val releaseStartup = CompletableDeferred<Unit>()
            val recovery =
                launch {
                    recoverProfileMutationsAndAwaitUnderlayThenStart(
                        awaitStartupReadiness = {
                            events += Event.StartupReady
                            releaseStartup.await()
                            true
                        },
                        onStartupNotReady = { events += Event.StartupRejected },
                        recoverProfileMutations = { events += Event.ProfileRecover },
                        awaitRecoveryUnderlay = { events += Event.UnderlayReady },
                        startRuntime = { events += Event.RuntimeStart },
                    )
                }

            runCurrent()
            assertEquals(listOf(Event.StartupReady), events)

            releaseStartup.complete(Unit)
            recovery.join()
            assertEquals(
                listOf(Event.StartupReady, Event.ProfileRecover, Event.UnderlayReady, Event.RuntimeStart),
                events,
            )
        }

    @Test
    fun `failed app startup rejects sticky runtime start`() =
        runTest {
            recoverProfileMutationsAndAwaitUnderlayThenStart(
                awaitStartupReadiness = {
                    events += Event.StartupReady
                    false
                },
                onStartupNotReady = { events += Event.StartupRejected },
                recoverProfileMutations = { events += Event.ProfileRecover },
                awaitRecoveryUnderlay = { events += Event.UnderlayReady },
                startRuntime = { events += Event.RuntimeStart },
            )

            assertEquals(listOf(Event.StartupReady, Event.StartupRejected), events)
        }

    @Test
    fun `recovery start waits for profile recovery and underlay before runtime`() =
        runTest {
            recoverProfileMutationsAndAwaitUnderlayThenStart(
                recoverProfileMutations = { events += Event.ProfileRecover },
                awaitRecoveryUnderlay = { events += Event.UnderlayReady },
                startRuntime = { events += Event.RuntimeStart },
            )

            assertEquals(listOf(Event.ProfileRecover, Event.UnderlayReady, Event.RuntimeStart), events)
        }

    @Test
    fun `recovery failure prevents runtime start`() =
        runTest {
            val failure = IllegalStateException("recovery failed")

            val thrown =
                runCatching {
                    recoverProfileMutationsAndAwaitUnderlayThenStart(
                        recoverProfileMutations = {
                            events += Event.ProfileRecover
                            throw failure
                        },
                        awaitRecoveryUnderlay = { events += Event.UnderlayReady },
                        startRuntime = { events += Event.RuntimeStart },
                    )
                }.exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(listOf(Event.ProfileRecover), events)
        }

    @Test
    fun `underlay failure prevents runtime start`() =
        runTest {
            val failure = IllegalStateException("underlay unavailable")

            val thrown =
                runCatching {
                    recoverProfileMutationsAndAwaitUnderlayThenStart(
                        recoverProfileMutations = { events += Event.ProfileRecover },
                        awaitRecoveryUnderlay = {
                            events += Event.UnderlayReady
                            throw failure
                        },
                        startRuntime = { events += Event.RuntimeStart },
                    )
                }.exceptionOrNull()

            assertEquals(failure::class, thrown?.let { it::class })
            assertEquals(failure.message, thrown?.message)
            assertEquals(listOf(Event.ProfileRecover, Event.UnderlayReady), events)
        }

    @Test
    fun `recovery starts runtime when underlay readiness times out`() =
        runTest {
            recoverProfileMutationsAndAwaitUnderlayThenStart(
                recoverProfileMutations = { events += Event.ProfileRecover },
                awaitRecoveryUnderlay = { awaitCancellation() },
                startRuntime = { events += Event.RuntimeStart },
                underlayTimeoutMillis = 1L,
            )

            assertEquals(listOf(Event.ProfileRecover, Event.RuntimeStart), events)
        }

    /**
     * Drives the advertise seam exactly as `createShellDelegate` does:
     * `startProtectSocketServer = socketServer::start` then
     * `advertiseProtectPath = { provider.set(socketServer.socketPath) }`.
     */
    @Test
    fun `advertise starts protect socket server before advertising the env path`() {
        val provider = ActiveProtectSocketPathProvider()
        val socketPath = "/data/user/0/com.poyka.ripdpi/files/protect_path"
        var started = false

        advertiseProtectPath(
            startProtectSocketServer = {
                started = true
                events += Event.ServerStart
            },
            advertiseProtectPath = {
                // Mirrors the production lambda: the env path is only published
                // once the server's start() has returned.
                assertEquals(
                    "env path must not be advertised before the protect server starts listening",
                    true,
                    started,
                )
                provider.set(socketPath)
                events += Event.PathSet
            },
        )

        assertEquals(listOf(Event.ServerStart, Event.PathSet), events)
        assertEquals(
            "provider must advertise the path once the server is up",
            socketPath,
            provider.current(),
        )
    }

    @Test
    fun `registration failure withdraws path and stops socket server`() {
        val provider = ActiveProtectSocketPathProvider()
        val registrationFailure = IllegalStateException("registration failed")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                establishProtectPath(
                    startProtectSocketServer = { events += Event.ServerStart },
                    advertiseProtectPath = {
                        provider.set("/data/user/0/com.poyka.ripdpi/files/protect_path")
                        events += Event.PathSet
                    },
                    registerNativeProtect = {
                        events += Event.Register
                        throw registrationFailure
                    },
                    rollbackProtection = {
                        provider.clear()
                        events += Event.PathClear
                        events += Event.Unregister
                        events += Event.ServerStop
                    },
                )
            }

        assertSame(registrationFailure, thrown)
        assertEquals(
            listOf(
                Event.ServerStart,
                Event.PathSet,
                Event.Register,
                Event.PathClear,
                Event.Unregister,
                Event.ServerStop,
            ),
            events,
        )
        assertNull(provider.current())
    }

    @Test
    fun `registration failure retains rollback failure as suppressed`() {
        val registrationFailure = IllegalStateException("registration failed")
        val rollbackFailure = IllegalArgumentException("rollback failed")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                establishProtectPath(
                    startProtectSocketServer = {},
                    advertiseProtectPath = {},
                    registerNativeProtect = { throw registrationFailure },
                    rollbackProtection = { throw rollbackFailure },
                )
            }

        assertSame(registrationFailure, thrown)
        assertEquals(listOf(rollbackFailure), thrown.suppressed.toList())
    }

    /**
     * Drives the withdraw seam exactly as `cleanupNativeProtect` does, threading
     * the REAL [VpnServiceSessionCleanup] so the recorded order is the real
     * `clear -> unregister -> stop` order (the server stop is the last step,
     * deepest inside the cleanup). Asserts the env path is withdrawn before the
     * server is asked to stop.
     */
    @Test
    fun `withdraw clears the env path before stopping the protect socket server`() {
        val provider = ActiveProtectSocketPathProvider()
        provider.set("/data/user/0/com.poyka.ripdpi/files/protect_path")
        val cleanup = VpnServiceSessionCleanup()

        withdrawProtectPath(
            withdrawProtectPath = {
                provider.clear()
                events += Event.PathClear
            },
            cleanupNativeProtect = {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = { events += Event.Unregister },
                    stopProtectSocketServer = { events += Event.ServerStop },
                )
            },
        )

        assertEquals(
            listOf(Event.PathClear, Event.Unregister, Event.ServerStop),
            events,
        )
        assertNull("env path must be withdrawn after teardown", provider.current())

        val clearIndex = events.indexOf(Event.PathClear)
        val stopIndex = events.indexOf(Event.ServerStop)
        assertEquals(
            "path clear must be recorded before server stop so no helper sees a stale path",
            true,
            clearIndex < stopIndex,
        )
    }

    /**
     * Full session shape: advertise on start, then withdraw on teardown, against
     * one shared provider — pins the round-trip ordering and the final cleared
     * state a relay helper would observe across a session.
     */
    @Test
    fun `session round trip advertises on start and withdraws on teardown in order`() {
        val provider = ActiveProtectSocketPathProvider()
        val socketPath = "/data/user/0/com.poyka.ripdpi/files/protect_path"
        val cleanup = VpnServiceSessionCleanup()

        advertiseProtectPath(
            startProtectSocketServer = { events += Event.ServerStart },
            advertiseProtectPath = {
                provider.set(socketPath)
                events += Event.PathSet
            },
        )
        assertEquals(socketPath, provider.current())

        withdrawProtectPath(
            withdrawProtectPath = {
                provider.clear()
                events += Event.PathClear
            },
            cleanupNativeProtect = {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = { events += Event.Unregister },
                    stopProtectSocketServer = { events += Event.ServerStop },
                )
            },
        )

        assertEquals(
            listOf(
                Event.ServerStart,
                Event.PathSet,
                Event.PathClear,
                Event.Unregister,
                Event.ServerStop,
            ),
            events,
        )
        assertNull(provider.current())
    }
}
