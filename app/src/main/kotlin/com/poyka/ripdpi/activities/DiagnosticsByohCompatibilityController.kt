package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpich.ByohConfig
import com.poyka.ripdpi.diagnostics.dpich.ByohCsvExporter
import com.poyka.ripdpi.diagnostics.dpich.ByohDomainCompatibilityChecker
import com.poyka.ripdpi.diagnostics.dpich.ByohIncompatibleReason
import com.poyka.ripdpi.diagnostics.dpich.ByohProbeResult
import com.poyka.ripdpi.diagnostics.dpich.ByohProgress
import com.poyka.ripdpi.diagnostics.dpich.ByohStats
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DiagnosticsByohCompatibilityController(
    private val scope: CoroutineScope,
    private val assetLoader: DpiAssetLoader,
    private val stringResolver: StringResolver,
    private val checker: ByohDomainCompatibilityChecker = ByohDomainCompatibilityChecker(),
) {
    private val _tool = MutableStateFlow(initialDiagnosticsByohUiModel(stringResolver))
    val tool: StateFlow<DiagnosticsByohCompatibilityToolUiModel> = _tool.asStateFlow()

    fun setDstIp(value: String) {
        _tool.value = _tool.value.copy(dstIp = value, errorMessage = null)
    }

    fun setUrlPath(value: String) {
        _tool.value = _tool.value.copy(urlPath = value.ifBlank { "/" }, errorMessage = null)
    }

    fun setSyntheticFixtureEnabled(enabled: Boolean) {
        _tool.value = _tool.value.copy(useSyntheticFixture = enabled, errorMessage = null)
    }

    fun run() {
        if (_tool.value.state == DiagnosticsByohCompatibilityState.Running) {
            return
        }
        val initial = _tool.value
        if (initial.dstIp.isBlank()) {
            _tool.value =
                initial.copy(
                    state = DiagnosticsByohCompatibilityState.Failed,
                    errorMessage = stringResolver.getString(R.string.diagnostics_byoh_dst_ip_required),
                )
            return
        }
        _tool.value =
            initial.copy(
                state = DiagnosticsByohCompatibilityState.Running,
                summary = stringResolver.getString(R.string.diagnostics_byoh_preparing),
                metrics = emptyList<DiagnosticsMetricUiModel>().toPersistentList(),
                rows = emptyList<DiagnosticsByohDomainUiModel>().toPersistentList(),
                csvExport = "",
                errorMessage = null,
            )
        scope.launch {
            val rows = mutableListOf<DiagnosticsByohDomainUiModel>()
            val results = mutableListOf<ByohProbeResult>()
            runCatching {
                val domains = loadDomains(initial.useSyntheticFixture)
                check(domains.isNotEmpty()) { "No domain fixture entries are available." }
                checker
                    .run(
                        ByohConfig(
                            dstIp = initial.dstIp.trim(),
                            urlPath = initial.urlPath.ifBlank { "/" },
                            domainList = domains,
                        ),
                    ).collect { event ->
                        handleProgress(event, rows, results)
                    }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tool.value =
                    _tool.value.copy(
                        state = DiagnosticsByohCompatibilityState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_byoh_check_failed),
                        rows = rows.toPersistentList(),
                        csvExport = ByohCsvExporter.toCsv(results),
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    private fun handleProgress(
        event: ByohProgress,
        rows: MutableList<DiagnosticsByohDomainUiModel>,
        results: MutableList<ByohProbeResult>,
    ) {
        when (event) {
            is ByohProgress.Probing -> {
                _tool.value =
                    _tool.value.copy(
                        summary =
                            stringResolver.getString(
                                R.string.diagnostics_byoh_checking_domain,
                                event.domain,
                                event.idx,
                                event.total,
                            ),
                    )
            }

            is ByohProgress.Compatible -> {
                val result = ByohProbeResult(event.domain, compatible = true, bytesReceived = event.bytesReceived)
                results += result
                rows += result.toUiModel()
                updateRunningRows(rows, results)
            }

            is ByohProgress.Incompatible -> {
                val result =
                    ByohProbeResult(
                        domain = event.domain,
                        compatible = false,
                        bytesReceived = event.bytesReceived,
                        reason = event.reason,
                    )
                results += result
                rows += result.toUiModel()
                updateRunningRows(rows, results)
            }

            is ByohProgress.Completed -> {
                _tool.value = event.stats.toCompleteUiModel(rows, results)
            }
        }
    }

    private fun updateRunningRows(
        rows: List<DiagnosticsByohDomainUiModel>,
        results: List<ByohProbeResult>,
    ) {
        _tool.value =
            _tool.value.copy(
                metrics = results.toMetrics(),
                rows = rows.toPersistentList(),
                csvExport = ByohCsvExporter.toCsv(results),
            )
    }

    private suspend fun loadDomains(useSyntheticFixture: Boolean): List<String> =
        withContext(Dispatchers.IO) {
            if (useSyntheticFixture) {
                assetLoader.loadByohSyntheticDomains()
            } else {
                assetLoader.loadDomains()
            }
        }

    private fun ByohStats.toCompleteUiModel(
        rows: List<DiagnosticsByohDomainUiModel>,
        results: List<ByohProbeResult>,
    ): DiagnosticsByohCompatibilityToolUiModel =
        _tool.value.copy(
            state = DiagnosticsByohCompatibilityState.Complete,
            summary =
                stringResolver.getString(
                    R.string.diagnostics_byoh_complete,
                    compatible,
                    total,
                ),
            metrics = results.toMetrics(),
            rows = rows.toPersistentList(),
            csvExport = ByohCsvExporter.toCsv(results),
            errorMessage = null,
        )

    private fun List<ByohProbeResult>.toMetrics(): ImmutableList<DiagnosticsMetricUiModel> =
        listOf(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_byoh_metric_checked),
                size.toString(),
                DiagnosticsTone.Info,
            ),
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_byoh_metric_compatible),
                count { result -> result.compatible }.toString(),
                DiagnosticsTone.Info,
            ),
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_byoh_metric_bytes),
                sumOf { result -> result.bytesReceived }.toString(),
                DiagnosticsTone.Neutral,
            ),
        ).toPersistentList()

    private fun ByohProbeResult.toUiModel(): DiagnosticsByohDomainUiModel =
        DiagnosticsByohDomainUiModel(
            domain = domain,
            verdict =
                if (compatible) {
                    stringResolver.getString(R.string.diagnostics_byoh_verdict_compatible)
                } else {
                    (reason ?: ByohIncompatibleReason.InvalidResponse).label()
                },
            bytesReceived = bytesReceived.toString(),
            tone = if (compatible) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
        )

    private fun ByohIncompatibleReason.label(): String = name.lowercase().replace('_', ' ')
}
