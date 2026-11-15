package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TimingAnalysisCheckerTest {
    private val dispatchers = AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Unconfined)
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun `slow DNS is excluded and one resolved endpoint is used for every TCP sample`() =
        runBlocking {
            val peerExecutor = Executors.newSingleThreadExecutor()
            try {
                ServerSocket(0, 3, loopback).use { server ->
                    server.soTimeout = 3_000
                    val peer =
                        peerExecutor.submit<List<Int>> {
                            List(3) {
                                server.accept().use { socket -> socket.getInputStream().read() }
                            }
                        }
                    var resolved = false
                    val startedAt = System.nanoTime()
                    val sample =
                        TimingAnalysisChecker.collectTargetTiming(
                            dispatchers = dispatchers,
                            host = "slow-resolver.invalid",
                            port = server.localPort,
                            resolveAddresses = {
                                check(!resolved) { "The endpoint must stay pinned for the sample series" }
                                resolved = true
                                delay(1_000)
                                listOf(loopback)
                            },
                        )
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                    requireNotNull(sample)
                    assertEquals("127.0.0.1", sample.resolvedAddress)
                    assertEquals(3, sample.rttMs.size)
                    assertTrue("The lookup delay must have elapsed", elapsedMs >= 900)
                    assertTrue("DNS delay must not be a TCP sample", sample.rttMs.all { it < 500 })
                    assertEquals(listOf(-1, -1, -1), peer.get(3, TimeUnit.SECONDS))
                }
            } finally {
                peerExecutor.shutdownNow()
            }
        }

    @Test
    fun `missing or failed DNS does not create a TCP timing snapshot`() =
        runTest {
            assertNull(
                TimingAnalysisChecker.collectTargetTiming(dispatchers, "timeout.invalid", 443) { null },
            )
            assertNull(
                TimingAnalysisChecker.collectTargetTiming(dispatchers, "unknown.invalid", 443) {
                    throw UnknownHostException("No address")
                },
            )
        }

    @Test
    fun `cancellation during DNS leaves the timing check cancelled`() =
        runTest {
            try {
                TimingAnalysisChecker.collectTargetTiming(dispatchers, "cancel.invalid", 443) {
                    throw CancellationException("Probe cancelled")
                }
                fail("Cancellation must propagate")
            } catch (error: CancellationException) {
                assertEquals("Probe cancelled", error.message)
            }
        }

    @Test
    fun `similar elevated TCP timings remain a low confidence review signal`() {
        val result =
            TimingAnalysisChecker.analyze(
                listOf(
                    TimingAnalysisChecker.TimingSnapshot("first", listOf(60, 60, 60)),
                    TimingAnalysisChecker.TimingSnapshot("second", listOf(60, 60, 60)),
                ),
            )

        assertFalse(result.detected)
        assertTrue(result.needsReview)
        assertTrue(result.evidence.isNotEmpty())
        assertTrue(result.evidence.all { it.confidence == EvidenceConfidence.LOW })
        assertTrue(result.findings.none { it.detected })
        assertTrue(result.findings.any { it.description.contains("TCP connect latency") })
        assertTrue(result.findings.none { it.description.contains("tunnel", ignoreCase = true) })
    }
}
