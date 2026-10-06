package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CandidateRelayPayloadProbeTest {
    @Test
    fun `successful candidate uses its credentials ephemeral listener and active VPN protection then stops`() =
        runTest {
            val runtime = FakeCandidateRuntime()
            val env = environment().copy(vpnProtectionRequired = true)
            var resolvedProfile: RelayProfileRecord? = null
            var resolvedCredentials: RelayCredentialRecord? = null
            val probe =
                CandidateRelayPayloadProbe(
                    resolve = { profile, credentials, _ ->
                        resolvedProfile = profile
                        resolvedCredentials = credentials
                        sampleResolvedRelayConfig().copy(kind = "trojan")
                    },
                    runtimeFactory = factory(runtime),
                    httpProbe =
                        capabilities { endpoint, url ->
                            assertEquals(RelayProbeEndpoint("127.0.0.1", 1234), endpoint)
                            assertEquals("https://configured.example/probe", url)
                            RelayTcpProbeResult(true, 204)
                        },
                    environment = { env },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val profile = RelayProfileRecord(id = "candidate", kind = "trojan")
            val credentials = RelayCredentialRecord(profileId = "candidate", trojanPassword = CredentialFixture)
            assertTrue(
                probe.measure(
                    profile,
                    credentials,
                    "https://configured.example/probe",
                ) is CandidateRelayMeasurement.Succeeded,
            )
            assertEquals(profile, resolvedProfile)
            assertEquals(credentials, resolvedCredentials)
            assertEquals(0, runtime.config?.localSocksPort)
            assertEquals(RelaySocketProtection.VpnRequired, runtime.config?.socketProtection)
            assertEquals(1, runtime.stops)
            assertTrue(runtime.finished)
        }

    @Test
    fun `readiness failure and unsuccessful payload both stop candidate runtime`() =
        runTest {
            for (failReady in listOf(true, false)) {
                val runtime = FakeCandidateRuntime(failReady)
                val probe = create(runtime, capabilities { _, _ -> RelayTcpProbeResult(false) })
                assertEquals(
                    CandidateRelayMeasurement.Failed(
                        if (failReady) CandidateMeasurementStage.Ready else CandidateMeasurementStage.Http,
                    ),
                    probe.measure(Profile, Credentials, "https://probe"),
                )
                assertEquals(1, runtime.stops)
                assertTrue(runtime.finished)
            }
        }

    @Test
    fun `caller cancellation closes runtime and remains cancellation`() =
        runTest {
            val runtime = FakeCandidateRuntime()
            val entered = CompletableDeferred<Unit>()
            val probe =
                create(
                    runtime,
                    capabilities { _, _ ->
                        entered.complete(Unit)
                        CompletableDeferred<RelayTcpProbeResult>().await()
                    },
                )
            val pending = async { probe.measure(Profile, Credentials, "https://probe") }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, runtime.stops)
            assertTrue(runtime.finished)
        }

    @Test
    fun `own deadline rejects stalled candidate and cleans up`() =
        runTest {
            val runtime = FakeCandidateRuntime()
            val probe = create(runtime, capabilities { _, _ -> CompletableDeferred<RelayTcpProbeResult>().await() })
            assertEquals(
                CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Http),
                probe.measure(Profile, Credentials, "https://probe"),
            )
            assertEquals(1, runtime.stops)
            assertTrue(runtime.finished)
        }

    @Test
    fun `runtime environment changes invalidate an otherwise successful payload`() =
        runTest {
            val runtime = FakeCandidateRuntime()
            var env = environment()
            val probe =
                CandidateRelayPayloadProbe(
                    resolve = { _, _, _ -> sampleResolvedRelayConfig().copy(kind = "trojan") },
                    runtimeFactory = factory(runtime),
                    httpProbe =
                        capabilities { _, _ ->
                            env = env.copy(tlsProfile = "changed")
                            RelayTcpProbeResult(true, 204)
                        },
                    environment = { env },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            assertEquals(
                CandidateRelayMeasurement.EnvironmentChanged,
                probe.measure(Profile, Credentials, "https://probe"),
            )
            assertEquals(1, runtime.stops)
        }

    @Test
    fun `unfinished native cleanup prevents new candidates until the old task finishes`() =
        runTest {
            val old = FakeCandidateRuntime(stopCompletesAfter = 3)
            val next = FakeCandidateRuntime()
            var creates = 0
            val probe =
                CandidateRelayPayloadProbe(
                    resolve = { _, _, _ -> sampleResolvedRelayConfig().copy(kind = "trojan") },
                    runtimeFactory =
                        object : RipDpiRelayFactory {
                            override fun create(): RipDpiRelayRuntime = if (creates++ == 0) old else next
                        },
                    httpProbe = capabilities { _, _ -> RelayTcpProbeResult(true, 204) },
                    environment = { environment() },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(Profile, Credentials, "https://probe"))
            assertEquals(1, creates)
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(Profile, Credentials, "https://probe"))
            assertEquals(1, creates)
            assertTrue(probe.measure(Profile, Credentials, "https://probe") is CandidateRelayMeasurement.Succeeded)
            assertEquals(2, creates)
            assertTrue(old.finished)
            assertTrue(next.finished)
        }

    @Test
    fun `stalled stop is bounded retained and never runs concurrent stop calls`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val old = FakeCandidateRuntime(stopRelease = release)
            val next = FakeCandidateRuntime()
            var creates = 0
            val probe =
                CandidateRelayPayloadProbe(
                    resolve = { _, _, _ -> sampleResolvedRelayConfig().copy(kind = "trojan") },
                    runtimeFactory =
                        object : RipDpiRelayFactory {
                            override fun create(): RipDpiRelayRuntime = if (creates++ == 0) old else next
                        },
                    httpProbe = capabilities { _, _ -> RelayTcpProbeResult(true, 204) },
                    environment = { environment() },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(Profile, Credentials, "https://probe"))
            assertEquals(5_000L, testScheduler.currentTime)
            assertEquals(1, old.stops)
            assertEquals(1, creates)
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(Profile, Credentials, "https://probe"))
            assertEquals(1, old.stops)
            assertEquals(1, creates)
            release.complete(Unit)
            runCurrent()
            assertTrue(probe.measure(Profile, Credentials, "https://probe") is CandidateRelayMeasurement.Succeeded)
            assertTrue(old.finished)
            assertTrue(next.finished)
            assertEquals(2, creates)
        }

    @Test
    fun `caller cancellation remains cancellation when native stop misses its deadline`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val runtime = FakeCandidateRuntime(stopRelease = release)
            val entered = CompletableDeferred<Unit>()
            val probe =
                create(
                    runtime,
                    capabilities { _, _ ->
                        entered.complete(Unit)
                        CompletableDeferred<RelayTcpProbeResult>().await()
                    },
                )
            val pending = async { probe.measure(Profile, Credentials, "https://probe") }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(5_000L, testScheduler.currentTime)
            assertEquals(1, runtime.stops)
            assertTrue(!runtime.finished)
            release.complete(Unit)
            runCurrent()
            assertTrue(runtime.finished)
        }

    @Test
    fun `readiness and complete HTTP body each receive their own deadline budget`() =
        runTest {
            val runtime = FakeCandidateRuntime(readyDelayMillis = 4_000)
            val probe =
                create(
                    runtime,
                    capabilities { _, _ ->
                        kotlinx.coroutines.delay(14_000)
                        RelayTcpProbeResult(true, 204)
                    },
                )
            assertTrue(probe.measure(Profile, Credentials, "https://probe") is CandidateRelayMeasurement.Succeeded)
            assertEquals(18_000L, testScheduler.currentTime)
            assertEquals(1, runtime.stops)
            assertTrue(runtime.finished)
        }

    @Test
    fun `readiness timeout never attempts HTTP and releases its native owner`() =
        runTest {
            val runtime = FakeCandidateRuntime(readyDelayMillis = 6_000)
            val probe = create(runtime, capabilities { _, _ -> error("HTTP cannot begin before Ready") })
            assertEquals(
                CandidateRelayMeasurement.TimedOut(CandidateMeasurementStage.Ready),
                probe.measure(Profile, Credentials, "https://probe"),
            )
            assertEquals(5_000L, testScheduler.currentTime)
            assertEquals(1, runtime.stops)
            assertTrue(runtime.finished)
        }

    @Test
    fun `failed stop remains owned even when the native task already exited`() =
        runTest {
            val old = FakeCandidateRuntime(stopFailsAfterRelease = true)
            val next = FakeCandidateRuntime()
            var creates = 0
            val probe =
                CandidateRelayPayloadProbe(
                    resolve = { _, _, _ -> sampleResolvedRelayConfig().copy(kind = "trojan") },
                    runtimeFactory =
                        object : RipDpiRelayFactory {
                            override fun create(): RipDpiRelayRuntime = if (creates++ == 0) old else next
                        },
                    httpProbe = capabilities { _, _ -> RelayTcpProbeResult(true, 204) },
                    environment = { environment() },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            assertEquals(CandidateRelayMeasurement.CleanupPending, probe.measure(Profile, Credentials, "https://probe"))
            assertTrue(probe.cleanupPending.value)
            assertTrue(old.finished)
            assertEquals(1, creates)
            old.stopFailsAfterRelease = false
            assertTrue(probe.measure(Profile, Credentials, "https://probe") is CandidateRelayMeasurement.Succeeded)
            assertTrue(!probe.cleanupPending.value)
            assertEquals(2, creates)
            assertEquals(2, old.stops)
        }

    private fun kotlinx.coroutines.test.TestScope.create(
        runtime: FakeCandidateRuntime,
        capabilities: CandidateHttpPayloadProbe,
    ) = CandidateRelayPayloadProbe(
        resolve = { _, _, _ -> sampleResolvedRelayConfig().copy(kind = "trojan") },
        runtimeFactory = factory(runtime),
        httpProbe = capabilities,
        environment = { environment() },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun factory(runtime: FakeCandidateRuntime) =
        object : RipDpiRelayFactory {
            override fun create(): RipDpiRelayRuntime = runtime
        }

    private fun capabilities(probe: RelayTcpProbe) = CandidateHttpPayloadProbe(probe, System::nanoTime)

    private fun environment() =
        CandidateRelayProbeEnvironment(
            false,
            "chrome_stable",
            emptyMap(),
            false,
            false,
        )

    private companion object {
        const val CredentialFixture = "candidate-credential-fixture"

        val Profile = RelayProfileRecord(id = "candidate", kind = "trojan")
        val Credentials = RelayCredentialRecord(profileId = "candidate", trojanPassword = CredentialFixture)
    }
}

private class FakeCandidateRuntime(
    private val failReady: Boolean = false,
    private val stopCompletesAfter: Int = 1,
    private val stopRelease: CompletableDeferred<Unit>? = null,
    private val readyDelayMillis: Long = 0,
    var stopFailsAfterRelease: Boolean = false,
) : RipDpiRelayRuntime {
    var config: ResolvedRipDpiRelayConfig? = null
    var stops = 0
    var finished = false
    private val stopped = CompletableDeferred<Unit>()

    override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
        this.config = config
        try {
            stopped.await()
            return 0
        } finally {
            finished = true
        }
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        kotlinx.coroutines.delay(readyDelayMillis)
        if (failReady) error("candidate readiness failed")
        check(config != null)
    }

    override suspend fun stop() {
        stops++
        stopRelease?.await()
        if (stops >= stopCompletesAfter) stopped.complete(Unit)
        check(!stopFailsAfterRelease) { "Native stop failed after its task completed" }
    }

    override suspend fun pollTelemetry() = NativeRuntimeSnapshot(source = "relay", listenerAddress = "127.0.0.1:1234")
}
