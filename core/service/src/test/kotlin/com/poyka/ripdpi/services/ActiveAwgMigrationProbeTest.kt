package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveAwgMigrationProbeTest {
    private val registry = ActiveAwgRuntimeRegistry()
    private val resolver = TestAmneziaWgRuntimeConfigResolver()
    private val request =
        AwgActivationRequest(
            profileId = "awg-migration",
            privateKey = "local-key",
            peerPublicKey = "new-peer",
            endpointHost = "new.example",
            endpointPort = 443,
            interfaceAddressV4 = "10.0.0.2/32",
        )

    @Test
    fun `owned exact runtime performs real payload probe and missing or old config refuses before IO`() =
        runTest {
            var calls = 0
            val probe =
                probe { endpoint, url ->
                    calls++
                    assertEquals(RelayProbeEndpoint("127.0.0.1", 10808), endpoint)
                    assertEquals("https://probe.example/body", url)
                    RelayTcpProbeResult(true, 200)
                }
            assertEquals(AwgMigrationMeasurement.ConnectTargetFirst, probe.measure(request, Url))
            val old = registry.register(resolver.resolve(request.copy(endpointHost = "old.example"))) { true }
            assertEquals(AwgMigrationMeasurement.ConnectTargetFirst, probe.measure(request, Url))
            assertEquals(0, calls)
            registry.register(resolver.resolve(request)) { true }
            registry.revoke(old)
            assertEquals(AwgMigrationMeasurement.Succeeded, probe.measure(request, Url))
            assertEquals(1, calls)
        }

    @Test
    fun `replacement termination network change and failure cannot produce success`() =
        runTest {
            for (mode in listOf("replacement", "termination", "network", "failure")) {
                var alive = true
                var epoch = token(1)
                lateinit var probe: ActiveAwgMigrationProbe
                probe =
                    probe(epoch = { epoch }) { _, _ ->
                        when (mode) {
                            "replacement" -> registry.register(resolver.resolve(request)) { true }
                            "termination" -> alive = false
                            "network" -> epoch = token(2)
                        }
                        RelayTcpProbeResult(mode != "failure", 200)
                    }
                registry.register(resolver.resolve(request)) { alive }
                assertEquals(
                    if (mode ==
                        "failure"
                    ) {
                        AwgMigrationMeasurement.Failed
                    } else {
                        AwgMigrationMeasurement.EnvironmentChanged
                    },
                    probe.measure(request, Url),
                )
            }
        }

    @Test
    fun `caller cancellation remains cancellation`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val probe =
                probe { _, _ ->
                    entered.complete(Unit)
                    CompletableDeferred<RelayTcpProbeResult>().await()
                }
            registry.register(resolver.resolve(request)) { true }
            val job = async { probe.measure(request, Url) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }

    @Test
    fun `supervisor registers ready consumed config and revokes before stop`() =
        runTest {
            val probe = probe { _, _ -> RelayTcpProbeResult(true, 200) }
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    backgroundScope,
                    StandardTestDispatcher(testScheduler),
                    TestRipDpiAmneziaWgFactory(),
                    resolver,
                    activeRegistry = registry,
                )
            assertEquals(AwgMigrationMeasurement.ConnectTargetFirst, probe.measure(request, Url))
            supervisor.start(request) {}
            assertEquals(AwgMigrationMeasurement.Succeeded, probe.measure(request, Url))
            supervisor.stop()
            assertEquals(AwgMigrationMeasurement.ConnectTargetFirst, probe.measure(request, Url))
        }

    private fun probe(
        epoch: () -> CandidatePhysicalNetworkToken? = { token(1) },
        http: suspend (RelayProbeEndpoint, String) -> RelayTcpProbeResult,
    ) = ActiveAwgMigrationProbe(
        resolver,
        registry,
        CandidateHttpPayloadProbe(
            object : RelayTcpProbe {
                override suspend fun probe(
                    endpoint: RelayProbeEndpoint,
                    url: String,
                ) = http(endpoint, url)
            },
            { 0 },
        ),
        { CandidateRelayProbeEnvironment(false, "chrome", emptyMap(), false, false) },
        epoch,
    )

    private fun token(version: Long) = CandidatePhysicalNetworkToken(1, version, "network", "private")

    companion object {
        private const val Url = "https://probe.example/body"
    }
}
