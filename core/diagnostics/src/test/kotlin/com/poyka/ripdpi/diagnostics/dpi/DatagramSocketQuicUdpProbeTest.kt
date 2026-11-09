package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis

class DatagramSocketQuicUdpProbeTest {
    @Test(timeout = 10_000)
    fun receivesTheWholeSingleDatagram() =
        runBlocking<Unit> {
            for (size in listOf(3_000, 8_192)) {
                DatagramSocket(0, loopback).use { server ->
                    server.soTimeout = 3_000
                    val reply = ByteArray(size) { (it * 17).toByte() }
                    val responder =
                        async(Dispatchers.IO) {
                            val request = DatagramPacket(ByteArray(16), 16)
                            server.receive(request)
                            server.send(DatagramPacket(reply, reply.size, request.socketAddress))
                        }
                    try {
                        val sample =
                            DatagramSocketQuicUdpProbe().sendInitial(
                                "127.0.0.1",
                                server.localPort,
                                QuicFingerprint.GENERIC_V1,
                                byteArrayOf(1),
                                3_000,
                            )
                        responder.await()
                        assertArrayEquals("single UDP response of $size bytes", reply, sample.response)
                    } finally {
                        server.close()
                        responder.cancelAndJoin()
                    }
                }
            }
        }

    @Test(timeout = 10_000)
    fun cancellationClosesTheSocketBeforeItsReceiveTimeout() =
        runBlocking {
            repeat(3) {
                DatagramSocket(0, loopback).use { server ->
                    server.soTimeout = 3_000
                    val pending =
                        launch(Dispatchers.Default) {
                            DatagramSocketQuicUdpProbe().sendInitial(
                                target = "127.0.0.1",
                                port = server.localPort,
                                fingerprint = QuicFingerprint.GENERIC_V1,
                                packet = byteArrayOf(1),
                                timeoutMs = 3_000,
                            )
                        }
                    val received = DatagramPacket(ByteArray(16), 16)
                    server.receive(received)

                    val elapsed = measureTimeMillis { pending.cancelAndJoin() }

                    assertTrue("cancel took $elapsed ms", elapsed < 500)
                    assertTrue(pending.isCancelled)
                    // Reusing the exact client port proves the cancelled probe released its socket.
                    DatagramSocket(received.port, received.address).close()
                }
            }
        }

    @Test(timeout = 10_000)
    fun cancellationDoesNotWaitForExecutingOrQueuedSystemLookups() =
        runBlocking {
            val entered = CountDownLatch(2)
            val lookupCount = AtomicInteger()
            val release = CountDownLatch(1)
            val lookupFinished = AtomicBoolean()
            val probe =
                DatagramSocketQuicUdpProbe {
                    lookupCount.incrementAndGet()
                    entered.countDown()
                    release.await()
                    lookupFinished.set(true)
                    loopback
                }
            val pending =
                List(8) {
                    launch(Dispatchers.Default) {
                        probe.sendInitial("blocked.invalid", 443, QuicFingerprint.GENERIC_V1, byteArrayOf(1), 3_000)
                    }
                }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))

                val elapsed =
                    measureTimeMillis {
                        pending.forEach { it.cancel() }
                        pending.forEach { it.join() }
                    }

                assertTrue("cancel took $elapsed ms", elapsed < 500)
                assertTrue("system lookups exceeded the worker bound", lookupCount.get() == 2)
                assertFalse(lookupFinished.get())
            } finally {
                release.countDown()
                pending.forEach { it.cancelAndJoin() }
            }
        }

    @Test(timeout = 10_000)
    fun cancelledAdmissionDoesNotQueueMoreDetachedLookups() =
        runBlocking {
            val submitted = AtomicInteger()
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val workers = Dispatchers.IO.limitedParallelism(2)
            val dispatcher =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        submitted.incrementAndGet()
                        workers.dispatch(context, block)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val probe =
                DatagramSocketQuicUdpProbe(lookupScope = scope) {
                    entered.countDown()
                    release.await()
                    loopback
                }
            val blocked =
                List(2) {
                    launch(Dispatchers.Default) {
                        probe.sendInitial("blocked.invalid", 443, QuicFingerprint.GENERIC_V1, byteArrayOf(1), 3_000)
                    }
                }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                repeat(10) {
                    val result =
                        withTimeout(500) {
                            probe.sendInitial("queued.invalid", 443, QuicFingerprint.GENERIC_V1, byteArrayOf(1), 20)
                        }
                    assertNull(result.response)
                    val cancelled =
                        launch(Dispatchers.Default) {
                            probe.sendInitial(
                                "cancelled.invalid",
                                443,
                                QuicFingerprint.GENERIC_V1,
                                byteArrayOf(1),
                                3_000,
                            )
                        }
                    cancelled.cancelAndJoin()
                }

                assertTrue("queued detached lookups: ${submitted.get()}", submitted.get() == 2)
            } finally {
                blocked.forEach { it.cancelAndJoin() }
                release.countDown()
                withTimeout(3_000) {
                    scope.coroutineContext[Job]!!
                        .children
                        .toList()
                        .forEach { it.join() }
                }
                scope.cancel()
            }
        }

    @Test(timeout = 10_000)
    fun resolutionDeadlineReturnsWhileTheSystemLookupIsStillBlocked() =
        runBlocking {
            val release = CountDownLatch(1)
            val lookupFinished = AtomicBoolean()
            val probe =
                DatagramSocketQuicUdpProbe {
                    release.await()
                    lookupFinished.set(true)
                    loopback
                }
            try {
                val started = System.nanoTime()
                val result = probe.sendInitial("blocked.invalid", 443, QuicFingerprint.GENERIC_V1, byteArrayOf(1), 100)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

                assertNull(result.response)
                assertTrue("timeout took $elapsed ms", elapsed < 500)
                assertFalse(lookupFinished.get())
            } finally {
                release.countDown()
            }
        }

    @Test(timeout = 10_000)
    fun resolutionAndReceiveShareOneDeadline() =
        runBlocking {
            DatagramSocket(0, loopback).use { server ->
                val delay = CountDownLatch(1)
                val probe =
                    DatagramSocketQuicUdpProbe {
                        delay.await(200, TimeUnit.MILLISECONDS)
                        loopback
                    }
                val started = System.nanoTime()
                val result =
                    probe.sendInitial("slow.invalid", server.localPort, QuicFingerprint.GENERIC_V1, byteArrayOf(1), 400)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

                assertNull(result.response)
                assertTrue("shared deadline took $elapsed ms", elapsed < 550)
                assertTrue("deadline fired before the receive phase", elapsed >= 350)
            }
        }

    private companion object {
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
