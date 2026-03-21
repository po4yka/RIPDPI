package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
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

            val startResult = delegate.onStartCommand(START_ACTION, 1)
            runCurrent()
            val stopResult = delegate.onStartCommand(STOP_ACTION, 7)
            runCurrent()

            assertEquals(android.app.Service.START_STICKY, startResult)
            assertEquals(android.app.Service.START_NOT_STICKY, stopResult)
            assertEquals(1, startCalls)
            assertEquals(listOf(7), stopIds)
        }

    @Test
    fun vpnShellDelegatesUnknownActionsAndRevokeToStopHandlers() =
        runTest {
            val stopIds = mutableListOf<Int?>()
            var revokeCalls = 0
            val delegate =
                ServiceShellDelegate(
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { stopIds += it },
                    onRevoke = { revokeCalls += 1 },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            val result = delegate.onStartCommand("unknown", 9)
            runCurrent()
            delegate.onRevoke()
            runCurrent()

            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertEquals(listOf(9), stopIds)
            assertEquals(1, revokeCalls)
        }
}
