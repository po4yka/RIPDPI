package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteConfig
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteEvent
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.EchTlsHandshake
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.SuiteVerdict
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.dpi.TelegramTestVerdict
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizer
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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
                summary = "Running ${current.selectedKinds.size} selected DPI probes...",
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
                    _tool.value =
                        if (error is CancellationException) {
                            _tool.value.copy(
                                state = DiagnosticsDpiSuiteState.Cancelled,
                                summary = "DPI suite cancelled; partial results retained.",
                            )
                        } else {
                            _tool.value.copy(
                                state = DiagnosticsDpiSuiteState.Failed,
                                summary = "DPI suite failed.",
                                errorMessage = error.message ?: error.javaClass.simpleName,
                            )
                        }
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
                rows.upsert(event.kind.startedRow())
                _tool.value = _tool.value.copy(rows = rows.toPersistentList())
            }

            is DpiSuiteEvent.ProbeProgress -> {
                rows.upsert(event.progressRow())
                _tool.value = _tool.value.copy(rows = rows.toPersistentList())
            }

            is DpiSuiteEvent.ProbeCompleted -> {
                rows.upsert(event.result.toDpiSuiteProbeRowUiModel(stringResolver))
                _tool.value = _tool.value.copy(rows = rows.toPersistentList())
            }

            is DpiSuiteEvent.SuiteCompleted -> {
                val verdict =
                    event.aggregate.verdict.name
                        .lowercase(Locale.US)
                val probeCount =
                    event.aggregate.results.size
                        .toString()
                _tool.value =
                    _tool.value.copy(
                        state = DiagnosticsDpiSuiteState.Complete,
                        summary = event.aggregate.verdict.summary(),
                        aggregateVerdict = event.aggregate.verdict,
                        metrics =
                            listOf(
                                DiagnosticsMetricUiModel(
                                    "verdict",
                                    verdict,
                                ),
                                DiagnosticsMetricUiModel(
                                    "probes",
                                    probeCount,
                                ),
                            ).toPersistentList(),
                        rows = rows.toPersistentList(),
                    )
            }
        }
    }
}

internal fun DpiSuiteProbeResult.toDpiSuiteProbeRowUiModel(
    stringResolver: StringResolver,
): DiagnosticsDpiSuiteProbeRowUiModel =
    DiagnosticsDpiSuiteProbeRowUiModel(
        kind = kind,
        label = kind.displayLabel(),
        status = statusLabel(),
        detail = detailLabel(),
        tone = tone(),
        detailRows = detailRows(stringResolver),
    )

private fun DpiSuiteProbeResult.detailRows(
    stringResolver: StringResolver,
): ImmutableList<DiagnosticsDpiSuiteProbeDetailUiModel> =
    when (this) {
        is DpiSuiteProbeResult.DomainReachability -> {
            results.mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsDetailRows(stringResolver)
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            results.mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsDetailRows(stringResolver)
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            results.map { result -> result.toQuicDetailRow() }.toPersistentList()
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            results.map { result -> result.toEchDetailRow() }.toPersistentList()
        }

        else -> {
            persistentListOf()
        }
    }

private fun List<DiagnosticsTlsClientState>.toDiagnosticsTlsDetailRows(
    stringResolver: StringResolver,
): ImmutableList<DiagnosticsDpiSuiteProbeDetailUiModel> {
    val state = firstOrNull() ?: return persistentListOf()
    val mode =
        stringResolver.getString(
            when {
                state.nativeOwnedTlsAvailable -> R.string.diagnostics_tls_mode_native_owned
                state.fallbackActive -> R.string.diagnostics_tls_mode_android_fallback
                else -> R.string.diagnostics_tls_mode_default
            },
        )
    val tone =
        when {
            state.fallbackActive -> DiagnosticsTone.Warning
            state.nativeOwnedTlsAvailable -> DiagnosticsTone.Positive
            else -> DiagnosticsTone.Neutral
        }
    return buildList {
        state.profileId?.let { profile ->
            add(
                DiagnosticsDpiSuiteProbeDetailUiModel(
                    label = stringResolver.getString(R.string.diagnostics_tls_metric_profile),
                    detail = profile,
                    tone = DiagnosticsTone.Info,
                ),
            )
        }
        add(
            DiagnosticsDpiSuiteProbeDetailUiModel(
                label = stringResolver.getString(R.string.diagnostics_tls_metric_mode),
                detail = mode,
                tone = tone,
            ),
        )
    }.toPersistentList()
}

private fun QuicProbeResult.toQuicDetailRow(): DiagnosticsDpiSuiteProbeDetailUiModel =
    DiagnosticsDpiSuiteProbeDetailUiModel(
        label = target,
        detail =
            listOf(
                "Chrome ${chromeOk.okBlockedLabel()}",
                "Firefox ${firefoxOk.okBlockedLabel()}",
                "Generic ${genericOk.okBlockedLabel()}",
                "VN ${vnOk.okBlockedLabel()}",
                latencyLabel(),
            ).joinToString(" | "),
        tone =
            countTone(
                if (verdict == QuicProbeVerdict.QUIC_OK) {
                    0
                } else {
                    1
                },
            ),
    )

private fun Boolean.okBlockedLabel(): String = if (this) "ok" else "blocked"

private fun QuicProbeResult.latencyLabel(): String =
    serverInitialLatencyMs
        ?.let { latency -> "$latency ms" }
        ?: "no latency"

private fun EchProbeResult.toEchDetailRow(): DiagnosticsDpiSuiteProbeDetailUiModel =
    DiagnosticsDpiSuiteProbeDetailUiModel(
        label = target,
        detail =
            listOf(
                verdictLabel(),
                "HTTPS RR ${httpsRrFetched.yesNoLabel()}",
                "negotiated ${negotiatedEch.yesNoLabel()}",
                vanillaTlsLabel(),
                latencyLabel(),
            ).joinToString(" | "),
        tone = echTone(),
    )

private fun EchProbeResult.verdictLabel(): String =
    when (verdict) {
        EchProbeVerdict.ECH_OK -> "ECH ok"
        EchProbeVerdict.ECH_REJECTED -> "ECH rejected"
        EchProbeVerdict.ECH_NO_CONFIG -> "no ECH config"
        EchProbeVerdict.ECH_UNKNOWN_KEY -> "unknown ECH key"
        EchProbeVerdict.ECH_NETWORK_BLOCK -> "network blocked"
    }

private fun Boolean.yesNoLabel(): String = if (this) "yes" else "no"

private fun EchProbeResult.vanillaTlsLabel(): String =
    when (vanillaTlsOk) {
        true -> "vanilla ok"
        false -> "vanilla blocked"
        null -> "vanilla unknown"
    }

private fun EchProbeResult.latencyLabel(): String =
    tlsLatencyMs
        ?.let { latency -> "$latency ms" }
        ?: "no latency"

private fun EchProbeResult.echTone(): DiagnosticsTone =
    when (verdict) {
        EchProbeVerdict.ECH_OK -> DiagnosticsTone.Positive

        EchProbeVerdict.ECH_NO_CONFIG -> DiagnosticsTone.Neutral

        EchProbeVerdict.ECH_REJECTED,
        EchProbeVerdict.ECH_UNKNOWN_KEY,
        EchProbeVerdict.ECH_NETWORK_BLOCK,
        -> DiagnosticsTone.Warning
    }

private fun DpiSuiteProbeResult.statusLabel(): String =
    when (this) {
        is DpiSuiteProbeResult.DnsIntegrity -> {
            val flagged = result.domains.count { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK }
            if (flagged == 0) "ok" else "flagged"
        }

        is DpiSuiteProbeResult.DnsAvailability -> {
            val available = results.count { server -> server.availableDomains > 0 }
            if (available == results.size) "ok" else "partial"
        }

        is DpiSuiteProbeResult.DomainReachability -> {
            val flagged = results.count { domain -> domain.verdict != DomainVerdict.OK }
            if (flagged == 0) "ok" else "flagged"
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            val detected = results.count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
            if (detected == 0) "ok" else "detected"
        }

        is DpiSuiteProbeResult.AllowlistSni -> {
            if (results.isEmpty()) "none" else "compatible"
        }

        is DpiSuiteProbeResult.Telegram -> {
            result.verdict.name.lowercase(Locale.US)
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            val flagged = results.count { result -> result.verdict != QuicProbeVerdict.QUIC_OK }
            if (flagged == 0) "ok" else "flagged"
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            val flagged =
                results.count { result ->
                    result.verdict != EchProbeVerdict.ECH_OK && result.verdict != EchProbeVerdict.ECH_NO_CONFIG
                }
            if (flagged == 0) "ok" else "flagged"
        }

        is DpiSuiteProbeResult.Skipped -> {
            "skipped"
        }

        is DpiSuiteProbeResult.Failed -> {
            "failed"
        }
    }

private fun DpiSuiteProbeResult.detailLabel(): String =
    when (this) {
        is DpiSuiteProbeResult.DnsIntegrity -> {
            val flagged = result.domains.count { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK }
            "$flagged/${result.domains.size} domains flagged; ${result.stubIps.size} stub IPs"
        }

        is DpiSuiteProbeResult.DnsAvailability -> {
            val available = results.count { server -> server.availableDomains > 0 }
            "$available/${results.size} resolvers available"
        }

        is DpiSuiteProbeResult.DomainReachability -> {
            val ok = results.count { domain -> domain.verdict == DomainVerdict.OK }
            "$ok/${results.size} domains reachable"
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            val detected = results.count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
            "$detected/${results.size} targets matched TCP16 closure pattern"
        }

        is DpiSuiteProbeResult.AllowlistSni -> {
            "${results.size} ASN groups checked"
        }

        is DpiSuiteProbeResult.Telegram -> {
            val reachable = result.dcResults.count { dc -> dc.reachable }
            "download ${result.download.status.name.lowercase(Locale.US)}, upload " +
                "${result.upload.status.name.lowercase(Locale.US)}, DC $reachable/${result.dcResults.size}"
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            val ok = results.count { result -> result.verdict == QuicProbeVerdict.QUIC_OK }
            "$ok/${results.size} targets passed QUIC/H3 fingerprint checks"
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            val ok = results.count { result -> result.verdict == EchProbeVerdict.ECH_OK }
            "$ok/${results.size} targets accepted ECH"
        }

        is DpiSuiteProbeResult.Skipped -> {
            reason
        }

        is DpiSuiteProbeResult.Failed -> {
            error
        }
    }

private fun DpiSuiteProbeResult.tone(): DiagnosticsTone =
    when (this) {
        is DpiSuiteProbeResult.Failed -> {
            DiagnosticsTone.Negative
        }

        is DpiSuiteProbeResult.Skipped -> {
            DiagnosticsTone.Neutral
        }

        is DpiSuiteProbeResult.DnsIntegrity -> {
            countTone(
                result.domains.count { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK },
            )
        }

        is DpiSuiteProbeResult.DnsAvailability -> {
            countTone(
                results.count { server -> server.availableDomains == 0 },
            )
        }

        is DpiSuiteProbeResult.DomainReachability -> {
            countTone(
                results.count { domain -> domain.verdict != DomainVerdict.OK },
            )
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            countTone(
                results.count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB },
            )
        }

        is DpiSuiteProbeResult.AllowlistSni -> {
            DiagnosticsTone.Neutral
        }

        is DpiSuiteProbeResult.Telegram -> {
            when (result.verdict) {
                TelegramTestVerdict.OK -> {
                    DiagnosticsTone.Positive
                }

                TelegramTestVerdict.PARTIAL,
                TelegramTestVerdict.SLOW,
                -> {
                    DiagnosticsTone.Warning
                }

                TelegramTestVerdict.BLOCKED,
                TelegramTestVerdict.ERROR,
                -> {
                    DiagnosticsTone.Negative
                }
            }
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            val flagged = results.count { result -> result.verdict != QuicProbeVerdict.QUIC_OK }
            countTone(flagged)
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            val flagged =
                results.count { result ->
                    result.verdict != EchProbeVerdict.ECH_OK && result.verdict != EchProbeVerdict.ECH_NO_CONFIG
                }
            countTone(flagged)
        }
    }

internal fun DpiProbeKind.displayLabel(): String =
    when (this) {
        DpiProbeKind.DNS_INTEGRITY -> "DNS integrity"
        DpiProbeKind.DNS_AVAILABILITY -> "DNS availability"
        DpiProbeKind.DOMAIN_REACHABILITY -> "Domain reachability"
        DpiProbeKind.TCP16 -> "TCP16 fat header"
        DpiProbeKind.WHITELIST_SNI -> "SNI compatibility"
        DpiProbeKind.TELEGRAM -> "Telegram"
        DpiProbeKind.QUIC_H3 -> "QUIC/H3 fingerprint"
        DpiProbeKind.ECH_READINESS -> "ECH readiness"
    }

private fun SuiteVerdict.summary(): String =
    when (this) {
        SuiteVerdict.CLEAN -> "No selected DPI probe reported warnings."
        SuiteVerdict.DPI_DETECTED -> "One or more selected probes reported DPI-like interference."
        SuiteVerdict.DNS_INTERFERENCE -> "DNS integrity probes reported resolver-path interference."
        SuiteVerdict.THROTTLING -> "Telegram transfer probes reported throughput degradation."
        SuiteVerdict.MIXED -> "Multiple interference categories were reported."
        SuiteVerdict.INCONCLUSIVE -> "Too many selected probes failed to classify the path."
    }

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }
