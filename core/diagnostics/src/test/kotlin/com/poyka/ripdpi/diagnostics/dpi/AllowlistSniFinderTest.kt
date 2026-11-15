package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AllowlistSniFinderTest {
    @Test
    fun earlyStopsAfterThreeCompatibleSnisFound() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = setOf("sni-1.test", "sni-3.test", "sni-5.test"))
            val finder = AllowlistSniFinder(probe = probe, sniList = namedSnis(10))

            val result = finder.find(listOf(detectedResult())).getValue("AS64500")

            assertEquals(3, result.compatibleSnis.size)
            assertEquals(5, result.triedCount)
            assertTrue(probe.probedSnis.none { sni -> sni == "sni-6.test" })
        }

    @Test
    fun triesAllCandidatesWhenNoneAreCompatible() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = emptySet())
            val finder = AllowlistSniFinder(probe = probe, sniList = namedSnis(8))

            val result = finder.find(listOf(detectedResult())).getValue("AS64500")

            assertEquals(emptyList<CompatibleSni>(), result.compatibleSnis)
            assertEquals(8, result.triedCount)
        }

    @Test
    fun batchSizeFiveRunsConcurrently() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = emptySet(), delayMs = 10)
            val finder = AllowlistSniFinder(probe = probe, sniList = namedSnis(12))

            finder.find(listOf(detectedResult()))

            assertEquals(5, probe.maxConcurrentCalls.get())
        }

    @Test
    fun lineNumberRecordedCorrectly() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = setOf("example-b.test"))
            val finder =
                AllowlistSniFinder(
                    probe = probe,
                    sniList = listOf("example-a.test", "example-b.test", "example-c.test"),
                )

            val result = finder.find(listOf(detectedResult())).getValue("AS64500")

            assertEquals(listOf(CompatibleSni("example-b.test", 2)), result.compatibleSnis)
        }

    @Test
    fun baselineAndCandidatesUseTheSameResponseTimeHint() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = setOf("sni-1.test"))
            val finder = AllowlistSniFinder(probe = probe, sniList = namedSnis(4))

            finder.find(listOf(detectedResult()))

            assertEquals(1, probe.baselineCalls.get())
            assertEquals(listOf(30L, 30L, 30L, 30L, 30L), probe.hintsUsed)
        }

    @Test
    fun cancellationIsRethrown() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = emptySet(), cancelOnSni = "sni-2.test")
            val finder = AllowlistSniFinder(probe = probe, sniList = namedSnis(4))

            try {
                finder.find(listOf(detectedResult()))
                fail("Expected cancellation to be rethrown")
            } catch (cancellation: CancellationException) {
                assertEquals("cancelled", cancellation.message)
            }
        }

    @Test
    fun candidateChangesTlsNameWhileKeepingHttpAuthorityAndPeer() =
        runTest {
            val certificate = HeldCertificate.Builder().commonName("control.test").build()
            val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory())
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.handshakeServerNames == listOf("control.test") && request.exchangeIndex == 5) {
                                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
                            } else {
                                MockResponse.Builder().code(200).build()
                            }
                    }
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val probe =
                    Tcp16FatHeaderProbe(
                        requestRunner =
                            OkHttpTcp16RequestRunner.withClientBuilder { configure ->
                                OkHttpClient
                                    .Builder()
                                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                                    .apply(configure)
                                    .build()
                            },
                    )
                val target =
                    Tcp16Target(
                        id = "test",
                        asn = "AS64500",
                        provider = "test",
                        ip = "127.0.0.1",
                        port = server.port,
                        sni = "control.test",
                        transport = Tcp16Transport.HTTPS,
                    )

                val original = probe.runTarget(target)
                val result =
                    AllowlistSniFinder(
                        probe,
                        listOf("alternate.test"),
                    ).find(listOf(original)).getValue(target.asn)

                assertEquals(Tcp16Verdict.PADDED_TRAIN_INTERRUPTED, original.verdict)
                assertEquals(listOf(CompatibleSni("alternate.test", 1)), result.compatibleSnis)
                assertEquals(server.port, result.port)
                val requests = List(server.requestCount) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
                assertEquals(28, requests.size)
                assertEquals(3, requests.map { it.connectionIndex }.toSet().size)
                assertEquals(listOf("control.test:${server.port}"), requests.map { it.headers["Host"] }.distinct())
                assertEquals(
                    List(12) { listOf("control.test") } + List(16) { listOf("alternate.test") },
                    requests.map { it.handshakeServerNames },
                )
            }
        }

    @Test
    fun successfulRerunDoesNotValidateAnyCandidates() =
        runTest {
            val probe = RecordingTcp16Probe(setOf("sni-1.test"), baselineVerdict = Tcp16Verdict.OK)

            val result = AllowlistSniFinder(probe, namedSnis(4)).find(listOf(detectedResult())).getValue("AS64500")

            assertEquals(AllowlistSniFailure.BASELINE_NOT_REPRODUCED, result.failure)
            assertEquals(0, result.triedCount)
            assertTrue(probe.probedSnis.isEmpty())
        }

    @Test
    fun cleartextInterruptionDoesNotProbeAnyNames() =
        runTest {
            val probe = RecordingTcp16Probe(setOf("sni-1.test"))
            val original = detectedResult().copy(transport = Tcp16Transport.HTTP)

            val result = AllowlistSniFinder(probe, namedSnis(4)).find(listOf(original)).getValue("AS64500")

            assertEquals(AllowlistSniFailure.NO_TLS_BASELINE, result.failure)
            assertEquals(0, result.triedCount)
            assertEquals(0, probe.baselineCalls.get())
        }

    @Test
    fun timedOutSearchPreservesItsInconclusiveReason() =
        runTest {
            val probe = RecordingTcp16Probe(setOf("sni-1.test"), delayMs = 61_000)
            val finder = AllowlistSniFinder(probe, namedSnis(4), dispatcher = StandardTestDispatcher(testScheduler))

            val result = finder.find(listOf(detectedResult())).getValue("AS64500")

            assertEquals(AllowlistSniFailure.SEARCH_TIMED_OUT, result.failure)
            assertEquals(emptyList<CompatibleSni>(), result.compatibleSnis)
        }

    private fun detectedResult(): Tcp16ProbeResult =
        Tcp16ProbeResult(
            targetId = "target-1",
            asn = "AS64500",
            provider = "ExampleNet",
            ip = "203.0.113.10",
            port = 443,
            alive = true,
            verdict = Tcp16Verdict.PADDED_TRAIN_INTERRUPTED,
            completedPaddingBytes = 20 * 1_024,
            meanResponseMs = 30,
            errorDetail = null,
            connectionCount = 1,
            httpAuthority = "203.0.113.10",
        )

    private fun namedSnis(count: Int): List<String> = (1..count).map { index -> "sni-$index.test" }

    private class RecordingTcp16Probe(
        private val compatibleSnis: Set<String>,
        private val delayMs: Long = 0,
        private val cancelOnSni: String? = null,
        private val baselineVerdict: Tcp16Verdict = Tcp16Verdict.PADDED_TRAIN_INTERRUPTED,
    ) : Tcp16Probe {
        val baselineCalls = AtomicInteger()
        val maxConcurrentCalls = AtomicInteger()
        val probedSnis = CopyOnWriteArrayList<String>()
        val hintsUsed = CopyOnWriteArrayList<Long>()
        private val activeCalls = AtomicInteger()

        override suspend fun runTarget(
            target: Tcp16Target,
            responseTimeHintMs: Long?,
        ): Tcp16ProbeResult {
            responseTimeHintMs?.let { hint -> hintsUsed += hint }
            if (target.sni == null) {
                baselineCalls.incrementAndGet()
                return resultFor(target, baselineVerdict, meanResponseMs = 42)
            }
            probedSnis += target.sni
            if (target.sni == cancelOnSni) {
                throw CancellationException("cancelled")
            }
            val active = activeCalls.incrementAndGet()
            maxConcurrentCalls.updateAndGet { current -> maxOf(current, active) }
            try {
                if (delayMs > 0) delay(delayMs)
                return resultFor(
                    target = target,
                    verdict =
                        if (target.sni in
                            compatibleSnis
                        ) {
                            Tcp16Verdict.OK
                        } else {
                            Tcp16Verdict.PADDED_TRAIN_INTERRUPTED
                        },
                )
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        private fun resultFor(
            target: Tcp16Target,
            verdict: Tcp16Verdict,
            meanResponseMs: Long? = null,
        ): Tcp16ProbeResult =
            Tcp16ProbeResult(
                targetId = target.id,
                asn = target.asn,
                provider = target.provider,
                ip = target.ip,
                port = target.port,
                alive = true,
                verdict = verdict,
                completedPaddingBytes = if (verdict == Tcp16Verdict.PADDED_TRAIN_INTERRUPTED) 20 * 1_024 else 0,
                meanResponseMs = meanResponseMs,
                errorDetail = null,
                connectionCount = 1,
                httpAuthority = target.httpAuthority,
            )
    }
}
