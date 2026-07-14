package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniFinder
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsServerResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeSuiteRunner
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteConfig
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteDomainsProvider
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteEchTargetsProvider
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteEvent
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbes
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteTcp16TargetsProvider
import com.poyka.ripdpi.diagnostics.dpi.EchProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchReadinessProbe
import com.poyka.ripdpi.diagnostics.dpi.EchTlsHandshake
import com.poyka.ripdpi.diagnostics.dpi.FixtureBackedQuicFingerprintFactory
import com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint
import com.poyka.ripdpi.diagnostics.dpi.QuicH3FingerprintProbe
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Target
import com.poyka.ripdpi.diagnostics.dpi.TelegramSpeedTest
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizer
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val SuiteDnsIntegrityPreviewDomainLimit = 5

internal class DiagnosticsDpiSuiteRunnerFactory(
    private val dnsIntegrityChecker: DnsIntegrityChecker,
    private val dnsAvailabilitySurvey: DnsAvailabilitySurvey,
    private val domainReachabilityScanner: DomainReachabilityScanner,
    private val tcp16FatHeaderProbe: Tcp16FatHeaderProbe,
    private val assetLoader: DpiAssetLoader,
    private val echTlsHandshake: EchTlsHandshake,
) {
    fun build(): DpiProbeSuiteRunner =
        DpiProbeSuiteRunner(
            domainsProvider = DpiSuiteDomainsProvider { loadDomains() },
            tcp16TargetsProvider = DpiSuiteTcp16TargetsProvider { loadTcp16Targets() },
            echTargetsProvider = DpiSuiteEchTargetsProvider { loadEchTargets() },
            probes = buildProbes(),
        )

    private fun buildProbes(): DpiSuiteProbes =
        object : DpiSuiteProbes {
            override suspend fun checkDnsIntegrity(domains: List<String>): DnsIntegrityResult =
                dnsIntegrityChecker.check(domains)

            override suspend fun collectStubIpsSilently(
                domains: List<String>,
                timeoutMs: Long,
            ): Set<String> =
                withTimeout(timeoutMs) {
                    dnsIntegrityChecker.check(domains.take(SuiteDnsIntegrityPreviewDomainLimit)).stubIps
                }

            override suspend fun runDnsAvailability(): List<DnsServerResult> = dnsAvailabilitySurvey.run()

            override suspend fun runDomainReachability(
                domains: List<String>,
                stubIps: Set<String>,
                concurrency: Int,
                randomHostname: Boolean,
            ): List<DomainReachabilityResult> =
                if (concurrency == DpiSuiteConfig.DefaultConcurrency) {
                    domainReachabilityScanner.scan(domains, stubIps, randomHostname = randomHostname)
                } else {
                    domainReachabilityScanner.withMaxConcurrent(concurrency).scan(domains, stubIps, randomHostname)
                }

            override suspend fun runTcp16(
                targets: List<Tcp16Target>,
                concurrency: Int,
                randomHostname: Boolean,
            ): List<Tcp16ProbeResult> =
                if (concurrency == DpiSuiteConfig.DefaultConcurrency) {
                    tcp16FatHeaderProbe.run(targets, randomHostname = randomHostname)
                } else {
                    tcp16FatHeaderProbe.withConcurrency(concurrency).run(targets, randomHostname = randomHostname)
                }

            override suspend fun findAllowlistSni(results: List<Tcp16ProbeResult>) =
                AllowlistSniFinder(tcp16FatHeaderProbe, loadWhitelistSni()).find(results)

            override suspend fun runTelegram() = TelegramSpeedTest().run()

            override suspend fun runQuicH3(
                targets: List<String>,
                concurrency: Int,
            ): List<QuicProbeResult> =
                QuicH3FingerprintProbe(
                    packetFactory = FixtureBackedQuicFingerprintFactory(loadQuicFingerprintFixtures()),
                    concurrency = concurrency,
                ).checkAll(targets)

            override suspend fun runEchReadiness(
                targets: List<String>,
                vanillaTlsByTarget: Map<String, Boolean>,
                concurrency: Int,
            ): List<EchProbeResult> =
                EchReadinessProbe(
                    tlsHandshake = echTlsHandshake,
                    concurrency = concurrency,
                ).checkAll(targets, vanillaTlsByTarget)
        }

    private suspend fun loadDomains(): List<String> = withContext(Dispatchers.IO) { assetLoader.loadDomains() }

    private suspend fun loadTcp16Targets(): List<Tcp16Target> =
        withContext(Dispatchers.IO) {
            assetLoader.loadTcp16Targets()
        }

    private suspend fun loadWhitelistSni(): List<String> =
        withContext(Dispatchers.IO) { assetLoader.loadWhitelistSni() }

    private suspend fun loadEchTargets(): List<String> = withContext(Dispatchers.IO) { assetLoader.loadEchTargets() }

    private suspend fun loadQuicFingerprintFixtures(): Map<QuicFingerprint, ByteArray> =
        withContext(Dispatchers.IO) { assetLoader.loadQuicFingerprintFixtures() }
}

internal class DiagnosticsDpiSuiteTlsRunFinalizer(
    private val diagnosticsFiles: DiagnosticsFiles,
    private val finalizer: TlsKeylogRunFinalizer,
) {
    suspend fun finish(settings: AppSettings) {
        val path = settings.effectiveDiagnosticTlsKeylogPath(diagnosticsFiles.appFilesDir) ?: return
        withContext(NonCancellable + Dispatchers.IO) { finalizer.finishRun(path) }
    }
}

internal fun parseCustomDomains(input: String): List<String>? =
    input
        .lineSequence()
        .flatMap { line -> line.split(',', ' ', ';').asSequence() }
        .map { value -> value.trim().trimEnd('/') }
        .filter(String::isNotBlank)
        .toList()
        .takeIf(List<String>::isNotEmpty)

internal fun MutableList<DiagnosticsDpiSuiteProbeRowUiModel>.upsert(row: DiagnosticsDpiSuiteProbeRowUiModel) {
    val index = indexOfFirst { existing -> existing.kind == row.kind }
    if (index >= 0) this[index] = row else add(row)
}

internal fun DpiProbeKind.startedRow(): DiagnosticsDpiSuiteProbeRowUiModel =
    DiagnosticsDpiSuiteProbeRowUiModel(
        kind = this,
        label = displayLabel(),
        status = "running",
        detail = "Probe is in progress.",
        tone = DiagnosticsTone.Info,
    )

internal fun DpiSuiteEvent.ProbeProgress.progressRow(): DiagnosticsDpiSuiteProbeRowUiModel =
    DiagnosticsDpiSuiteProbeRowUiModel(
        kind = kind,
        label = kind.displayLabel(),
        status = "running",
        detail = "$completed/$total steps complete.",
        tone = DiagnosticsTone.Info,
    )
