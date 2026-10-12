package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteConfig
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteEvent
import com.poyka.ripdpi.diagnostics.dpi.EchTlsHandshake
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizer
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DpiSuiteMinConcurrency = 1
private const val DpiSuiteMaxConcurrency = 250

internal class DiagnosticsDpiSuiteController(
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    dnsIntegrityChecker: DnsIntegrityChecker,
    dnsAvailabilitySurvey: DnsAvailabilitySurvey,
    domainReachabilityScanner: DomainReachabilityScanner,
    tcp16FatHeaderProbe: Tcp16FatHeaderProbe,
    assetLoader: DpiAssetLoader,
    diagnosticsFiles: DiagnosticsFiles,
    tlsKeylogRunFinalizer: TlsKeylogRunFinalizer,
    echTlsHandshake: EchTlsHandshake,
    private val stringResolver: StringResolver,
) {
    private val _tool = MutableStateFlow(initialDiagnosticsDpiSuiteUiModel(stringResolver))
    val tool: StateFlow<DiagnosticsDpiSuiteToolUiModel> = _tool.asStateFlow()

    private var suiteJob: Job? = null
    private val runnerFactory =
        DiagnosticsDpiSuiteRunnerFactory(
            dnsIntegrityChecker,
            dnsAvailabilitySurvey,
            domainReachabilityScanner,
            tcp16FatHeaderProbe,
            assetLoader,
            echTlsHandshake,
        )
    private val tlsRunFinalizer = DiagnosticsDpiSuiteTlsRunFinalizer(diagnosticsFiles, tlsKeylogRunFinalizer)

    init {
        scope.launch {
            appSettingsRepository.settings.collect { settings ->
                _tool.value =
                    _tool.value.copy(
                        concurrency =
                            settings.dpiSuiteConcurrency.coerceIn(
                                DpiSuiteMinConcurrency,
                                DpiSuiteMaxConcurrency,
                            ),
                    )
            }
        }
    }

    fun setProbeEnabled(
        kind: DpiProbeKind,
        enabled: Boolean,
    ) {
        if (_tool.value.state == DiagnosticsDpiSuiteState.Running) {
            return
        }
        val selected =
            if (enabled) {
                _tool.value.selectedKinds + kind
            } else {
                _tool.value.selectedKinds - kind
            }
        _tool.value = _tool.value.copy(selectedKinds = selected.toPersistentSet())
    }

    fun setCustomDomains(value: String) {
        if (_tool.value.state != DiagnosticsDpiSuiteState.Running) {
            _tool.value = _tool.value.copy(customDomainsInput = value)
        }
    }

    fun adjustConcurrency(delta: Int) {
        val next = (_tool.value.concurrency + delta).coerceIn(DpiSuiteMinConcurrency, DpiSuiteMaxConcurrency)
        scope.launch {
            appSettingsRepository.update {
                dpiSuiteConcurrency = next
            }
        }
        _tool.value = _tool.value.copy(concurrency = next)
    }

    fun run() {
        val current = _tool.value
        if (current.state == DiagnosticsDpiSuiteState.Running || current.selectedKinds.isEmpty()) {
            return
        }
        suiteJob?.cancel()
        _tool.value =
            current.copy(
                state = DiagnosticsDpiSuiteState.Running,
                summary =
                    stringResolver.getString(
                        R.string.diagnostics_dpi_suite_running_format,
                        current.selectedKinds.size,
                    ),
                aggregateVerdict = null,
                metrics = emptyList<DiagnosticsMetricUiModel>().toPersistentList(),
                rows = emptyList<DiagnosticsDpiSuiteProbeRowUiModel>().toPersistentList(),
                errorMessage = null,
            )
        suiteJob =
            scope.launch {
                val rows = mutableListOf<DiagnosticsDpiSuiteProbeRowUiModel>()
                runCatching {
                    val settings = appSettingsRepository.snapshot()
                    val config =
                        DpiSuiteConfig(
                            selection = current.selectedKinds.toSet(),
                            customDomains = parseCustomDomains(current.customDomainsInput),
                            concurrency = current.concurrency,
                            randomHostname = settings.detectionDiagnosticRandomHostnamesEnabled,
                        )
                    try {
                        runnerFactory.build().run(config).collect { event ->
                            handleEvent(event, rows)
                        }
                    } finally {
                        tlsRunFinalizer.finish(settings)
                    }
                }.onFailure { error ->
                    _tool.value = _tool.value.withFailure(error, stringResolver)
                }
            }
    }

    fun cancel() {
        suiteJob?.cancel()
    }

    private fun handleEvent(
        event: DpiSuiteEvent,
        rows: MutableList<DiagnosticsDpiSuiteProbeRowUiModel>,
    ) {
        when (event) {
            is DpiSuiteEvent.ProbeStarted -> {
                rows.upsert(event.kind.startedRow(stringResolver))
                _tool.value = _tool.value.copy(rows = rows.toPersistentList())
            }

            is DpiSuiteEvent.ProbeProgress -> {
                rows.upsert(event.progressRow(stringResolver))
                _tool.value = _tool.value.copy(rows = rows.toPersistentList())
            }

            is DpiSuiteEvent.ProbeCompleted -> {
                rows.upsert(event.result.toDpiSuiteProbeRowUiModel(stringResolver))
                _tool.value = _tool.value.copy(rows = rows.toPersistentList())
            }

            is DpiSuiteEvent.SuiteCompleted -> {
                val verdict =
                    event.aggregate.verdict.label(stringResolver)
                val probeCount =
                    event.aggregate.results.size
                        .toString()
                _tool.value =
                    _tool.value.copy(
                        state = DiagnosticsDpiSuiteState.Complete,
                        summary = event.aggregate.verdict.summary(stringResolver),
                        aggregateVerdict = event.aggregate.verdict,
                        metrics =
                            listOf(
                                DiagnosticsMetricUiModel(
                                    stringResolver.getString(R.string.diagnostics_cidr_whitelist_metric_verdict),
                                    verdict,
                                ),
                                DiagnosticsMetricUiModel(
                                    stringResolver.getString(R.string.diagnostics_metric_probes),
                                    probeCount,
                                ),
                            ).toPersistentList(),
                        rows = rows.toPersistentList(),
                    )
            }
        }
    }
}
