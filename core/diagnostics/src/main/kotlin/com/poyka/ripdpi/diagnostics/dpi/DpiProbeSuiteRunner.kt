package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface DpiSuiteDomainsProvider {
    suspend fun loadDomains(): List<String>
}

fun interface DpiSuiteTcp16TargetsProvider {
    suspend fun loadTargets(): List<Tcp16Target>
}

interface DpiSuiteProbes {
    suspend fun checkDnsIntegrity(domains: List<String>): DnsIntegrityResult

    suspend fun collectStubIpsSilently(
        domains: List<String>,
        timeoutMs: Long,
    ): Set<String>

    suspend fun runDnsAvailability(): List<DnsServerResult>

    suspend fun runDomainReachability(
        domains: List<String>,
        stubIps: Set<String>,
        concurrency: Int,
        randomHostname: Boolean,
    ): List<DomainReachabilityResult>

    suspend fun runTcp16(
        targets: List<Tcp16Target>,
        concurrency: Int,
        randomHostname: Boolean,
    ): List<Tcp16ProbeResult>

    suspend fun findAllowlistSni(results: List<Tcp16ProbeResult>): Map<String, AllowlistSniResult>

    suspend fun runTelegram(): TelegramTestResult

    suspend fun runQuicH3(
        targets: List<String>,
        concurrency: Int,
    ): List<QuicProbeResult>
}

class DpiProbeSuiteRunner(
    private val domainsProvider: DpiSuiteDomainsProvider,
    private val tcp16TargetsProvider: DpiSuiteTcp16TargetsProvider,
    private val probes: DpiSuiteProbes,
) {
    fun run(config: DpiSuiteConfig): Flow<DpiSuiteEvent> =
        channelFlow {
            val domains = config.customDomains?.takeIf { it.isNotEmpty() } ?: domainsProvider.loadDomains()
            val results = mutableListOf<DpiSuiteProbeResult>()
            val resultsMutex = Mutex()

            suspend fun record(
                kind: DpiProbeKind,
                result: DpiSuiteProbeResult,
            ) {
                resultsMutex.withLock {
                    results += result
                }
                send(DpiSuiteEvent.ProbeCompleted(kind = kind, result = result))
            }

            suspend fun runProbe(
                kind: DpiProbeKind,
                block: suspend () -> DpiSuiteProbeResult,
            ): DpiSuiteProbeResult {
                send(DpiSuiteEvent.ProbeStarted(kind))
                send(
                    DpiSuiteEvent.ProbeProgress(
                        kind = kind,
                        completed = 0,
                        total = ProbeProgressTotal,
                    ),
                )
                val result =
                    try {
                        block()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        DpiSuiteProbeResult.Failed(
                            kind = kind,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                send(
                    DpiSuiteEvent.ProbeProgress(
                        kind = kind,
                        completed = ProbeProgressTotal,
                        total = ProbeProgressTotal,
                    ),
                )
                record(kind, result)
                return result
            }

            coroutineScope {
                var stubIps = emptySet<String>()
                if (DpiProbeKind.DNS_INTEGRITY in config.selection) {
                    val result =
                        runProbe(DpiProbeKind.DNS_INTEGRITY) {
                            DpiSuiteProbeResult.DnsIntegrity(probes.checkDnsIntegrity(domains))
                        }
                    if (result is DpiSuiteProbeResult.DnsIntegrity) {
                        stubIps = result.result.stubIps
                    }
                } else if (config.needsStubIps()) {
                    stubIps = collectStubIpsSilently(domains)
                }

                val asyncRuns =
                    buildList {
                        if (DpiProbeKind.DNS_AVAILABILITY in config.selection) {
                            add(
                                async {
                                    runProbe(DpiProbeKind.DNS_AVAILABILITY) {
                                        DpiSuiteProbeResult.DnsAvailability(probes.runDnsAvailability())
                                    }
                                },
                            )
                        }
                        if (DpiProbeKind.TELEGRAM in config.selection) {
                            add(
                                async {
                                    runProbe(DpiProbeKind.TELEGRAM) {
                                        DpiSuiteProbeResult.Telegram(probes.runTelegram())
                                    }
                                },
                            )
                        }
                        if (DpiProbeKind.QUIC_H3 in config.selection) {
                            add(
                                async {
                                    runProbe(DpiProbeKind.QUIC_H3) {
                                        DpiSuiteProbeResult.QuicH3(
                                            probes.runQuicH3(
                                                targets = domains,
                                                concurrency = config.concurrency.coerceAtLeast(1),
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        if (config.selection.any { it in SequentialProbeKinds }) {
                            add(
                                async {
                                    runSequentialDiagnostics(
                                        selection = config.selection,
                                        domains = domains,
                                        stubIps = stubIps,
                                        concurrency = config.concurrency.coerceAtLeast(1),
                                        randomHostname = config.randomHostname,
                                        runProbe = ::runProbe,
                                    )
                                },
                            )
                        }
                    }
                asyncRuns.awaitAll()
            }

            val aggregate =
                resultsMutex.withLock {
                    DpiSuiteVerdictAggregator.aggregate(results.toList())
                }
            send(DpiSuiteEvent.SuiteCompleted(aggregate))
        }

    private suspend fun collectStubIpsSilently(domains: List<String>): Set<String> =
        try {
            probes.collectStubIpsSilently(domains, SilentStubIpTimeoutMs)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptySet()
        }

    private suspend fun runSequentialDiagnostics(
        selection: Set<DpiProbeKind>,
        domains: List<String>,
        stubIps: Set<String>,
        concurrency: Int,
        randomHostname: Boolean,
        runProbe: suspend (DpiProbeKind, suspend () -> DpiSuiteProbeResult) -> DpiSuiteProbeResult,
    ) {
        if (DpiProbeKind.DOMAIN_REACHABILITY in selection) {
            runProbe(DpiProbeKind.DOMAIN_REACHABILITY) {
                DpiSuiteProbeResult.DomainReachability(
                    probes.runDomainReachability(
                        domains = domains,
                        stubIps = stubIps,
                        concurrency = concurrency,
                        randomHostname = randomHostname,
                    ),
                )
            }
        }

        var tcp16Results = emptyList<Tcp16ProbeResult>()
        if (DpiProbeKind.TCP16 in selection) {
            val result =
                runProbe(DpiProbeKind.TCP16) {
                    DpiSuiteProbeResult.Tcp16(
                        probes.runTcp16(
                            targets = tcp16TargetsProvider.loadTargets(),
                            concurrency = concurrency,
                            randomHostname = randomHostname,
                        ),
                    )
                }
            if (result is DpiSuiteProbeResult.Tcp16) {
                tcp16Results = result.results
            }
        }

        if (DpiProbeKind.WHITELIST_SNI in selection) {
            if (tcp16Results.none { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }) {
                runProbe(DpiProbeKind.WHITELIST_SNI) {
                    DpiSuiteProbeResult.Skipped(
                        kind = DpiProbeKind.WHITELIST_SNI,
                        reason = "TCP16 detected zero blocked ASNs",
                    )
                }
            } else {
                runProbe(DpiProbeKind.WHITELIST_SNI) {
                    DpiSuiteProbeResult.AllowlistSni(probes.findAllowlistSni(tcp16Results))
                }
            }
        }
    }

    private fun DpiSuiteConfig.needsStubIps(): Boolean =
        DpiProbeKind.DOMAIN_REACHABILITY in selection || DpiProbeKind.TCP16 in selection

    private companion object {
        private const val ProbeProgressTotal = 1
        private const val SilentStubIpTimeoutMs = 5_000L
        private val SequentialProbeKinds =
            setOf(DpiProbeKind.DOMAIN_REACHABILITY, DpiProbeKind.TCP16, DpiProbeKind.WHITELIST_SNI)
    }
}
