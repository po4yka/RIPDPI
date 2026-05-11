package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
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
    fun baselineRttMeasuredOncePerAsn() =
        runTest {
            val probe = RecordingTcp16Probe(compatibleSnis = setOf("sni-1.test"))
            val finder = AllowlistSniFinder(probe = probe, sniList = namedSnis(4))

            finder.find(listOf(detectedResult()))

            assertEquals(1, probe.baselineCalls.get())
            assertEquals(listOf(42L, 42L, 42L, 42L), probe.hintsUsed)
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

    private fun detectedResult(): Tcp16ProbeResult =
        Tcp16ProbeResult(
            targetId = "target-1",
            asn = "AS64500",
            provider = "ExampleNet",
            ip = "203.0.113.10",
            port = 443,
            alive = true,
            verdict = Tcp16Verdict.DETECTED_AT_KB,
            detectedAtKb = 20,
            measuredRttMs = 30,
            errorDetail = null,
            connectionCount = 1,
        )

    private fun namedSnis(count: Int): List<String> = (1..count).map { index -> "sni-$index.test" }

    private class RecordingTcp16Probe(
        private val compatibleSnis: Set<String>,
        private val delayMs: Long = 0,
        private val cancelOnSni: String? = null,
    ) : Tcp16Probe {
        val baselineCalls = AtomicInteger()
        val maxConcurrentCalls = AtomicInteger()
        val probedSnis = CopyOnWriteArrayList<String>()
        val hintsUsed = CopyOnWriteArrayList<Long>()
        private val activeCalls = AtomicInteger()

        override suspend fun runWithRttHint(
            target: Tcp16Target,
            hintRttMs: Long?,
        ): Tcp16ProbeResult {
            if (target.sni == null) {
                baselineCalls.incrementAndGet()
                return resultFor(target, Tcp16Verdict.DETECTED_AT_KB, measuredRttMs = 42)
            }
            hintRttMs?.let { hint -> hintsUsed += hint }
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
                    verdict = if (target.sni in compatibleSnis) Tcp16Verdict.OK else Tcp16Verdict.DETECTED_AT_KB,
                )
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        private fun resultFor(
            target: Tcp16Target,
            verdict: Tcp16Verdict,
            measuredRttMs: Long? = null,
        ): Tcp16ProbeResult =
            Tcp16ProbeResult(
                targetId = target.id,
                asn = target.asn,
                provider = target.provider,
                ip = target.ip,
                port = target.port,
                alive = true,
                verdict = verdict,
                detectedAtKb = if (verdict == Tcp16Verdict.DETECTED_AT_KB) 20 else null,
                measuredRttMs = measuredRttMs,
                errorDetail = null,
                connectionCount = 1,
            )
    }
}
