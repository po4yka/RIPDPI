package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniFinder
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DnsServerResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.Tcp16AsnSummary
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Target
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.dpi.byAsn
import com.poyka.ripdpi.diagnostics.dpich.CompressionCodec
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeResult
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeVerdict
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.rkn.RknAggregateVerdictEngine
import com.poyka.ripdpi.diagnostics.rkn.RknCheckResult
import com.poyka.ripdpi.diagnostics.rkn.RknConfidence
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.RknProbeTarget
import com.poyka.ripdpi.diagnostics.rkn.RknTarget
import com.poyka.ripdpi.diagnostics.rkn.RknVerdict
import com.poyka.ripdpi.diagnostics.rkn.RknVerdictColorToken
import com.poyka.ripdpi.diagnostics.rkn.RknVerdictLabel
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoResult
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val DnsIntegrityPreviewDomainLimit = 5

internal class DiagnosticsDpiToolsController(
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val dnsIntegrityChecker: DnsIntegrityChecker = DnsIntegrityChecker(),
    private val dnsAvailabilitySurvey: DnsAvailabilitySurvey = DnsAvailabilitySurvey(),
    private val domainReachabilityScanner: DomainReachabilityScanner = DomainReachabilityScanner(),
    private val tcp16FatHeaderProbe: Tcp16FatHeaderProbe = Tcp16FatHeaderProbe(),
    private val httpCompressionProber: HttpCompressionProber = HttpCompressionProber(),
    private val rknLayeredProbePipeline: RknLayeredProbePipeline = RknLayeredProbePipeline(),
    private val selfInfoFetcher: SelfInfoFetcher,
    private val assetLoader: DpiAssetLoader,
) {
    private val _dnsIntegrityTool = MutableStateFlow(DiagnosticsDnsIntegrityToolUiModel())
    val dnsIntegrityTool: StateFlow<DiagnosticsDnsIntegrityToolUiModel> = _dnsIntegrityTool.asStateFlow()

    private val _dnsAvailabilityTool = MutableStateFlow(DiagnosticsDnsAvailabilityToolUiModel())
    val dnsAvailabilityTool: StateFlow<DiagnosticsDnsAvailabilityToolUiModel> =
        _dnsAvailabilityTool.asStateFlow()

    private val _domainReachabilityTool = MutableStateFlow(DiagnosticsDomainReachabilityToolUiModel())
    val domainReachabilityTool: StateFlow<DiagnosticsDomainReachabilityToolUiModel> =
        _domainReachabilityTool.asStateFlow()

    private val _rknBlockDiagnosisTool = MutableStateFlow(DiagnosticsRknBlockDiagnosisToolUiModel())
    val rknBlockDiagnosisTool: StateFlow<DiagnosticsRknBlockDiagnosisToolUiModel> =
        _rknBlockDiagnosisTool.asStateFlow()

    private val _compressionProbeTool = MutableStateFlow(DiagnosticsCompressionProbeToolUiModel())
    val compressionProbeTool: StateFlow<DiagnosticsCompressionProbeToolUiModel> =
        _compressionProbeTool.asStateFlow()

    private val _tcp16FatHeaderTool = MutableStateFlow(DiagnosticsTcp16FatHeaderToolUiModel())
    val tcp16FatHeaderTool: StateFlow<DiagnosticsTcp16FatHeaderToolUiModel> =
        _tcp16FatHeaderTool.asStateFlow()

    private val _allowlistSniTool = MutableStateFlow(DiagnosticsAllowlistSniToolUiModel())
    val allowlistSniTool: StateFlow<DiagnosticsAllowlistSniToolUiModel> =
        _allowlistSniTool.asStateFlow()

    private val byohCompatibilityController =
        DiagnosticsByohCompatibilityController(
            scope = scope,
            assetLoader = assetLoader,
        )
    val byohCompatibilityTool: StateFlow<DiagnosticsByohCompatibilityToolUiModel> =
        byohCompatibilityController.tool

    private var latestDnsStubIps: Set<String> = emptySet()
    private var latestTcp16DetectedResults: List<Tcp16ProbeResult> = emptyList()

    init {
        scope.launch {
            appSettingsRepository.settings.collect { settings ->
                val current = _rknBlockDiagnosisTool.value
                _rknBlockDiagnosisTool.value =
                    current.copy(
                        fetchSelfInfoEnabled = settings.rknDiagnosticsFetchSelfInfoEnabled,
                        selfInfoPrivacyOverridden = settings.detectionCheckPrivacyModeEnabled,
                    )
                _compressionProbeTool.value =
                    _compressionProbeTool.value.copy(
                        includeZstd = settings.compressionProbeIncludeZstd,
                    )
            }
        }
    }

    fun runDnsIntegrityCheck() {
        if (_dnsIntegrityTool.value.state == DiagnosticsDnsIntegrityState.Running) {
            return
        }
        _dnsIntegrityTool.value =
            DiagnosticsDnsIntegrityToolUiModel(
                state = DiagnosticsDnsIntegrityState.Running,
                summary = "Checking UDP/53 answers against DoH controls...",
            )
        scope.launch {
            runCatching {
                val domains = loadDomains(DnsIntegrityPreviewDomainLimit)
                check(domains.isNotEmpty()) { "No bundled DPI domains are available." }
                dnsIntegrityChecker.check(domains)
            }.onSuccess { result ->
                latestDnsStubIps = result.stubIps
                _dnsIntegrityTool.value = result.toUiModel()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _dnsIntegrityTool.value =
                    DiagnosticsDnsIntegrityToolUiModel(
                        state = DiagnosticsDnsIntegrityState.Failed,
                        summary = "DNS integrity check failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    fun runDnsAvailabilitySurvey() {
        if (_dnsAvailabilityTool.value.state == DiagnosticsDnsAvailabilityState.Running) {
            return
        }
        _dnsAvailabilityTool.value =
            DiagnosticsDnsAvailabilityToolUiModel(
                state = DiagnosticsDnsAvailabilityState.Running,
                summary = "Surveying public DNS resolvers...",
            )
        scope.launch {
            runCatching {
                dnsAvailabilitySurvey.run()
            }.onSuccess { results ->
                _dnsAvailabilityTool.value = results.toDnsAvailabilityUiModel()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _dnsAvailabilityTool.value =
                    DiagnosticsDnsAvailabilityToolUiModel(
                        state = DiagnosticsDnsAvailabilityState.Failed,
                        summary = "DNS availability survey failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    fun runDomainReachabilityScan() {
        if (_domainReachabilityTool.value.state == DiagnosticsDomainReachabilityState.Running) {
            return
        }
        val stubIps = latestDnsStubIps
        _domainReachabilityTool.value =
            DiagnosticsDomainReachabilityToolUiModel(
                state = DiagnosticsDomainReachabilityState.Running,
                summary = "Probing reachability with ${stubIps.size} DNS stub IPs...",
            )
        scope.launch {
            runCatching {
                val settings = appSettingsRepository.snapshot()
                val domains = loadDomains(limit = null)
                check(domains.isNotEmpty()) { "No bundled DPI domains are available." }
                domainReachabilityScanner.scan(
                    domains = domains,
                    stubIps = stubIps,
                    randomHostname = settings.detectionDiagnosticRandomHostnamesEnabled,
                )
            }.onSuccess { results ->
                _domainReachabilityTool.value = results.toUiModel(stubIps.size)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _domainReachabilityTool.value =
                    DiagnosticsDomainReachabilityToolUiModel(
                        state = DiagnosticsDomainReachabilityState.Failed,
                        summary = "Domain reachability scan failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    fun runCompressionProbe() {
        if (_compressionProbeTool.value.state == DiagnosticsCompressionProbeState.Running) {
            return
        }
        val current = _compressionProbeTool.value
        _compressionProbeTool.value =
            DiagnosticsCompressionProbeToolUiModel(
                state = DiagnosticsCompressionProbeState.Running,
                targetUrl = current.targetUrl,
                summary = "Checking HTTP compression support...",
                includeZstd = current.includeZstd,
            )
        scope.launch {
            runCatching {
                val settings = appSettingsRepository.snapshot()
                val targetUrl = loadCompressionProbeTarget()
                val results =
                    httpCompressionProber.probeAll(
                        url = targetUrl,
                        includeZstd = settings.compressionProbeIncludeZstd,
                    )
                CompressionProbeRunResult(
                    targetUrl = targetUrl,
                    includeZstd = settings.compressionProbeIncludeZstd,
                    results = results,
                )
            }.onSuccess { result ->
                _compressionProbeTool.value = result.toUiModel()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val latest = _compressionProbeTool.value
                _compressionProbeTool.value =
                    DiagnosticsCompressionProbeToolUiModel(
                        state = DiagnosticsCompressionProbeState.Failed,
                        targetUrl = latest.targetUrl,
                        summary = "HTTP compression probe failed.",
                        includeZstd = latest.includeZstd,
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    fun runTcp16FatHeaderProbe() {
        if (_tcp16FatHeaderTool.value.state == DiagnosticsTcp16FatHeaderState.Running) {
            return
        }
        _tcp16FatHeaderTool.value =
            DiagnosticsTcp16FatHeaderToolUiModel(
                state = DiagnosticsTcp16FatHeaderState.Running,
                summary = "Running TCP16 fat-header probe across bundled targets...",
            )
        scope.launch {
            runCatching {
                val settings = appSettingsRepository.snapshot()
                val targets = loadTcp16Targets()
                check(targets.isNotEmpty()) { "No bundled TCP16 targets are available." }
                tcp16FatHeaderProbe.run(
                    targets = targets,
                    randomHostname = settings.detectionDiagnosticRandomHostnamesEnabled,
                )
            }.onSuccess { results ->
                latestTcp16DetectedResults = results.filter { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
                _tcp16FatHeaderTool.value = results.toTcp16UiModel()
                _allowlistSniTool.value =
                    _allowlistSniTool.value.copy(
                        enabled = latestTcp16DetectedResults.isNotEmpty(),
                        summary =
                            if (latestTcp16DetectedResults.isEmpty()) {
                                "Run after TCP16 finds flagged ASNs."
                            } else {
                                "Ready for ${latestTcp16DetectedResults.size} TCP16-flagged targets."
                            },
                    )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tcp16FatHeaderTool.value =
                    DiagnosticsTcp16FatHeaderToolUiModel(
                        state = DiagnosticsTcp16FatHeaderState.Failed,
                        summary = "TCP16 fat-header probe failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    fun runAllowlistSniFinder() {
        if (_allowlistSniTool.value.state == DiagnosticsAllowlistSniState.Running) {
            return
        }
        val detectedResults = latestTcp16DetectedResults
        if (detectedResults.isEmpty()) {
            _allowlistSniTool.value =
                DiagnosticsAllowlistSniToolUiModel(
                    summary = "Run TCP16 first; no flagged ASNs are available yet.",
                    enabled = false,
                )
            return
        }
        _allowlistSniTool.value =
            DiagnosticsAllowlistSniToolUiModel(
                state = DiagnosticsAllowlistSniState.Running,
                summary = "Testing SNI compatibility for TCP16-flagged ASNs...",
                enabled = true,
            )
        scope.launch {
            runCatching {
                val sniList = loadWhitelistSni()
                check(sniList.isNotEmpty()) { "No bundled SNI compatibility entries are available." }
                AllowlistSniFinder(
                    probe = tcp16FatHeaderProbe,
                    sniList = sniList,
                ).find(detectedResults)
            }.onSuccess { results ->
                _allowlistSniTool.value = results.values.toList().toAllowlistSniUiModel()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _allowlistSniTool.value =
                    DiagnosticsAllowlistSniToolUiModel(
                        state = DiagnosticsAllowlistSniState.Failed,
                        summary = "SNI compatibility finder failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                        enabled = true,
                    )
            }
        }
    }

    fun runRknBlockDiagnosis() {
        if (_rknBlockDiagnosisTool.value.state == DiagnosticsRknBlockDiagnosisState.Running) {
            return
        }
        val current = _rknBlockDiagnosisTool.value
        _rknBlockDiagnosisTool.value =
            DiagnosticsRknBlockDiagnosisToolUiModel(
                state = DiagnosticsRknBlockDiagnosisState.Running,
                summary = "Running RKN control and blacklist probes...",
                fetchSelfInfoEnabled = current.fetchSelfInfoEnabled,
                selfInfoPrivacyOverridden = current.selfInfoPrivacyOverridden,
            )
        scope.launch {
            runCatching {
                val settings = appSettingsRepository.snapshot()
                val selfInfo =
                    selfInfoFetcher.fetch(
                        enabled = settings.rknDiagnosticsFetchSelfInfoEnabled,
                        privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled,
                    )
                val controlTargets = loadRknWhitelistControl()
                val testTargets = loadRknBlacklistTest()
                check(controlTargets.isNotEmpty()) { "No bundled RKN control targets are available." }
                check(testTargets.isNotEmpty()) { "No bundled RKN test targets are available." }
                val controlResults = rknLayeredProbePipeline.iterCheckUrls(controlTargets.toProbeTargets()).toList()
                val testResults = rknLayeredProbePipeline.iterCheckUrls(testTargets.toProbeTargets()).toList()
                RknDiagnosisRunResult(
                    controlResults = controlResults,
                    testResults = testResults,
                    selfInfo = selfInfo,
                    fetchSelfInfoEnabled = settings.rknDiagnosticsFetchSelfInfoEnabled,
                    selfInfoPrivacyOverridden = settings.detectionCheckPrivacyModeEnabled,
                )
            }.onSuccess { result ->
                _rknBlockDiagnosisTool.value = buildRknBlockDiagnosisUiModel(result)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val latest = _rknBlockDiagnosisTool.value
                _rknBlockDiagnosisTool.value =
                    DiagnosticsRknBlockDiagnosisToolUiModel(
                        state = DiagnosticsRknBlockDiagnosisState.Failed,
                        summary = "RKN block diagnosis failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                        fetchSelfInfoEnabled = latest.fetchSelfInfoEnabled,
                        selfInfoPrivacyOverridden = latest.selfInfoPrivacyOverridden,
                    )
            }
        }
    }

    fun setRknSelfInfoEnabled(enabled: Boolean) {
        scope.launch {
            appSettingsRepository.update {
                rknDiagnosticsFetchSelfInfoEnabled = enabled
            }
        }
    }

    fun setCompressionProbeZstdEnabled(enabled: Boolean) {
        scope.launch {
            appSettingsRepository.update {
                compressionProbeIncludeZstd = enabled
            }
        }
    }

    fun setByohDstIp(value: String) = byohCompatibilityController.setDstIp(value)

    fun setByohUrlPath(value: String) = byohCompatibilityController.setUrlPath(value)

    fun setByohSyntheticFixtureEnabled(enabled: Boolean) =
        byohCompatibilityController.setSyntheticFixtureEnabled(enabled)

    fun runByohCompatibilityCheck() = byohCompatibilityController.run()

    private suspend fun loadDomains(limit: Int?): List<String> =
        withContext(Dispatchers.IO) {
            val domains = assetLoader.loadDomains()
            if (limit == null) {
                domains
            } else {
                domains.take(limit)
            }
        }

    private suspend fun loadRknWhitelistControl(): List<RknTarget> =
        withContext(Dispatchers.IO) {
            assetLoader.loadRknWhitelistControl()
        }

    private suspend fun loadRknBlacklistTest(): List<RknTarget> =
        withContext(Dispatchers.IO) {
            assetLoader.loadRknBlacklistTest()
        }

    private suspend fun loadCompressionProbeTarget(): String =
        withContext(Dispatchers.IO) {
            val domain =
                assetLoader
                    .loadDomains()
                    .firstOrNull()
                    ?: error("No bundled DPI domains are available.")
            "https://$domain/"
        }

    private suspend fun loadTcp16Targets(): List<Tcp16Target> =
        withContext(Dispatchers.IO) {
            assetLoader.loadTcp16Targets()
        }

    private suspend fun loadWhitelistSni(): List<String> =
        withContext(Dispatchers.IO) {
            assetLoader.loadWhitelistSni()
        }
}

internal data class RknDiagnosisRunResult(
    val controlResults: List<RknCheckResult>,
    val testResults: List<RknCheckResult>,
    val selfInfo: SelfInfoResult?,
    val fetchSelfInfoEnabled: Boolean,
    val selfInfoPrivacyOverridden: Boolean,
)

private data class CompressionProbeRunResult(
    val targetUrl: String,
    val includeZstd: Boolean,
    val results: Map<CompressionCodec, CompressionProbeResult>,
)

private fun List<DnsServerResult>.toDnsAvailabilityUiModel(): DiagnosticsDnsAvailabilityToolUiModel {
    val available = count { result -> result.availableDomains > 0 }
    return DiagnosticsDnsAvailabilityToolUiModel(
        state = DiagnosticsDnsAvailabilityState.Complete,
        summary = "$available of $size public DNS resolvers responded.",
        metrics =
            listOf(
                DiagnosticsMetricUiModel("servers", size.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("available", available.toString(), countTone(size - available)),
            ).toPersistentList(),
        rows =
            map { result ->
                DiagnosticsDnsAvailabilityServerUiModel(
                    name = result.name,
                    type = result.type.name.lowercase(Locale.US),
                    availability = "${result.availableDomains}/${result.totalDomains}",
                    latency = result.avgLatencyMs?.let { "$it ms" } ?: "timeout",
                    tone = if (result.availableDomains > 0) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
                )
            }.toPersistentList(),
    )
}

internal fun List<DomainReachabilityResult>.toUiModel(stubIpCount: Int): DiagnosticsDomainReachabilityToolUiModel {
    val blocked = count { result -> result.verdict != DomainVerdict.OK }
    val checked = size
    return DiagnosticsDomainReachabilityToolUiModel(
        state = DiagnosticsDomainReachabilityState.Complete,
        summary =
            if (blocked == 0) {
                "No reachability blocks detected across $checked bundled domains."
            } else {
                "$blocked of $checked domains showed reachability warnings."
            },
        metrics =
            (
                listOf(
                    DiagnosticsMetricUiModel("checked", checked.toString(), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("flagged", blocked.toString(), countTone(blocked)),
                    DiagnosticsMetricUiModel("stub IPs", stubIpCount.toString(), DiagnosticsTone.Neutral),
                ) + mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsMetrics()
            ).toPersistentList(),
        rows =
            map { result ->
                DiagnosticsDomainReachabilityDomainUiModel(
                    domain = result.domain,
                    verdict = result.verdict.displayLabel(),
                    resolvedIps = result.resolvedIps.joinToString().ifBlank { "unresolved" },
                    tls13 = result.tls13.displayLabel(),
                    tls12 = result.tls12.displayLabel(),
                    http = result.http.displayLabel(),
                    tone = result.verdict.tone(),
                )
            }.toPersistentList(),
    )
}

private fun CompressionProbeRunResult.toUiModel(): DiagnosticsCompressionProbeToolUiModel {
    val okCount = results.values.count { result -> result.verdict == CompressionProbeVerdict.OK }
    return DiagnosticsCompressionProbeToolUiModel(
        state = DiagnosticsCompressionProbeState.Complete,
        targetUrl = targetUrl,
        summary = "$okCount of ${results.size} compression codecs are usable for $targetUrl.",
        includeZstd = includeZstd,
        metrics =
            listOf(
                DiagnosticsMetricUiModel("target", targetUrl, DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("usable", okCount.toString(), countTone(results.size - okCount)),
                DiagnosticsMetricUiModel("zstd", if (includeZstd) "enabled" else "off", DiagnosticsTone.Neutral),
            ).toPersistentList(),
        rows =
            CompressionCodec.entries
                .map { codec ->
                    val result = results.getValue(codec)
                    DiagnosticsCompressionCodecUiModel(
                        codec = codec.name.lowercase(Locale.US),
                        verdict = result.verdict.displayLabel(),
                        compressedBytes = result.compressedBytes?.toString() ?: "n/a",
                        decompressedBytes = result.decompressedBytes?.toString() ?: "n/a",
                        tone = result.verdict.tone(),
                    )
                }.toPersistentList(),
    )
}

internal fun List<Tcp16ProbeResult>.toTcp16UiModel(): DiagnosticsTcp16FatHeaderToolUiModel {
    val detected = count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
    val invalid = count { result -> result.verdict == Tcp16Verdict.INVALID_RECONNECTED }
    val unavailable = count { result -> result.verdict == Tcp16Verdict.DEAD || result.verdict == Tcp16Verdict.ERROR }
    return DiagnosticsTcp16FatHeaderToolUiModel(
        state = DiagnosticsTcp16FatHeaderState.Complete,
        summary =
            if (detected == 0 && invalid == 0) {
                "No TCP16 fat-header closure pattern detected across $size targets."
            } else {
                "$detected targets showed TCP16 closure patterns; $invalid reconnect validations failed."
            },
        metrics =
            (
                listOf(
                    DiagnosticsMetricUiModel("targets", size.toString(), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("detected", detected.toString(), countTone(detected)),
                    DiagnosticsMetricUiModel("unavailable", unavailable.toString(), DiagnosticsTone.Neutral),
                    DiagnosticsMetricUiModel("single-socket invalid", invalid.toString(), countTone(invalid)),
                ) + mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsMetrics()
            ).toPersistentList(),
        rows =
            byAsn()
                .values
                .sortedWith(compareByDescending<Tcp16AsnSummary> { summary -> summary.detected }.thenBy { it.asn })
                .map { summary -> summary.toUiModel() }
                .toPersistentList(),
        detectedResults =
            filter { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
                .map { result ->
                    DiagnosticsTcp16DetectedTargetUiModel(
                        targetId = result.targetId,
                        asn = result.asn,
                        provider = result.provider,
                        ip = result.ip,
                    )
                }.toPersistentList(),
    )
}

private fun Tcp16AsnSummary.toUiModel(): DiagnosticsTcp16AsnUiModel {
    val warningCount = detected + errors + invalidReconnected
    return DiagnosticsTcp16AsnUiModel(
        asn = asn,
        providers = providers.joinToString(),
        checked = checked.toString(),
        detected = detected.toString(),
        dead = dead.toString(),
        errors = (errors + invalidReconnected).toString(),
        tone = countTone(warningCount),
    )
}

internal fun buildRknBlockDiagnosisUiModel(result: RknDiagnosisRunResult): DiagnosticsRknBlockDiagnosisToolUiModel {
    val controlResults = result.controlResults
    val testResults = result.testResults
    val aggregate = RknAggregateVerdictEngine.aggregate(controlResults, testResults)
    val blockTypes = RknAggregateVerdictEngine.blockTypes(testResults)
    val flagged = testResults.count { result -> result.verdict != RknVerdict.OK }
    val rows =
        controlResults.map { result -> result.toUiModel("control") } +
            testResults.map { result -> result.toUiModel("test") }
    return DiagnosticsRknBlockDiagnosisToolUiModel(
        state = DiagnosticsRknBlockDiagnosisState.Complete,
        headline = aggregate.headline,
        confidenceNote = aggregate.confidenceNote,
        summary = "Checked ${controlResults.size} control targets and ${testResults.size} blacklist targets.",
        fetchSelfInfoEnabled = result.fetchSelfInfoEnabled,
        selfInfoPrivacyOverridden = result.selfInfoPrivacyOverridden,
        selfInfo = result.selfInfo?.toUiModel(),
        metrics =
            (
                listOf(
                    DiagnosticsMetricUiModel("control", controlResults.size.toString(), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("test", testResults.size.toString(), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel("flagged", flagged.toString(), countTone(flagged)),
                ) + (controlResults + testResults).mapNotNull { item -> item.tlsClientState }.toDiagnosticsTlsMetrics()
            ).toPersistentList(),
        blockTypes =
            blockTypes
                .map { (verdict, count) ->
                    val label = RknVerdictLabel.format(verdict, RknConfidence.HIGH)
                    DiagnosticsRknBlockTypeUiModel(
                        label = "${label.symbol} ${label.text}",
                        count = count,
                        tone = label.color.toDiagnosticsTone(),
                    )
                }.toPersistentList(),
        rows = rows.toPersistentList(),
    )
}

private fun SelfInfoResult.toUiModel(): DiagnosticsRknSelfInfoUiModel =
    DiagnosticsRknSelfInfoUiModel(
        maskedIp = SelfInfoFetcher.maskIpForDisplay(ip),
        provider = listOfNotNull(asn, org).joinToString(" ").ifBlank { "Unknown ISP" },
        asn = asn,
        org = org,
        location = listOfNotNull(city, region, country).joinToString(", ").ifBlank { null },
        source = source,
    )

private fun DomainVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun CompressionProbeVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun AttemptResult.displayLabel(): String =
    buildList {
        add(status.name.lowercase(Locale.US).replace('_', ' '))
        statusCode?.let { code -> add(code.toString()) }
        error?.let { error -> add(error.label.lowercase(Locale.US).replace('_', ' ')) }
    }.joinToString(" · ")

private fun List<RknTarget>.toProbeTargets(): List<RknProbeTarget> =
    map { target -> RknProbeTarget(name = target.name, url = target.url) }

private fun RknCheckResult.toUiModel(group: String): DiagnosticsRknTargetUiModel {
    val label = RknVerdictLabel.format(verdict, confidence)
    return DiagnosticsRknTargetUiModel(
        group = group,
        name = name,
        verdict = "${label.symbol} ${label.text}",
        notes = notes.ifEmpty { listOfNotNull(dnsError, tcpError, tlsError, httpError) }.joinToString("; "),
        tone = label.color.toDiagnosticsTone(),
    )
}

private fun RknVerdictColorToken.toDiagnosticsTone(): DiagnosticsTone =
    when (this) {
        RknVerdictColorToken.POSITIVE -> DiagnosticsTone.Positive
        RknVerdictColorToken.WARNING -> DiagnosticsTone.Warning
        RknVerdictColorToken.ERROR -> DiagnosticsTone.Negative
        RknVerdictColorToken.MUTED -> DiagnosticsTone.Neutral
    }

private fun List<DiagnosticsTlsClientState>.toDiagnosticsTlsMetrics(): List<DiagnosticsMetricUiModel> {
    val state = firstOrNull() ?: return emptyList()
    val mode =
        when {
            state.nativeOwnedTlsAvailable -> "Native owned TLS"
            state.fallbackActive -> "Android template fallback"
            else -> "Default TLS"
        }
    val tone =
        when {
            state.fallbackActive -> DiagnosticsTone.Warning
            state.nativeOwnedTlsAvailable -> DiagnosticsTone.Positive
            else -> DiagnosticsTone.Neutral
        }
    return buildList {
        state.profileId?.let { profile ->
            add(DiagnosticsMetricUiModel("TLS profile", profile, DiagnosticsTone.Info))
        }
        add(DiagnosticsMetricUiModel("TLS mode", mode, tone))
    }
}

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }

private fun DomainVerdict.tone(): DiagnosticsTone =
    when (this) {
        DomainVerdict.OK -> DiagnosticsTone.Positive

        DomainVerdict.DNS_FAIL,
        DomainVerdict.FAKE_IP,
        -> DiagnosticsTone.Neutral

        DomainVerdict.BLOCKED,
        DomainVerdict.TLS_VERSION_BLOCK,
        DomainVerdict.ISP_PAGE,
        DomainVerdict.TCP16_BAND,
        DomainVerdict.UNREACHABLE,
        -> DiagnosticsTone.Warning
    }

private fun CompressionProbeVerdict.tone(): DiagnosticsTone =
    when (this) {
        CompressionProbeVerdict.OK -> DiagnosticsTone.Positive

        CompressionProbeVerdict.NOT_SUPPORTED -> DiagnosticsTone.Neutral

        CompressionProbeVerdict.EOF_BEFORE_MIN,
        CompressionProbeVerdict.TIMEOUT,
        CompressionProbeVerdict.CONN_ERR,
        CompressionProbeVerdict.INTERNAL_ERR,
        -> DiagnosticsTone.Warning
    }
