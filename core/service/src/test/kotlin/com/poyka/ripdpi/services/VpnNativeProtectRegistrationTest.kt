package com.poyka.ripdpi.services

import android.net.VpnService
import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [VpnNativeProtectRegistration].
 *
 * JNI statics (`RipDpiProxyNativeBindings.jniRegisterVpnProtect` etc.) are
 * `external` companion methods that cannot be intercepted without mockk.
 * Instead, the object exposes `internal var` function-reference hooks
 * (same package = direct access) that are replaced here with counting fakes.
 * No new test dependency is required.
 *
 * Robolectric is used solely to satisfy the [VpnService] type required by
 * [VpnNativeProtectRegistration.register]; the fakes never invoke the service's
 * real Android methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class VpnNativeProtectRegistrationTest {
    /** Trivial VpnService subclass — only used as a typed argument, never started. */
    private class FakeVpnService : VpnService()

    private val fakeService = FakeVpnService()

    private val proxyRegisterCalls = mutableListOf<Long>()
    private val relayRegisterCalls = mutableListOf<Long>()
    private val warpRegisterCalls = mutableListOf<Long>()
    private val awgRegisterCalls = mutableListOf<Long>()
    private val proxyUnregisterCalls = mutableListOf<Long>()
    private val relayUnregisterCalls = mutableListOf<Long>()
    private val warpUnregisterCalls = mutableListOf<Long>()
    private val awgUnregisterCalls = mutableListOf<Long>()

    private val nextProxyToken = AtomicLong(1L)
    private val nextRelayToken = AtomicLong(50L)
    private val nextWarpToken = AtomicLong(100L)
    private val nextAwgToken = AtomicLong(200L)

    // Saved originals restored in tearDown.
    private val savedProxyRegister = VpnNativeProtectRegistration.proxyRegister
    private val savedRelayRegister = VpnNativeProtectRegistration.relayRegister
    private val savedWarpRegister = VpnNativeProtectRegistration.warpRegister
    private val savedAwgRegister = VpnNativeProtectRegistration.awgRegister
    private val savedProxyUnregister = VpnNativeProtectRegistration.proxyUnregister
    private val savedRelayUnregister = VpnNativeProtectRegistration.relayUnregister
    private val savedWarpUnregister = VpnNativeProtectRegistration.warpUnregister
    private val savedAwgUnregister = VpnNativeProtectRegistration.awgUnregister

    @Before
    fun setUp() {
        installSuccessfulRegisterHooks()
        installSuccessfulUnregisterHooks()

        // Drain any leftover state from a previous test (object is a singleton).
        VpnNativeProtectRegistration.unregister()
        clearUnregisterCalls()
    }

    private fun installSuccessfulUnregisterHooks() {
        VpnNativeProtectRegistration.proxyUnregister = { token -> proxyUnregisterCalls += token }
        VpnNativeProtectRegistration.relayUnregister = { token -> relayUnregisterCalls += token }
        VpnNativeProtectRegistration.warpUnregister = { token -> warpUnregisterCalls += token }
        VpnNativeProtectRegistration.awgUnregister = { token -> awgUnregisterCalls += token }
    }

    private fun installSuccessfulRegisterHooks() {
        VpnNativeProtectRegistration.proxyRegister = {
            nextProxyToken.getAndIncrement().also { proxyRegisterCalls += it }
        }
        VpnNativeProtectRegistration.relayRegister = {
            nextRelayToken.getAndIncrement().also { relayRegisterCalls += it }
        }
        VpnNativeProtectRegistration.warpRegister = {
            nextWarpToken.getAndIncrement().also { warpRegisterCalls += it }
        }
        VpnNativeProtectRegistration.awgRegister = {
            nextAwgToken.getAndIncrement().also { awgRegisterCalls += it }
        }
    }

    private fun clearUnregisterCalls() {
        proxyUnregisterCalls.clear()
        relayUnregisterCalls.clear()
        warpUnregisterCalls.clear()
        awgUnregisterCalls.clear()
    }

    @After
    fun tearDown() {
        VpnNativeProtectRegistration.unregister()
        VpnNativeProtectRegistration.proxyRegister = savedProxyRegister
        VpnNativeProtectRegistration.relayRegister = savedRelayRegister
        VpnNativeProtectRegistration.warpRegister = savedWarpRegister
        VpnNativeProtectRegistration.awgRegister = savedAwgRegister
        VpnNativeProtectRegistration.proxyUnregister = savedProxyUnregister
        VpnNativeProtectRegistration.relayUnregister = savedRelayUnregister
        VpnNativeProtectRegistration.warpUnregister = savedWarpUnregister
        VpnNativeProtectRegistration.awgUnregister = savedAwgUnregister
    }

    /**
     * (a) register twice then unregister once:
     * - The double-registration guard must call unregister on the first pair
     *   during the second register call.
     * - After the final unregister, tokens must be 0 (evidenced by a subsequent
     *   unregister call receiving 0 for both sides).
     */
    @Test
    fun `register twice then unregister once clears tokens and unregisters first pair during second register`() {
        VpnNativeProtectRegistration.register(fakeService)
        val firstProxy = proxyRegisterCalls[0]
        val firstRelay = relayRegisterCalls[0]
        val firstWarp = warpRegisterCalls[0]
        val firstAwg = awgRegisterCalls[0]

        VpnNativeProtectRegistration.register(fakeService)
        assertEquals(
            "proxy unregister must be called with first proxy token during double-register guard",
            listOf(firstProxy),
            proxyUnregisterCalls,
        )
        assertEquals(
            "relay unregister must be called with first relay token during double-register guard",
            listOf(firstRelay),
            relayUnregisterCalls,
        )
        assertEquals(
            "warp unregister must be called with first warp token during double-register guard",
            listOf(firstWarp),
            warpUnregisterCalls,
        )
        assertEquals(
            "awg unregister must be called with first awg token during double-register guard",
            listOf(firstAwg),
            awgUnregisterCalls,
        )

        // Clear guard-unregister calls so we can inspect the final unregister independently.
        proxyUnregisterCalls.clear()
        relayUnregisterCalls.clear()
        warpUnregisterCalls.clear()
        awgUnregisterCalls.clear()

        val secondProxy = proxyRegisterCalls[1]
        val secondRelay = relayRegisterCalls[1]
        val secondWarp = warpRegisterCalls[1]
        val secondAwg = awgRegisterCalls[1]

        VpnNativeProtectRegistration.unregister()
        assertEquals(listOf(secondProxy), proxyUnregisterCalls)
        assertEquals(listOf(secondRelay), relayUnregisterCalls)
        assertEquals(listOf(secondWarp), warpUnregisterCalls)
        assertEquals(listOf(secondAwg), awgUnregisterCalls)

        // After unregister tokens must be zero; a follow-up unregister is a no-op.
        proxyUnregisterCalls.clear()
        relayUnregisterCalls.clear()
        warpUnregisterCalls.clear()
        awgUnregisterCalls.clear()
        VpnNativeProtectRegistration.unregister()
        assertEquals(emptyList<Long>(), proxyUnregisterCalls)
        assertEquals(emptyList<Long>(), relayUnregisterCalls)
        assertEquals(emptyList<Long>(), warpUnregisterCalls)
        assertEquals(emptyList<Long>(), awgUnregisterCalls)
    }

    /**
     * (b) Concurrent register / unregister from two threads, repeated 100×.
     * After a final serialized unregister the tokens must be 0.
     */
    @Test
    fun `concurrent register and unregister never leaves nonzero token after final unregister`() {
        repeat(100) {
            val barrier = CyclicBarrier(2)
            val t1 =
                Thread {
                    barrier.await()
                    VpnNativeProtectRegistration.register(fakeService)
                }
            val t2 =
                Thread {
                    barrier.await()
                    VpnNativeProtectRegistration.unregister()
                }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        }

        // Drain whatever state remains, then confirm tokens are 0.
        VpnNativeProtectRegistration.unregister()
        proxyUnregisterCalls.clear()
        relayUnregisterCalls.clear()
        warpUnregisterCalls.clear()
        awgUnregisterCalls.clear()
        VpnNativeProtectRegistration.unregister()
        assertEquals(emptyList<Long>(), proxyUnregisterCalls)
        assertEquals(emptyList<Long>(), relayUnregisterCalls)
        assertEquals(emptyList<Long>(), warpUnregisterCalls)
        assertEquals(emptyList<Long>(), awgUnregisterCalls)
    }

    @Test
    fun `partial registration failure rolls back every acquired token`() {
        val registrationFailure = IllegalStateException("warp registration failed")
        VpnNativeProtectRegistration.warpRegister = { throw registrationFailure }

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                VpnNativeProtectRegistration.register(fakeService)
            }

        assertSame(registrationFailure, thrown)
        assertEquals(listOf(1L), proxyUnregisterCalls)
        assertEquals(listOf(50L), relayUnregisterCalls)
        assertEquals(emptyList<Long>(), warpUnregisterCalls)
        assertEquals(emptyList<Long>(), awgUnregisterCalls)
    }

    @Test
    fun `zero token from every native owner fails registration and rolls back prior owners`() {
        val zeroHooks =
            listOf<() -> Unit>(
                { VpnNativeProtectRegistration.proxyRegister = { 0L } },
                { VpnNativeProtectRegistration.relayRegister = { 0L } },
                { VpnNativeProtectRegistration.warpRegister = { 0L } },
                { VpnNativeProtectRegistration.awgRegister = { 0L } },
            )

        zeroHooks.forEachIndexed { ownerIndex, installZeroHook ->
            installSuccessfulRegisterHooks()
            clearUnregisterCalls()
            installZeroHook()

            assertThrows(IllegalStateException::class.java) {
                VpnNativeProtectRegistration.register(fakeService)
            }

            assertEquals(if (ownerIndex > 0) 1 else 0, proxyUnregisterCalls.size)
            assertEquals(if (ownerIndex > 1) 1 else 0, relayUnregisterCalls.size)
            assertEquals(if (ownerIndex > 2) 1 else 0, warpUnregisterCalls.size)
            assertEquals(0, awgUnregisterCalls.size)
        }
    }

    @Test
    fun `unregister attempts every slot and retries only failed owners`() {
        VpnNativeProtectRegistration.register(fakeService)
        val proxyFailure = IllegalStateException("proxy unregister failed")
        val warpFailure = IllegalArgumentException("warp unregister failed")
        VpnNativeProtectRegistration.proxyUnregister = { token ->
            proxyUnregisterCalls += token
            throw proxyFailure
        }
        VpnNativeProtectRegistration.warpUnregister = { token ->
            warpUnregisterCalls += token
            throw warpFailure
        }

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                VpnNativeProtectRegistration.unregister()
            }

        assertSame(proxyFailure, thrown)
        assertEquals(listOf(warpFailure), thrown.suppressed.toList())
        assertEquals(listOf(1L), proxyUnregisterCalls)
        assertEquals(listOf(50L), relayUnregisterCalls)
        assertEquals(listOf(100L), warpUnregisterCalls)
        assertEquals(listOf(200L), awgUnregisterCalls)

        VpnNativeProtectRegistration.proxyUnregister = { token -> proxyUnregisterCalls += token }
        VpnNativeProtectRegistration.warpUnregister = { token -> warpUnregisterCalls += token }
        VpnNativeProtectRegistration.unregister()

        assertEquals(listOf(1L, 1L), proxyUnregisterCalls)
        assertEquals(listOf(50L), relayUnregisterCalls)
        assertEquals(listOf(100L, 100L), warpUnregisterCalls)
        assertEquals(listOf(200L), awgUnregisterCalls)
    }

    @Test
    fun `failure at every unregister position keeps only that owner for retry`() {
        val failureHooks =
            listOf<() -> Unit>(
                {
                    VpnNativeProtectRegistration.proxyUnregister = { token ->
                        proxyUnregisterCalls += token
                        error("proxy failed")
                    }
                },
                {
                    VpnNativeProtectRegistration.relayUnregister = { token ->
                        relayUnregisterCalls += token
                        error("relay failed")
                    }
                },
                {
                    VpnNativeProtectRegistration.warpUnregister = { token ->
                        warpUnregisterCalls += token
                        error("warp failed")
                    }
                },
                {
                    VpnNativeProtectRegistration.awgUnregister = { token ->
                        awgUnregisterCalls += token
                        error("awg failed")
                    }
                },
            )

        failureHooks.forEachIndexed { failedOwner, installFailure ->
            installSuccessfulRegisterHooks()
            installSuccessfulUnregisterHooks()
            clearUnregisterCalls()
            VpnNativeProtectRegistration.register(fakeService)
            installFailure()

            assertThrows(IllegalStateException::class.java) {
                VpnNativeProtectRegistration.unregister()
            }
            assertEquals(listOf(1, 1, 1, 1), unregisterCallCounts())

            installSuccessfulUnregisterHooks()
            VpnNativeProtectRegistration.unregister()
            assertEquals(
                List(4) { owner -> if (owner == failedOwner) 2 else 1 },
                unregisterCallCounts(),
            )
        }
    }

    private fun unregisterCallCounts(): List<Int> =
        listOf(
            proxyUnregisterCalls.size,
            relayUnregisterCalls.size,
            warpUnregisterCalls.size,
            awgUnregisterCalls.size,
        )
}
