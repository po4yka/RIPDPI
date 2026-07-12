package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniFinder
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Target
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.RknTarget
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val stringResolver: StringResolver,
) {
    private val _dnsIntegrityTool = MutableStateFlow(initialDiagnosticsDnsIntegrityUiModel(stringResolver))
    val dnsIntegrityTool: StateFlow<DiagnosticsDnsIntegrityToolUiModel> = _dnsIntegrityTool.asStateFlow()

    private val _dnsAvailabilityTool = MutableStateFlow(initialDiagnosticsDnsAvailabilityUiModel(stringResolver))
    val dnsAvailabilityTool: StateFlow<DiagnosticsDnsAvailabilityToolUiModel> =
        _dnsAvailabilityTool.asStateFlow()

    private val _domainReachabilityTool = MutableStateFlow(initialDiagnosticsDomainReachabilityUiModel(stringResolver))
    val domainReachabilityTool: StateFlow<DiagnosticsDomainReachabilityToolUiModel> =
        _domainReachabilityTool.asStateFlow()

    private val _rknBlockDiagnosisTool = MutableStateFlow(initialDiagnosticsRknBlockDiagnosisUiModel(stringResolver))
    val rknBlockDiagnosisTool: StateFlow<DiagnosticsRknBlockDiagnosisToolUiModel> =
        _rknBlockDiagnosisTool.asStateFlow()

    private val _compressionProbeTool = MutableStateFlow(initialDiagnosticsCompressionProbeUiModel(stringResolver))
    val compressionProbeTool: StateFlow<DiagnosticsCompressionProbeToolUiModel> =
        _compressionProbeTool.asStateFlow()

    private val _tcp16FatHeaderTool = MutableStateFlow(initialDiagnosticsTcp16FatHeaderUiModel(stringResolver))
    val tcp16FatHeaderTool: StateFlow<DiagnosticsTcp16FatHeaderToolUiModel> =
        _tcp16FatHeaderTool.asStateFlow()

    private val _allowlistSniTool = MutableStateFlow(initialDiagnosticsAllowlistSniUiModel(stringResolver))
    val allowlistSniTool: StateFlow<DiagnosticsAllowlistSniToolUiModel> =
        _allowlistSniTool.asStateFlow()

    private val byohCompatibilityController =
        DiagnosticsByohCompatibilityController(
            scope = scope,
            assetLoader = assetLoader,
            stringResolver = stringResolver,
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
                summary = stringResolver.getString(R.string.diagnostics_dns_integrity_running),
            )
        scope.launch {
            runCatching {
                val domains = loadDomains(DnsIntegrityPreviewDomainLimit)
                check(domains.isNotEmpty()) { "No bundled DPI domains are available." }
                dnsIntegrityChecker.check(domains)
            }.onSuccess { result ->
                latestDnsStubIps = result.stubIps
                _dnsIntegrityTool.value = result.toUiModel(stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _dnsIntegrityTool.value =
                    DiagnosticsDnsIntegrityToolUiModel(
                        state = DiagnosticsDnsIntegrityState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_dns_integrity_failed),
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
                summary = stringResolver.getString(R.string.diagnostics_dns_availability_running),
            )
        scope.launch {
            runCatching {
                dnsAvailabilitySurvey.run()
            }.onSuccess { results ->
                _dnsAvailabilityTool.value = results.toDnsAvailabilityUiModel(stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _dnsAvailabilityTool.value =
                    DiagnosticsDnsAvailabilityToolUiModel(
                        state = DiagnosticsDnsAvailabilityState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_dns_availability_failed),
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
                summary =
                    stringResolver.getString(
                        R.string.diagnostics_domain_reachability_running,
                        stubIps.size,
                    ),
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
                _domainReachabilityTool.value = results.toUiModel(stubIps.size, stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _domainReachabilityTool.value =
                    DiagnosticsDomainReachabilityToolUiModel(
                        state = DiagnosticsDomainReachabilityState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_domain_reachability_failed),
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
                summary = stringResolver.getString(R.string.diagnostics_compression_running),
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
                _compressionProbeTool.value = result.toUiModel(stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val latest = _compressionProbeTool.value
                _compressionProbeTool.value =
                    DiagnosticsCompressionProbeToolUiModel(
                        state = DiagnosticsCompressionProbeState.Failed,
                        targetUrl = latest.targetUrl,
                        summary = stringResolver.getString(R.string.diagnostics_compression_failed),
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
                summary = stringResolver.getString(R.string.diagnostics_tcp16_running),
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
                _tcp16FatHeaderTool.value = results.toTcp16UiModel(stringResolver)
                _allowlistSniTool.value =
                    _allowlistSniTool.value.copy(
                        enabled = latestTcp16DetectedResults.isNotEmpty(),
                        summary =
                            if (latestTcp16DetectedResults.isEmpty()) {
                                stringResolver.getString(R.string.diagnostics_allowlist_sni_run_after_tcp16)
                            } else {
                                stringResolver.getString(
                                    R.string.diagnostics_allowlist_sni_ready,
                                    latestTcp16DetectedResults.size,
                                )
                            },
                    )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tcp16FatHeaderTool.value =
                    DiagnosticsTcp16FatHeaderToolUiModel(
                        state = DiagnosticsTcp16FatHeaderState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_tcp16_failed),
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
                    summary = stringResolver.getString(R.string.diagnostics_allowlist_sni_no_flagged),
                    enabled = false,
                )
            return
        }
        _allowlistSniTool.value =
            DiagnosticsAllowlistSniToolUiModel(
                state = DiagnosticsAllowlistSniState.Running,
                summary = stringResolver.getString(R.string.diagnostics_allowlist_sni_running),
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
                        summary = stringResolver.getString(R.string.diagnostics_allowlist_sni_failed),
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
                headline = current.headline,
                confidenceNote = current.confidenceNote,
                summary = stringResolver.getString(R.string.diagnostics_rkn_running),
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
                _rknBlockDiagnosisTool.value = buildRknBlockDiagnosisUiModel(result, stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val latest = _rknBlockDiagnosisTool.value
                _rknBlockDiagnosisTool.value =
                    DiagnosticsRknBlockDiagnosisToolUiModel(
                        state = DiagnosticsRknBlockDiagnosisState.Failed,
                        headline = latest.headline,
                        confidenceNote = latest.confidenceNote,
                        summary = stringResolver.getString(R.string.diagnostics_rkn_failed),
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
