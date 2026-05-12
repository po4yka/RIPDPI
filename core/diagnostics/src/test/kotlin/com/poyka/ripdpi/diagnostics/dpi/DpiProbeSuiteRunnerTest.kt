package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiProbeSuiteRunnerTest {
    @Test
    fun dnsIntegrityRunsBeforeReachabilityWhenBothSelected() =
        runTest {
            val calls = mutableListOf<String>()
            val runner =
                runner(
                    probes =
                        FakeSuiteProbes(calls).copy(
                            onDnsIntegrity = {
                                calls += "dns"
                                dnsIntegrityResult(stubIps = setOf("198.18.0.1"))
                            },
                            onDomainReachability = { _, stubIps, _, _ ->
                                calls += "domain:${stubIps.single()}"
                                emptyList()
                            },
                        ),
                )

            runner
                .run(
                    DpiSuiteConfig(
                        selection =
                            setOf(
                                DpiProbeKind.DNS_INTEGRITY,
                                DpiProbeKind.DOMAIN_REACHABILITY,
                            ),
                    ),
                ).toList()

            assertEquals(listOf("dns", "domain:198.18.0.1"), calls)
        }

    @Test
    fun silentStubIpCollectionWhenDnsSkippedButReachabilitySelected() =
        runTest {
            val probes =
                FakeSuiteProbes().copy(
                    onCollectStubIps = { _, timeoutMs ->
                        assertEquals(5_000L, timeoutMs)
                        setOf("198.18.0.2")
                    },
                    onDomainReachability = { _, stubIps, _, _ ->
                        assertEquals(setOf("198.18.0.2"), stubIps)
                        emptyList()
                    },
                )

            runner(probes = probes)
                .run(DpiSuiteConfig(selection = setOf(DpiProbeKind.DOMAIN_REACHABILITY)))
                .toList()

            assertEquals(1, probes.collectStubIpsCalls)
        }

    @Test
    fun whitelistSniAutoSkippedWhenTcp16ZeroBlockedAsns() =
        runTest {
            val probes =
                FakeSuiteProbes().copy(
                    onTcp16 = { _, _, _ -> emptyList() },
                )

            val events =
                runner(probes = probes)
                    .run(DpiSuiteConfig(selection = setOf(DpiProbeKind.TCP16, DpiProbeKind.WHITELIST_SNI)))
                    .toList()

            assertEquals(0, probes.allowlistCalls)
            assertTrue(
                events
                    .filterIsInstance<DpiSuiteEvent.ProbeCompleted>()
                    .any { event ->
                        event.kind == DpiProbeKind.WHITELIST_SNI &&
                            event.result is DpiSuiteProbeResult.Skipped
                    },
            )
        }

    @Test
    fun cancellationPropagatesToAllInflightProbes() =
        runTest {
            val probes =
                FakeSuiteProbes().copy(
                    onDnsAvailability = {
                        try {
                            delay(Long.MAX_VALUE)
                            emptyList()
                        } catch (error: CancellationException) {
                            probesCancelled += DpiProbeKind.DNS_AVAILABILITY
                            throw error
                        }
                    },
                    onTelegram = {
                        try {
                            delay(Long.MAX_VALUE)
                            telegramResult()
                        } catch (error: CancellationException) {
                            probesCancelled += DpiProbeKind.TELEGRAM
                            throw error
                        }
                    },
                )
            val job =
                launch {
                    runner(probes = probes)
                        .run(DpiSuiteConfig(selection = setOf(DpiProbeKind.DNS_AVAILABILITY, DpiProbeKind.TELEGRAM)))
                        .toList()
                }

            delay(100)
            job.cancel()
            job.join()

            assertEquals(setOf(DpiProbeKind.DNS_AVAILABILITY, DpiProbeKind.TELEGRAM), probesCancelled)
        }

    @Test
    fun customDomainsOverrideBundledAssetsForThisRun() =
        runTest {
            val probes =
                FakeSuiteProbes().copy(
                    onDnsIntegrity = { domains ->
                        assertEquals(listOf("foo.example"), domains)
                        dnsIntegrityResult()
                    },
                )

            runner(probes = probes, domains = listOf("bundled.example"))
                .run(
                    DpiSuiteConfig(
                        selection = setOf(DpiProbeKind.DNS_INTEGRITY),
                        customDomains = listOf("foo.example"),
                    ),
                ).toList()
        }

    @Test
    fun emitsProgressEventsAroundEachSelectedProbe() =
        runTest {
            val events =
                runner()
                    .run(DpiSuiteConfig(selection = setOf(DpiProbeKind.DNS_AVAILABILITY)))
                    .toList()

            assertEquals(
                listOf(
                    DpiSuiteEvent.ProbeProgress(DpiProbeKind.DNS_AVAILABILITY, completed = 0, total = 1),
                    DpiSuiteEvent.ProbeProgress(DpiProbeKind.DNS_AVAILABILITY, completed = 1, total = 1),
                ),
                events.filterIsInstance<DpiSuiteEvent.ProbeProgress>(),
            )
        }

    @Test
    fun passesConfiguredConcurrencyToReachabilityAndTcp16Probes() =
        runTest {
            val observedConcurrency = mutableListOf<Int>()
            val probes =
                FakeSuiteProbes().copy(
                    onDomainReachability = { _, _, concurrency, _ ->
                        observedConcurrency += concurrency
                        emptyList()
                    },
                    onTcp16 = { _, concurrency, _ ->
                        observedConcurrency += concurrency
                        emptyList()
                    },
                )

            runner(probes = probes)
                .run(
                    DpiSuiteConfig(
                        selection = setOf(DpiProbeKind.DOMAIN_REACHABILITY, DpiProbeKind.TCP16),
                        concurrency = 7,
                    ),
                ).toList()

            assertEquals(listOf(7, 7), observedConcurrency)
        }

    @Test
    fun passesRandomHostnameFlagToReachabilityAndTcp16Probes() =
        runTest {
            val observed = mutableListOf<Boolean>()
            val probes =
                FakeSuiteProbes().copy(
                    onDomainReachability = { _, _, _, randomHostname ->
                        observed += randomHostname
                        emptyList()
                    },
                    onTcp16 = { _, _, randomHostname ->
                        observed += randomHostname
                        emptyList()
                    },
                )

            runner(probes = probes)
                .run(
                    DpiSuiteConfig(
                        selection = setOf(DpiProbeKind.DOMAIN_REACHABILITY, DpiProbeKind.TCP16),
                        randomHostname = true,
                    ),
                ).toList()

            assertEquals(listOf(true, true), observed)
        }

    @Test
    fun quicH3ProbeUsesSelectedDomainsAndConcurrency() =
        runTest {
            val probes =
                FakeSuiteProbes().copy(
                    onQuicH3 = { domains, concurrency ->
                        assertEquals(listOf("h3.example"), domains)
                        assertEquals(8, concurrency)
                        emptyList()
                    },
                )

            runner(probes = probes)
                .run(
                    DpiSuiteConfig(
                        selection = setOf(DpiProbeKind.QUIC_H3),
                        customDomains = listOf("h3.example"),
                        concurrency = 8,
                    ),
                ).toList()
        }

    @Test
    fun quicH3ProbeCapsSuiteConcurrencyAtEight() =
        runTest {
            val observedConcurrency = mutableListOf<Int>()
            val probes =
                FakeSuiteProbes().copy(
                    onQuicH3 = { _, concurrency ->
                        observedConcurrency += concurrency
                        emptyList()
                    },
                )

            runner(probes = probes)
                .run(
                    DpiSuiteConfig(
                        selection = setOf(DpiProbeKind.QUIC_H3),
                        concurrency = 100,
                    ),
                ).toList()

            assertEquals(listOf(8), observedConcurrency)
        }

    @Test
    fun echReadinessRunsAfterReachabilityWithVanillaTlsCrossReference() =
        runTest {
            val calls = mutableListOf<String>()
            val observedVanillaTlsByTarget = mutableListOf<Map<String, Boolean>>()
            val observedConcurrency = mutableListOf<Int>()
            val probes =
                FakeSuiteProbes(calls).copy(
                    onDomainReachability = { _, _, _, _ ->
                        calls += "domain-reachability"
                        listOf(
                            DomainReachabilityResult(
                                domain = "cloudflare.com",
                                resolvedIps = listOf("1.1.1.1"),
                                tls13 = AttemptResult(AttemptStatus.OK, latencyMs = 40),
                                tls12 = AttemptResult(AttemptStatus.OK, latencyMs = 45),
                                http = AttemptResult(AttemptStatus.OK, latencyMs = 50),
                                verdict = DomainVerdict.OK,
                            ),
                        )
                    },
                    onEchReadiness = { targets, vanillaTlsByTarget, concurrency ->
                        calls += "ech-readiness"
                        assertEquals(listOf("cloudflare.com"), targets)
                        observedVanillaTlsByTarget += vanillaTlsByTarget
                        observedConcurrency += concurrency
                        listOf(
                            EchProbeResult(
                                target = "cloudflare.com",
                                verdict = EchProbeVerdict.ECH_OK,
                                httpsRrFetched = true,
                                echConfigBytesB64 = "AQID",
                                tlsLatencyMs = 32,
                                negotiatedEch = true,
                                errorDetail = null,
                                vanillaTlsOk = false,
                            ),
                        )
                    },
                )

            val results =
                runner(
                    probes = probes,
                    domains = listOf("cloudflare.com"),
                    echTargets = listOf("cloudflare.com"),
                ).run(
                    DpiSuiteConfig(
                        selection = setOf(DpiProbeKind.DOMAIN_REACHABILITY, DpiProbeKind.ECH_READINESS),
                        concurrency = 16,
                    ),
                ).filterIsInstance<DpiSuiteEvent.ProbeCompleted>()
                    .toList()
                    .map { event -> event.kind }

            assertEquals(listOf("domain-reachability", "ech-readiness"), calls)
            assertEquals(listOf(mapOf("cloudflare.com" to true)), observedVanillaTlsByTarget)
            assertEquals(listOf(4), observedConcurrency)
            assertEquals(listOf(DpiProbeKind.DOMAIN_REACHABILITY, DpiProbeKind.ECH_READINESS), results)
        }

    private fun runner(
        probes: FakeSuiteProbes = FakeSuiteProbes(),
        domains: List<String> = listOf("example.com"),
        tcp16Targets: List<Tcp16Target> = listOf(tcp16Target()),
        echTargets: List<String> = listOf("cloudflare.com"),
    ): DpiProbeSuiteRunner =
        DpiProbeSuiteRunner(
            domainsProvider = { domains },
            tcp16TargetsProvider = { tcp16Targets },
            echTargetsProvider = { echTargets },
            probes = probes,
        )

    private class FakeSuiteProbes(
        private val calls: MutableList<String> = mutableListOf(),
        private val onDnsIntegrity: suspend (List<String>) -> DnsIntegrityResult = { dnsIntegrityResult() },
        private val onCollectStubIps: suspend (List<String>, Long) -> Set<String> = { _, _ -> emptySet() },
        private val onDnsAvailability: suspend () -> List<DnsServerResult> = { emptyList() },
        private val onDomainReachability: suspend (
            List<String>,
            Set<String>,
            Int,
            Boolean,
        ) -> List<DomainReachabilityResult> = { _, _, _, _ -> emptyList() },
        private val onTcp16: suspend (
            List<Tcp16Target>,
            Int,
            Boolean,
        ) -> List<Tcp16ProbeResult> = { _, _, _ -> emptyList() },
        private val onAllowlist: suspend (List<Tcp16ProbeResult>) -> Map<String, AllowlistSniResult> = { emptyMap() },
        private val onTelegram: suspend () -> TelegramTestResult = { telegramResult() },
        private val onQuicH3: suspend (List<String>, Int) -> List<QuicProbeResult> = { _, _ -> emptyList() },
        private val onEchReadiness: suspend (List<String>, Map<String, Boolean>, Int) -> List<EchProbeResult> =
            { _, _, _ -> emptyList() },
    ) : DpiSuiteProbes {
        var collectStubIpsCalls = 0
        var allowlistCalls = 0

        override suspend fun checkDnsIntegrity(domains: List<String>): DnsIntegrityResult = onDnsIntegrity(domains)

        override suspend fun collectStubIpsSilently(
            domains: List<String>,
            timeoutMs: Long,
        ): Set<String> {
            collectStubIpsCalls += 1
            return onCollectStubIps(domains, timeoutMs)
        }

        override suspend fun runDnsAvailability(): List<DnsServerResult> = onDnsAvailability()

        override suspend fun runDomainReachability(
            domains: List<String>,
            stubIps: Set<String>,
            concurrency: Int,
            randomHostname: Boolean,
        ): List<DomainReachabilityResult> = onDomainReachability(domains, stubIps, concurrency, randomHostname)

        override suspend fun runTcp16(
            targets: List<Tcp16Target>,
            concurrency: Int,
            randomHostname: Boolean,
        ): List<Tcp16ProbeResult> = onTcp16(targets, concurrency, randomHostname)

        override suspend fun findAllowlistSni(results: List<Tcp16ProbeResult>): Map<String, AllowlistSniResult> {
            allowlistCalls += 1
            calls += "allowlist"
            return onAllowlist(results)
        }

        override suspend fun runTelegram(): TelegramTestResult = onTelegram()

        override suspend fun runQuicH3(
            targets: List<String>,
            concurrency: Int,
        ): List<QuicProbeResult> = onQuicH3(targets, concurrency)

        override suspend fun runEchReadiness(
            targets: List<String>,
            vanillaTlsByTarget: Map<String, Boolean>,
            concurrency: Int,
        ): List<EchProbeResult> = onEchReadiness(targets, vanillaTlsByTarget, concurrency)

        fun copy(
            onDnsIntegrity: suspend (List<String>) -> DnsIntegrityResult = this.onDnsIntegrity,
            onCollectStubIps: suspend (List<String>, Long) -> Set<String> = this.onCollectStubIps,
            onDnsAvailability: suspend () -> List<DnsServerResult> = this.onDnsAvailability,
            onDomainReachability: suspend (List<String>, Set<String>, Int, Boolean) -> List<DomainReachabilityResult> =
                this.onDomainReachability,
            onTcp16: suspend (List<Tcp16Target>, Int, Boolean) -> List<Tcp16ProbeResult> = this.onTcp16,
            onAllowlist: suspend (List<Tcp16ProbeResult>) -> Map<String, AllowlistSniResult> = this.onAllowlist,
            onTelegram: suspend () -> TelegramTestResult = this.onTelegram,
            onQuicH3: suspend (List<String>, Int) -> List<QuicProbeResult> = this.onQuicH3,
            onEchReadiness: suspend (List<String>, Map<String, Boolean>, Int) -> List<EchProbeResult> =
                this.onEchReadiness,
        ): FakeSuiteProbes =
            FakeSuiteProbes(
                calls = calls,
                onDnsIntegrity = onDnsIntegrity,
                onCollectStubIps = onCollectStubIps,
                onDnsAvailability = onDnsAvailability,
                onDomainReachability = onDomainReachability,
                onTcp16 = onTcp16,
                onAllowlist = onAllowlist,
                onTelegram = onTelegram,
                onQuicH3 = onQuicH3,
                onEchReadiness = onEchReadiness,
            )
    }

    private companion object {
        val probesCancelled = mutableSetOf<DpiProbeKind>()

        fun dnsIntegrityResult(stubIps: Set<String> = emptySet()): DnsIntegrityResult =
            DnsIntegrityResult(
                domains = emptyList(),
                stubIps = stubIps,
                dohBlocked = 0,
            )

        fun telegramResult(): TelegramTestResult =
            TelegramTestResult(
                verdict = TelegramTestVerdict.OK,
                download = ProbeStats(TelegramProbeStatus.OK, 100, 10, 10_000, 100),
                upload = ProbeStats(TelegramProbeStatus.OK, 100, 10, 10_000, 100),
                dcResults = emptyList(),
            )

        fun tcp16Target(): Tcp16Target =
            Tcp16Target(
                id = "target",
                asn = "AS1",
                provider = "provider",
                ip = "1.1.1.1",
                port = 443,
                sni = null,
            )
    }
}
