package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Protect-fd contract coverage for the Xray provider.
 *
 * The [vpnservice-protect-invariant](../../../../../../../../.claude/rules/vpnservice-protect-invariant.md)
 * rule is the single highest-leverage Android networking invariant: every
 * non-loopback outbound socket Xray opens (the relay outbound to the server)
 * MUST pass through [android.net.VpnService.protect] BEFORE `connect`/`bind`,
 * or the socket is captured by the VPN's own TUN route and the packet loop
 * fires.
 *
 * [RipDpiXrayRuntime] guarantees the *registration ordering* (protect
 * registered before [XrayNativeBridge.start]); the existing
 * [RipDpiXrayRuntimeTest] proves that. This suite proves the *runtime* half of
 * the contract that the registration ordering exists to serve:
 *
 * 1. Every outbound socket Xray opens is offered to the registered protect
 *    controller BEFORE the simulated `connect` returns.
 * 2. A protect *denial* (`false`) fails that socket — Xray must not proceed
 *    with an unprotected socket.
 * 3. Loopback inbound sockets are never offered to protect (they must NOT be
 *    protected; protecting the local inbound would be meaningless and the rule
 *    explicitly scopes protect to non-loopback targets).
 *
 * The contract is exercised against [ProtectingXrayBridge], a fake that models
 * libXray's observable protect behaviour: it replays the registered controller
 * for each socket open, recording the call order, so the test can assert
 * protect-before-connect without gomobile. The real gomobile bridge is
 * `// UNVERIFIED IN CI`; this fake stands in for its contract.
 */
class XrayProtectFdContractTest {
    @Test
    fun `cancelled start revokes protection before delayed native cleanup`() =
        runTest {
            withContext(Dispatchers.IO) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val bridge =
                    object : FakeXrayNativeBridge() {
                        override fun start(jsonConfig: String): Int {
                            entered.countDown()
                            release.await()
                            return super.start(jsonConfig)
                        }
                    }
                val owner = XrayRuntimeOwner(bridge)
                val runtime = RipDpiXrayRuntime(owner)
                val startCall = async { runtime.start("{}", XrayProtectController { true }) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    startCall.cancelAndJoin()
                    assertFalse(checkNotNull(bridge.registeredProtectController).protect(42))
                } finally {
                    release.countDown()
                    withContext(NonCancellable) {
                        startCall.join()
                        runtime.stop()
                    }
                }
            }
        }

    /**
     * A fake bridge that, on [start], simulates Xray opening its configured
     * outbound (and a loopback inbound) sockets and exercising the registered
     * protect controller exactly as libXray would.
     *
     * It records an ordered event log of `protect(fd)` / `connect(fd)` /
     * `bind(loopback)` events so a test can assert protect strictly precedes
     * connect for each non-loopback socket, and that a denied protect aborts
     * the connect.
     */
    private class ProtectingXrayBridge(
        /** fds for the non-loopback outbound sockets Xray opens on start. */
        private val outboundFds: List<Int> = listOf(100, 101),
        /** fd for the loopback inbound listener (must never be protected). */
        private val loopbackInboundFd: Int = 7,
    ) : FakeXrayNativeBridge() {
        /** Ordered log: "protect(fd)", "connect(fd)", "deny(fd)", "bind(fd)". */
        val socketEvents: MutableList<String> = mutableListOf()

        /** Outbound fds that completed a (protected) connect. */
        val connectedFds: MutableList<Int> = mutableListOf()

        /** Outbound fds whose connect was aborted by a protect denial. */
        val abortedFds: MutableList<Int> = mutableListOf()

        override fun start(jsonConfig: String): Int {
            val code = super.start(jsonConfig)
            if (code != 0) return code
            val controller =
                registeredProtectController
                    ?: error("PROTECT-FIRST VIOLATION: start() ran before registerProtect()")

            // Loopback inbound listener: bind only, never protected.
            socketEvents += "bind($loopbackInboundFd)"

            // Each non-loopback outbound: protect MUST be attempted before connect.
            for (fd in outboundFds) {
                socketEvents += "protect($fd)"
                val ok = controller.protect(fd)
                if (ok) {
                    socketEvents += "connect($fd)"
                    connectedFds += fd
                } else {
                    // Denied: the socket is closed and the connect fails. Xray
                    // MUST NOT proceed with an unprotected outbound.
                    socketEvents += "deny($fd)"
                    abortedFds += fd
                }
            }
            return code
        }
    }

    @Test
    fun `every outbound socket is protected before connect`() =
        runTest {
            val protectedFds = mutableListOf<Int>()
            val protect =
                XrayProtectController { fd ->
                    protectedFds += fd
                    true
                }
            val bridge = ProtectingXrayBridge(outboundFds = listOf(200, 201, 202))
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            runtime.start(CONFIG, protect)

            // Protect was offered every outbound fd.
            assertEquals(listOf(200, 201, 202), protectedFds)
            // For each fd, protect(fd) strictly precedes connect(fd).
            for (fd in listOf(200, 201, 202)) {
                val protectIdx = bridge.socketEvents.indexOf("protect($fd)")
                val connectIdx = bridge.socketEvents.indexOf("connect($fd)")
                assertTrue("protect($fd) logged", protectIdx >= 0)
                assertTrue("connect($fd) logged", connectIdx >= 0)
                assertTrue("protect($fd) before connect($fd)", protectIdx < connectIdx)
            }
            assertEquals(listOf(200, 201, 202), bridge.connectedFds)
            assertTrue("no socket aborted on the happy path", bridge.abortedFds.isEmpty())
        }

    @Test
    fun `registerProtect happens before the bridge opens any socket`() =
        runTest {
            // The fake's start() asserts registeredProtectController != null; a
            // protect-first violation would throw inside start. This test pins
            // that the runtime upholds it (no exception, start accepted).
            val bridge = ProtectingXrayBridge()
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            val code = runtime.start(CONFIG) { true }

            assertEquals(0, code)
            assertEquals(VpnProviderState.Starting, runtime.providerState)
            val registerIdx = bridge.callLog.indexOf("registerProtect")
            val startIdx = bridge.callLog.indexOf("start")
            assertTrue("registerProtect before start", registerIdx in 0 until startIdx)
        }

    @Test
    fun `a denied protect aborts the socket and never connects unprotected`() =
        runTest {
            // Deny exactly fd 101; 100 is allowed.
            val protect = XrayProtectController { fd -> fd != 101 }
            val bridge = ProtectingXrayBridge(outboundFds = listOf(100, 101))
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            runtime.start(CONFIG, protect)

            // 100 connected (protected ok); 101 was aborted, never connected.
            assertEquals(listOf(100), bridge.connectedFds)
            assertEquals(listOf(101), bridge.abortedFds)
            // The denied fd has a protect + deny event but NO connect event.
            assertTrue("protect(101) attempted", bridge.socketEvents.contains("protect(101)"))
            assertTrue("connect(101) never happened", !bridge.socketEvents.contains("connect(101)"))
            assertTrue("deny(101) recorded", bridge.socketEvents.contains("deny(101)"))
        }

    @Test
    fun `loopback inbound listener is never offered to protect`() =
        runTest {
            val protectedFds = mutableListOf<Int>()
            val protect =
                XrayProtectController { fd ->
                    protectedFds += fd
                    true
                }
            val bridge = ProtectingXrayBridge(outboundFds = listOf(300), loopbackInboundFd = 9)
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            runtime.start(CONFIG, protect)

            // The loopback inbound fd (9) must never be passed to protect; only
            // the non-loopback outbound (300) is.
            assertFalse("loopback fd must not be protected", protectedFds.contains(9))
            assertEquals(listOf(300), protectedFds)
            assertTrue("loopback bind recorded", bridge.socketEvents.contains("bind(9)"))
        }

    private companion object {
        const val CONFIG =
            """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"users":[""" +
                """{"id":"d0c0ffee-dead-beef-cafe-000000000001","flow":"xtls-rprx-vision"}]}]}}]}"""
    }
}
