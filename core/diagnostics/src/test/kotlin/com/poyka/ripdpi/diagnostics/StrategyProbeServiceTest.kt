package com.poyka.ripdpi.diagnostics

import app.cash.turbine.test
import com.poyka.ripdpi.core.StrategyEngineBindings
import com.poyka.ripdpi.core.StrategyProbeResultDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyProbeServiceTest {
    @Test
    fun `run emits one result per strategy and domain as each probe completes`() =
        runTest {
            val service =
                testService(
                    candidates =
                        listOf(
                            StrategyProbeCandidate("split", "Split"),
                            StrategyProbeCandidate("fake", "Fake"),
                        ),
                    transport =
                        FakeProbeTransport {
                            StrategyProbeConnectionResult.Success(latencyMs = 42)
                        },
                )

            val results =
                service
                    .run(
                        StrategyProbeConfig(
                            testDomains = listOf("one.example", "two.example"),
                            timeoutMs = 1_000,
                            proxyPort = 2080,
                        ),
                    ).toList()

            assertEquals(
                listOf(
                    "split:one.example",
                    "split:two.example",
                    "fake:one.example",
                    "fake:two.example",
                ),
                results.map { "${it.strategyId}:${it.domain}" },
            )
            assertTrue(results.all { it.success })
            assertEquals(listOf("split", "fake"), service.activator.activatedIds)
            val injectedResults = service.injector.injectedResults.single()
            assertEquals(4, injectedResults.size)
            assertTrue(service.activator.restored)
        }

    @Test
    fun `run maps transport timeout to failed result without crashing`() =
        runTest {
            val service =
                testService(
                    transport =
                        FakeProbeTransport {
                            StrategyProbeConnectionResult.Failure(
                                latencyMs = 1_000,
                                error = "timeout",
                                timedOut = true,
                            )
                        },
                )

            val result =
                service
                    .run(StrategyProbeConfig(testDomains = listOf("slow.example"), timeoutMs = 1_000))
                    .toList()
                    .single()

            assertFalse(result.success)
            assertEquals("timeout", result.error)
            assertEquals(StrategyProbeFailureKind.Timeout, result.failureKind)
            assertEquals(listOf(listOf(result)), service.injector.injectedResults)
            assertTrue(service.activator.restored)
        }

    @Test
    fun `run restores active strategy when collection is cancelled`() =
        runTest {
            val transport =
                FakeProbeTransport { request ->
                    if (request.domain == "first.example") {
                        StrategyProbeConnectionResult.Success(latencyMs = 10)
                    } else {
                        awaitCancellation()
                    }
                }
            val service = testService(transport = transport)

            service
                .run(
                    StrategyProbeConfig(
                        testDomains = listOf("first.example", "second.example"),
                        timeoutMs = 10_000,
                    ),
                ).test {
                    assertEquals("first.example", awaitItem().domain)
                    cancelAndIgnoreRemainingEvents()
                }

            assertTrue(transport.cancelled)
            assertTrue(service.injector.injectedResults.isEmpty())
            assertTrue(service.activator.restored)
        }

    @Test
    fun `run flags dns tampering when doh and local address sets differ`() =
        runTest {
            val service =
                testService(
                    dnsComparator =
                        FakeDnsComparator(
                            StrategyProbeDnsComparison(
                                localAddresses = setOf("5.6.7.8"),
                                dohAddresses = setOf("1.2.3.4"),
                                tampered = true,
                            ),
                        ),
                )

            val result =
                service
                    .run(StrategyProbeConfig(testDomains = listOf("tampered.example")))
                    .toList()
                    .single()

            assertTrue(result.dnsTampered)
            assertEquals(setOf("5.6.7.8"), result.dnsComparison?.localAddresses)
            assertEquals(setOf("1.2.3.4"), result.dnsComparison?.dohAddresses)
        }

    @Test
    fun `summarize ranks by success rate then average latency`() {
        val report =
            summarizeStrategyProbeResults(
                listOf(
                    result("a", success = true, latencyMs = 100),
                    result("a", success = true, latencyMs = 100),
                    result("b", success = true, latencyMs = 50),
                    result("b", success = false, latencyMs = 50),
                    result("c", success = true, latencyMs = 200),
                    result("c", success = true, latencyMs = 200),
                ),
            )

        assertEquals(listOf("a", "c", "b"), report.rankedStrategies.map { it.strategyId })
    }

    @Test
    fun `candidate provider does not expose lua strategies without tun egress support metadata`() =
        runTest {
            val provider =
                DefaultStrategyProbeCandidateProvider(
                    FakeStrategyEngineBindings(
                        luaStrategies = arrayOf("multisplit", "helper"),
                        luaScriptPaths = arrayOf("/data/user/0/com.poyka.ripdpi/files/lua/zapret.lua"),
                    ),
                )

            val candidates = provider.listCandidates()

            assertEquals(
                listOf("split", "fake", "oob", "tls_rec_split", "seq_overlap", "udp_fake_burst"),
                candidates.map(StrategyProbeCandidate::id),
            )
            assertFalse(candidates.any { it.id.startsWith("lua:") })
        }

    private fun testService(
        candidates: List<StrategyProbeCandidate> = listOf(StrategyProbeCandidate("split", "Split")),
        transport: StrategyProbeTransport =
            FakeProbeTransport {
                StrategyProbeConnectionResult.Success(latencyMs = 10)
            },
        dnsComparator: StrategyProbeDnsComparator = FakeDnsComparator(StrategyProbeDnsComparison.NoDifference),
    ): TestStrategyProbeService {
        val activator = FakeStrategyProbeActivator()
        val injector = FakeStrategyProbeResultInjector()
        val service =
            DefaultStrategyProbeService(
                candidateProvider = FakeStrategyProbeCandidateProvider(candidates),
                activator = activator,
                transport = transport,
                dnsComparator = dnsComparator,
                resultInjector = injector,
            )
        return TestStrategyProbeService(service = service, activator = activator, injector = injector)
    }

    private fun result(
        strategyId: String,
        success: Boolean,
        latencyMs: Long,
    ): StrategyProbeResult =
        StrategyProbeResult(
            strategyId = strategyId,
            strategyLabel = strategyId,
            domain = "example.test",
            success = success,
            latencyMs = latencyMs,
        )
}

private data class TestStrategyProbeService(
    val service: StrategyProbeService,
    val activator: FakeStrategyProbeActivator,
    val injector: FakeStrategyProbeResultInjector,
) : StrategyProbeService by service

private class FakeStrategyProbeCandidateProvider(
    private val candidates: List<StrategyProbeCandidate>,
) : StrategyProbeCandidateProvider {
    override suspend fun listCandidates(): List<StrategyProbeCandidate> = candidates
}

private class FakeStrategyProbeActivator : StrategyProbeActivator {
    val activatedIds = mutableListOf<String>()
    var restored = false

    override suspend fun capture(): StrategyProbeActivationSnapshot = StrategyProbeActivationSnapshot("previous")

    override suspend fun activate(candidate: StrategyProbeCandidate) {
        activatedIds += candidate.id
    }

    override suspend fun restore(snapshot: StrategyProbeActivationSnapshot) {
        assertEquals("previous", snapshot.snapshotId)
        restored = true
    }
}

private class FakeProbeTransport(
    private val block: suspend (StrategyProbeConnectionRequest) -> StrategyProbeConnectionResult,
) : StrategyProbeTransport {
    var cancelled = false

    override suspend fun probe(request: StrategyProbeConnectionRequest): StrategyProbeConnectionResult =
        try {
            block(request)
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        }
}

private class FakeDnsComparator(
    private val comparison: StrategyProbeDnsComparison,
) : StrategyProbeDnsComparator {
    override suspend fun compare(domain: String): StrategyProbeDnsComparison = comparison
}

private class FakeStrategyProbeResultInjector : StrategyProbeResultInjector {
    val injectedResults = mutableListOf<List<StrategyProbeResult>>()

    override suspend fun inject(results: List<StrategyProbeResult>) {
        injectedResults += results
    }
}

private class FakeStrategyEngineBindings(
    private val luaStrategies: Array<String>,
    private val luaScriptPaths: Array<String>,
) : StrategyEngineBindings {
    override fun luaLoadScript(path: String): String? = null

    override fun luaReloadConfig(): String? = null

    override fun luaListStrategies(): Array<String> = luaStrategies

    override fun luaLoadedScriptPaths(): Array<String> = luaScriptPaths

    override fun luaValidateScript(path: String): String? = null

    override fun validateStrategyConfigText(configText: String): String? = null

    override fun injectProbeResults(results: Array<StrategyProbeResultDto>): String? = null
}
