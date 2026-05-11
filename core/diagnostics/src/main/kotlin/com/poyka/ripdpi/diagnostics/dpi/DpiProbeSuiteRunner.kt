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
    ): List<DomainReachabilityResult>

    suspend fun runTcp16(targets: List<Tcp16Target>): List<Tcp16ProbeResult>

    suspend fun findAllowlistSni(results: List<Tcp16ProbeResult>): Map<String, AllowlistSniResult>

    suspend fun runTelegram(): TelegramTestResult
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
                val result =
                    try {
                        block()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        DpiSuiteProbeResult.Failed(kind = kind, error = error.message ?: error.javaClass.simpleName)
                    }
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
                        if (config.selection.any { it in SequentialProbeKinds }) {
                            add(
                                async {
                                    runSequentialDiagnostics(
                                        selection = config.selection,
                                        domains = domains,
                                        stubIps = stubIps,
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
        runProbe: suspend (DpiProbeKind, suspend () -> DpiSuiteProbeResult) -> DpiSuiteProbeResult,
    ) {
        if (DpiProbeKind.DOMAIN_REACHABILITY in selection) {
            runProbe(DpiProbeKind.DOMAIN_REACHABILITY) {
                DpiSuiteProbeResult.DomainReachability(probes.runDomainReachability(domains, stubIps))
            }
        }

        var tcp16Results = emptyList<Tcp16ProbeResult>()
        if (DpiProbeKind.TCP16 in selection) {
            val result =
                runProbe(DpiProbeKind.TCP16) {
                    DpiSuiteProbeResult.Tcp16(probes.runTcp16(tcp16TargetsProvider.loadTargets()))
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
        private const val SilentStubIpTimeoutMs = 5_000L
        private val SequentialProbeKinds =
            setOf(DpiProbeKind.DOMAIN_REACHABILITY, DpiProbeKind.TCP16, DpiProbeKind.WHITELIST_SNI)
    }
}
