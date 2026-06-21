package com.poyka.ripdpi.services

import android.net.VpnService
import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
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
        VpnNativeProtectRegistration.proxyUnregister = { token -> proxyUnregisterCalls += token }
        VpnNativeProtectRegistration.relayUnregister = { token -> relayUnregisterCalls += token }
        VpnNativeProtectRegistration.warpUnregister = { token -> warpUnregisterCalls += token }
        VpnNativeProtectRegistration.awgUnregister = { token -> awgUnregisterCalls += token }

        // Drain any leftover state from a previous test (object is a singleton).
        VpnNativeProtectRegistration.unregister()
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

        // After unregister tokens must be zero; a follow-up unregister passes 0.
        proxyUnregisterCalls.clear()
        relayUnregisterCalls.clear()
        warpUnregisterCalls.clear()
        awgUnregisterCalls.clear()
        VpnNativeProtectRegistration.unregister()
        assertEquals("proxy token must be 0 after unregister", listOf(0L), proxyUnregisterCalls)
        assertEquals("relay token must be 0 after unregister", listOf(0L), relayUnregisterCalls)
        assertEquals("warp token must be 0 after unregister", listOf(0L), warpUnregisterCalls)
        assertEquals("awg token must be 0 after unregister", listOf(0L), awgUnregisterCalls)
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
        assertEquals("proxy token must be 0 after final drain", listOf(0L), proxyUnregisterCalls)
        assertEquals("relay token must be 0 after final drain", listOf(0L), relayUnregisterCalls)
        assertEquals("warp token must be 0 after final drain", listOf(0L), warpUnregisterCalls)
        assertEquals("awg token must be 0 after final drain", listOf(0L), awgUnregisterCalls)
    }
}
