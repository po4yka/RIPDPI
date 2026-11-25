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

    private class ProtectingXrayBridge(
        private val outboundFds: List<Int> = listOf(100, 101),
        private val loopbackInboundFd: Int = 7,
    ) : FakeXrayNativeBridge() {
        val socketEvents: MutableList<String> = mutableListOf()

        val connectedFds: MutableList<Int> = mutableListOf()

        val abortedFds: MutableList<Int> = mutableListOf()

        override fun start(jsonConfig: String): Int {
            val code = super.start(jsonConfig)
            if (code != 0) return code
            val controller =
                registeredProtectController
                    ?: error("PROTECT-FIRST VIOLATION: start() ran before registerProtect()")

            socketEvents += "bind($loopbackInboundFd)"

            for (fd in outboundFds) {
                socketEvents += "protect($fd)"
                val ok = controller.protect(fd)
                if (ok) {
                    socketEvents += "connect($fd)"
                    connectedFds += fd
                } else {
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

            assertEquals(listOf(200, 201, 202), protectedFds)
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
            val protect = XrayProtectController { fd -> fd != 101 }
            val bridge = ProtectingXrayBridge(outboundFds = listOf(100, 101))
            val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, kotlinx.coroutines.Dispatchers.Unconfined))

            runtime.start(CONFIG, protect)

            assertEquals(listOf(100), bridge.connectedFds)
            assertEquals(listOf(101), bridge.abortedFds)
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
