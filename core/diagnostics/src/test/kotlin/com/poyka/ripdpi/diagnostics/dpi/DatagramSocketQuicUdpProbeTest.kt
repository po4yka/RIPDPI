package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
                                loopback,
                                server.localPort,
                                QuicFingerprint.GENERIC_V1,
                                byteArrayOf(1),
                                TimeSource.Monotonic.markNow() + 3_000.milliseconds,
                            ) { classify(it, reply) }
                        responder.await()
                        assertEquals(
                            "single UDP response of $size bytes",
                            QuicProbeOutcome.INITIAL_CRYPTO,
                            sample.outcome,
                        )
                    } finally {
                        server.close()
                        responder.cancelAndJoin()
                    }
                }
            }
        }

    @Test(timeout = 10_000)
    fun ignoresTheWrongPeerAndUnrelatedDatagramsBeforeTheMatchingReply() =
        runBlocking<Unit> {
            DatagramSocket(0, loopback).use { server ->
                DatagramSocket(0, loopback).use { wrongPeer ->
                    server.soTimeout = 3_000
                    val expected = ByteArray(3_000) { (it * 13).toByte() }
                    val responder =
                        async(Dispatchers.IO) {
                            val request = DatagramPacket(ByteArray(16), 16)
                            server.receive(request)
                            wrongPeer.send(DatagramPacket(expected, expected.size, request.socketAddress))
                            val unrelated = byteArrayOf(0)
                            server.send(DatagramPacket(unrelated, unrelated.size, request.socketAddress))
                            server.send(DatagramPacket(expected, expected.size, request.socketAddress))
                        }
                    try {
                        val sample =
                            DatagramSocketQuicUdpProbe().sendInitial(
                                loopback,
                                server.localPort,
                                QuicFingerprint.GENERIC_V1,
                                byteArrayOf(1),
                                TimeSource.Monotonic.markNow() + 3_000.milliseconds,
                            ) { classify(it, expected) }
                        assertEquals(QuicProbeOutcome.INITIAL_CRYPTO, sample.outcome)
                        assertEquals(1, sample.invalidDatagrams)
                        responder.await()
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
                                address = loopback,
                                port = server.localPort,
                                fingerprint = QuicFingerprint.GENERIC_V1,
                                packet = byteArrayOf(1),
                                deadline = TimeSource.Monotonic.markNow() + 3_000.milliseconds,
                            ) { QuicProbeOutcome.INITIAL_CRYPTO }
                        }
                    val received = DatagramPacket(ByteArray(16), 16)
                    server.receive(received)

                    val elapsed = measureTimeMillis { pending.cancelAndJoin() }

                    assertTrue("cancel took $elapsed ms", elapsed < 500)
                    assertTrue(pending.isCancelled)
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
                        probe.resolve("blocked.invalid", 3_000)
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
                        probe.resolve("blocked.invalid", 3_000)
                    }
                }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                repeat(10) {
                    val result =
                        withTimeout(500) {
                            probe.resolve("blocked.invalid", 20)
                        }
                    assertNull(result)
                    val cancelled =
                        launch(Dispatchers.Default) {
                            probe.resolve("blocked.invalid", 3_000)
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
                val result =
                    probe.resolve("blocked.invalid", 100)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

                assertNull(result)
                assertTrue("timeout took $elapsed ms", elapsed < 500)
                assertFalse(lookupFinished.get())
            } finally {
                release.countDown()
            }
        }

    @Test(timeout = 10_000)
    fun invalidRepliesDoNotExtendReceiveDeadline() =
        runBlocking {
            DatagramSocket(0, loopback).use { server ->
                server.soTimeout = 1_000
                val responder =
                    launch(Dispatchers.IO) {
                        val received = DatagramPacket(ByteArray(16), 16)
                        server.receive(received)
                        val invalid = byteArrayOf(3)
                        repeat(8) {
                            server.send(DatagramPacket(invalid, invalid.size, received.socketAddress))
                            delay(75)
                        }
                    }
                try {
                    val started = System.nanoTime()
                    val result =
                        DatagramSocketQuicUdpProbe().sendInitial(
                            loopback,
                            server.localPort,
                            QuicFingerprint.GENERIC_V1,
                            byteArrayOf(1),
                            TimeSource.Monotonic.markNow() + 350.milliseconds,
                        ) { QuicProbeOutcome.UNCORRELATED_RESPONSE }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

                    assertEquals(QuicProbeOutcome.UNCORRELATED_RESPONSE, result.outcome)
                    assertTrue("invalid packets before deadline must remain evidence", result.invalidDatagrams in 1..7)
                    assertTrue("invalid packets extended receive deadline to $elapsed ms", elapsed < 550)
                } finally {
                    responder.cancelAndJoin()
                }
            }
        }

    @Test(timeout = 10_000)
    fun unvalidatedDatagramBeyondByteLimitIsNotCountedAsRejected() =
        runBlocking {
            DatagramSocket(0, loopback).use { server ->
                server.soTimeout = 3_000
                val responder =
                    launch(Dispatchers.IO) {
                        val received = DatagramPacket(ByteArray(16), 16)
                        server.receive(received)
                        val reply = ByteArray(60_000)
                        repeat(5) {
                            server.send(DatagramPacket(reply, reply.size, received.socketAddress))
                            delay(20)
                        }
                    }
                try {
                    val result =
                        DatagramSocketQuicUdpProbe().sendInitial(
                            loopback,
                            server.localPort,
                            QuicFingerprint.GENERIC_V1,
                            byteArrayOf(1),
                            TimeSource.Monotonic.markNow() + 3_000.milliseconds,
                        ) { QuicProbeOutcome.UNCORRELATED_RESPONSE }

                    assertEquals(QuicProbeOutcome.RECEIVE_LIMIT_REACHED, result.outcome)
                    assertEquals(4, result.invalidDatagrams)
                } finally {
                    responder.cancelAndJoin()
                }
            }
        }

    private fun classify(
        received: ByteArray,
        expected: ByteArray,
    ): QuicProbeOutcome =
        if (received.contentEquals(
                expected,
            )
        ) {
            QuicProbeOutcome.INITIAL_CRYPTO
        } else {
            QuicProbeOutcome.UNCORRELATED_RESPONSE
        }

    private companion object {
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
