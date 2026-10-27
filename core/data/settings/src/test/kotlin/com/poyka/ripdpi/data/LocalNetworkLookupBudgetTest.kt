package com.poyka.ripdpi.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch

class LocalNetworkLookupBudgetTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun `answer within the budget is returned`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            assertArrayEquals(arrayOf(loopback), resolveWithinBudget(scope, 5_000L) { arrayOf(loopback) })
            scope.cancel()
        }

    @Test
    fun `hung resolver gives no addresses after the budget instead of blocking the caller`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val resolverStuck = CountDownLatch(1)

            val addresses =
                withTimeout(5_000L) {
                    resolveWithinBudget(scope, 100L) {
                        resolverStuck.await()
                        arrayOf(loopback)
                    }
                }

            assertEquals(0, addresses.size)
            resolverStuck.countDown()
            scope.cancel()
        }

    @Test
    fun `cancelled caller returns without waiting for the resolver`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val resolverStuck = CountDownLatch(1)
            val lookupStarted = CountDownLatch(1)
            val caller =
                launch(Dispatchers.Default) {
                    resolveWithinBudget(scope, 60_000L) {
                        lookupStarted.countDown()
                        resolverStuck.await()
                        arrayOf(loopback)
                    }
                }
            lookupStarted.await()

            withTimeout(5_000L) { caller.cancelAndJoin() }
            resolverStuck.countDown()
            scope.cancel()
        }
}
