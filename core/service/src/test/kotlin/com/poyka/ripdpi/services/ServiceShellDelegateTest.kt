package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellDelegateTest {
    @Test
    fun proxyShellDelegatesStartAndStopActions() =
        runTest {
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val startResult = delegate.onStartCommand(startAction, 1)
            runCurrent()
            val stopResult = delegate.onStartCommand(stopAction, 7)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, startResult)
            assertEquals(android.app.Service.START_NOT_STICKY, stopResult)
            assertEquals(1, startCalls)
            assertEquals(listOf(7), stopIds)
        }

    @Test
    fun `stop action is rejected when service policy forbids disconnect`() =
        runTest {
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    isStopAllowed = { false },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(stopAction, 7)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `stop action checks current policy for stale notification intent`() =
        runTest {
            var stopAllowed = true
            var startCalls = 0
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = { stopIds += it },
                    isStopAllowed = { stopAllowed },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            stopAllowed = false
            val result = delegate.onStartCommand(notificationStopAction, 9)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `proxy notification stop remains allowed by default`() =
        runTest {
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStop = { stopIds += it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(notificationStopAction, 11)
            runCurrent()

            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertEquals(listOf(11), stopIds)
        }

    @Test
    fun `null action triggers start for sticky service restart`() =
        runTest {
            var startCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { startCalls += 1 },
                    onStop = {},
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand(null, 1)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(1, startCalls)
        }

    @Test
    fun `unknown action is ignored without stopping service`() =
        runTest {
            val stopIds = mutableListOf<Int?>()
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { stopIds += it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand("unknown", 9)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, result)
            assertEquals(emptyList<Int?>(), stopIds)
        }

    @Test
    fun `onRevoke delegates to revoke handler`() =
        runTest {
            var revokeCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = {},
                    onRevoke = { revokeCalls += 1 },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onRevoke()
            runCurrent()

            assertEquals(1, revokeCalls)
        }
}
