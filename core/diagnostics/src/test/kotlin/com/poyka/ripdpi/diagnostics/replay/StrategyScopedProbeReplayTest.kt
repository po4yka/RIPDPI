package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.diagnostics.StrategyProbeActivationSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeActivator
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeRunGuard
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyScopedProbeReplayTest {
    private val state = StrategyState()
    private val activator = FakeActivator(state)
    private val nativeApplier = FakeNativeApplier(state)
    private val guard = StrategyProbeRunGuard()
    private val nativeConfig = RipDpiProxyUIPreferences().toNativeConfigJson()
    private var storedConfig: String? = nativeConfig
    private val request = ReplayProbeRequest(domain = "example.org", strategyId = "split", timeoutMs = 1_000L)
    private val finished =
        ReplayStepEvent.Finished(ReplayVerdict.Success, ReplayStepKind.FirstByte, recommendationKey = "")

    private fun replay(delegate: ProbeReplayService) =
        StrategyScopedProbeReplay(
            delegate = delegate,
            candidateProvider =
                object : StrategyProbeCandidateProvider {
                    override suspend fun listCandidates(): List<StrategyProbeCandidate> =
                        listOf(StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto"))
                },
            activator = activator,
            proxyConfigApplier = nativeApplier,
            configSource = { _, _ -> storedConfig },
            runGuard = guard,
        )

    private suspend fun runNative() {
        replay(ProbeReplayService { flow { emit(finished) } }).run(nativeRequest()).toList()
    }

    private fun nativeRequest() = request.copy(strategyId = "tlsrec_split_host", sessionId = "session-1")

    private suspend fun failureOf(block: suspend () -> Unit): IllegalStateException {
        try {
            block()
        } catch (expected: IllegalStateException) {
            return expected
        }
        fail("expected failure")
        error("unreachable")
    }

    @Test
    fun `replay runs under the requested strategy and restores the original after success`() =
        runTest {
            var activeDuringRun: String? = null
            val events =
                replay(
                    ProbeReplayService {
                        flow {
                            activeDuringRun = state.active
                            emit(finished)
                        }
                    },
                ).run(request)
                    .toList()

            assertEquals(listOf<ReplayStepEvent>(finished), events)
            assertEquals("split", activeDuringRun)
            assertEquals("previous", state.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `original strategy is restored when the replay fails`() =
        runTest {
            val error = failureOf { replay(ProbeReplayService { flow { error("boom") } }).run(request).toList() }

            assertEquals("boom", error.message)
            assertEquals("previous", state.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `original strategy is restored when the collector is cancelled mid-run`() =
        runTest {
            val started = CompletableDeferred<String?>()
            val suspended =
                ProbeReplayService {
                    flow {
                        started.complete(state.active)
                        awaitCancellation()
                    }
                }

            val job = launch { replay(suspended).run(request).toList() }
            assertEquals("split", started.await())
            job.cancel()
            job.join()

            assertEquals("previous", state.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `unknown strategy id fails without activating anything`() =
        runTest {
            var ran = false
            val error =
                failureOf {
                    replay(ProbeReplayService { flow { ran = true } })
                        .run(request.copy(strategyId = "tlsrec_split_host"))
                        .toList()
                }

            assertTrue(error.message.orEmpty().contains("tlsrec_split_host"))
            assertFalse(ran)
            assertEquals("previous", state.active)
            assertEquals(0, state.activations)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `strategy probe run holding the guard rejects a replay, and a replay holds the guard while it runs`() =
        runTest {
            assertTrue(guard.tryAcquire())
            val error = failureOf { replay(ProbeReplayService { flow { emit(finished) } }).run(request).toList() }
            assertTrue(error.message.orEmpty().contains("already active"))
            assertEquals(0, state.activations)
            assertTrue(guard.isHeld())
            guard.release()

            var probeCouldStart: Boolean? = null
            replay(
                ProbeReplayService {
                    flow {
                        probeCouldStart = guard.tryAcquire()
                        emit(finished)
                    }
                },
            ).run(request)
                .toList()
            assertEquals(false, probeCouldStart)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `guard is freed when restore throws`() =
        runTest {
            state.restoreFails = true

            failureOf { replay(ProbeReplayService { flow { emit(finished) } }).run(request).toList() }

            assertFalse(guard.isHeld())
        }

    @Test
    fun `native candidate config is applied during the run and restored after success`() =
        runTest {
            var activeDuringRun: String? = null
            replay(
                ProbeReplayService {
                    flow {
                        activeDuringRun = state.active
                        emit(finished)
                    }
                },
            ).run(nativeRequest())
                .toList()

            assertEquals("native", activeDuringRun)
            assertEquals("previous", state.active)
            assertEquals(0, state.activations)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `native candidate config is restored after failure and cancellation`() =
        runTest {
            failureOf { replay(ProbeReplayService { flow { error("boom") } }).run(nativeRequest()).toList() }
            assertEquals("previous", state.active)

            val started = CompletableDeferred<String?>()
            val job =
                launch {
                    replay(
                        ProbeReplayService {
                            flow {
                                started.complete(state.active)
                                awaitCancellation()
                            }
                        },
                    ).run(nativeRequest())
                        .toList()
                }
            assertEquals("native", started.await())
            job.cancel()
            job.join()

            assertEquals("previous", state.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `missing or undecodable native config fails without applying and without leaking the config`() =
        runTest {
            storedConfig = null
            val missing = failureOf { runNative() }
            assertTrue(missing.message.orEmpty().contains("not found"))

            storedConfig = "secret-token-not-json"
            val invalid = failureOf { runNative() }
            assertTrue(invalid.message.orEmpty().contains("invalid"))
            assertFalse(invalid.message.orEmpty().contains("secret-token"))

            assertEquals("previous", state.active)
            assertEquals(0, state.applications)
            assertFalse(guard.isHeld())
        }

    private fun StrategyProbeRunGuard.isHeld(): Boolean =
        if (tryAcquire()) {
            release()
            false
        } else {
            true
        }

    private class StrategyState {
        var active: String = "previous"
        var activations = 0
        var applications = 0
        var restoreFails = false
    }

    private class FakeActivator(
        private val state: StrategyState,
    ) : StrategyProbeActivator {
        override suspend fun capture(): StrategyProbeActivationSnapshot = StrategyProbeActivationSnapshot(state.active)

        override suspend fun activate(candidate: StrategyProbeCandidate) {
            state.activations += 1
            state.active = candidate.id
        }

        override suspend fun restore(snapshot: StrategyProbeActivationSnapshot) {
            state.active = snapshot.snapshotId
            check(!state.restoreFails) { "restore failed" }
        }
    }

    private class FakeNativeApplier(
        private val state: StrategyState,
    ) : ProxyConfigStrategyApplier {
        override suspend fun apply(preferences: RipDpiProxyUIPreferences): ProxyStrategySnapshot {
            val original = AppSettings.newBuilder().setOobData(state.active).build()
            state.applications += 1
            state.active = "native"
            return ProxyStrategySnapshot(original = original, applied = original)
        }

        override suspend fun restore(snapshot: ProxyStrategySnapshot) {
            state.active = snapshot.original.oobData
        }
    }
}
