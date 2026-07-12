package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.Ipv4WhitelistedSubnetDiscoverer
import com.poyka.ripdpi.diagnostics.dpich.WhitelistedSubnetResult
import com.poyka.ripdpi.diagnostics.dpich.toWhitelistedSubnetCsv
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class DiagnosticsIpv4WhitelistController(
    private val scope: CoroutineScope,
    private val discoverer: Ipv4WhitelistedSubnetDiscoverer,
    private val shareCsv: (String) -> Unit,
    private val stringResolver: StringResolver,
) {
    private val _tool = MutableStateFlow(initialDiagnosticsIpv4WhitelistUiModel(stringResolver))
    val tool: StateFlow<DiagnosticsIpv4WhitelistToolUiModel> = _tool.asStateFlow()

    fun cacheSubnets() {
        if (_tool.value.state == DiagnosticsIpv4WhitelistState.Running) {
            return
        }
        _tool.value =
            DiagnosticsIpv4WhitelistToolUiModel(
                state = DiagnosticsIpv4WhitelistState.Running,
                summary = stringResolver.getString(R.string.diagnostics_ipv4_whitelist_fetching),
            )
        scope.launch {
            val cachedProviders = mutableSetOf<String>()
            var cachedCidrs = 0
            runCatching {
                discoverer.cacheSubnets().collect { progress ->
                    cachedProviders += progress.provider
                    cachedCidrs = progress.totalCached
                    _tool.value =
                        DiagnosticsIpv4WhitelistToolUiModel(
                            state = DiagnosticsIpv4WhitelistState.Running,
                            summary =
                                stringResolver.getString(
                                    R.string.diagnostics_ipv4_whitelist_cached_provider,
                                    progress.cachedCidrs.size,
                                    progress.provider,
                                ),
                            metrics =
                                listOf(
                                    DiagnosticsMetricUiModel(
                                        stringResolver.getString(R.string.diagnostics_ipv4_whitelist_metric_providers),
                                        cachedProviders.size.toString(),
                                        DiagnosticsTone.Info,
                                    ),
                                    DiagnosticsMetricUiModel(
                                        stringResolver.getString(R.string.diagnostics_ipv4_whitelist_metric_cached_24),
                                        cachedCidrs.toString(),
                                        DiagnosticsTone.Info,
                                    ),
                                ).toPersistentList(),
                        )
                }
            }.onSuccess {
                _tool.value =
                    DiagnosticsIpv4WhitelistToolUiModel(
                        state = DiagnosticsIpv4WhitelistState.Complete,
                        summary =
                            stringResolver.getString(
                                R.string.diagnostics_ipv4_whitelist_cached_complete,
                                cachedCidrs,
                            ),
                        metrics =
                            listOf(
                                DiagnosticsMetricUiModel(
                                    stringResolver.getString(R.string.diagnostics_ipv4_whitelist_metric_providers),
                                    cachedProviders.size.toString(),
                                    DiagnosticsTone.Positive,
                                ),
                                DiagnosticsMetricUiModel(
                                    stringResolver.getString(R.string.diagnostics_ipv4_whitelist_metric_cached_24),
                                    cachedCidrs.toString(),
                                    DiagnosticsTone.Positive,
                                ),
                            ).toPersistentList(),
                    )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail(stringResolver.getString(R.string.diagnostics_ipv4_whitelist_cache_failed), error)
            }
        }
    }

    fun checkSubnets() {
        if (_tool.value.state == DiagnosticsIpv4WhitelistState.Running) {
            return
        }
        _tool.value =
            _tool.value.copy(
                state = DiagnosticsIpv4WhitelistState.Running,
                summary = stringResolver.getString(R.string.diagnostics_ipv4_whitelist_sampling),
                errorMessage = null,
            )
        scope.launch {
            val results = mutableListOf<WhitelistedSubnetResult>()
            runCatching {
                discoverer.checkCachedSubnets().collect { progress ->
                    results += progress.result
                    _tool.value =
                        results.toIpv4WhitelistUiModel(
                            stringResolver = stringResolver,
                            state = DiagnosticsIpv4WhitelistState.Running,
                            summary =
                                stringResolver.getString(
                                    R.string.diagnostics_ipv4_whitelist_checked_progress,
                                    progress.checkedCount,
                                    progress.totalCount,
                                ),
                        )
                }
            }.onSuccess {
                _tool.value = results.toIpv4WhitelistUiModel(stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail(stringResolver.getString(R.string.diagnostics_ipv4_whitelist_check_failed), error)
            }
        }
    }

    fun saveCsv() {
        val csv = _tool.value.csv
        if (csv.isNotBlank()) {
            shareCsv(csv)
        }
    }

    private fun fail(
        summary: String,
        error: Throwable,
    ) {
        _tool.value =
            _tool.value.copy(
                state = DiagnosticsIpv4WhitelistState.Failed,
                summary = summary,
                errorMessage = error.message ?: error.javaClass.simpleName,
            )
    }
}

internal fun List<WhitelistedSubnetResult>.toIpv4WhitelistUiModel(
    stringResolver: StringResolver,
    state: DiagnosticsIpv4WhitelistState = DiagnosticsIpv4WhitelistState.Complete,
    summary: String =
        stringResolver.getString(R.string.diagnostics_ipv4_whitelist_checked_complete, size),
): DiagnosticsIpv4WhitelistToolUiModel {
    val whitelisted = count(WhitelistedSubnetResult::whitelisted)
    return DiagnosticsIpv4WhitelistToolUiModel(
        state = state,
        summary = summary,
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_ipv4_whitelist_metric_checked),
                    size.toString(),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_ipv4_whitelist_metric_whitelisted),
                    whitelisted.toString(),
                    countTone(whitelisted),
                ),
            ).toPersistentList(),
        rows = map { it.toUiModel(stringResolver) }.toPersistentList(),
        csv = toWhitelistedSubnetCsv(),
    )
}

private fun WhitelistedSubnetResult.toUiModel(stringResolver: StringResolver): DiagnosticsIpv4WhitelistSubnetUiModel =
    DiagnosticsIpv4WhitelistSubnetUiModel(
        provider = provider,
        cidr = cidr,
        alive = "$aliveCount/$aliveSampled",
        verdict =
            stringResolver.getString(
                if (whitelisted) {
                    R.string.diagnostics_ipv4_whitelist_verdict_whitelisted
                } else {
                    R.string.diagnostics_ipv4_whitelist_verdict_regular
                },
            ),
        tone = if (whitelisted) DiagnosticsTone.Positive else DiagnosticsTone.Neutral,
    )

private fun countTone(count: Int): DiagnosticsTone =
    if (count > 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Neutral
    }
