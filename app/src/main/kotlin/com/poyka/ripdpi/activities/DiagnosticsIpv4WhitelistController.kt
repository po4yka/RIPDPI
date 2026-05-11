package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpich.Ipv4WhitelistedSubnetDiscoverer
import com.poyka.ripdpi.diagnostics.dpich.WhitelistedSubnetResult
import com.poyka.ripdpi.diagnostics.dpich.toWhitelistedSubnetCsv
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
) {
    private val _tool = MutableStateFlow(DiagnosticsIpv4WhitelistToolUiModel())
    val tool: StateFlow<DiagnosticsIpv4WhitelistToolUiModel> = _tool.asStateFlow()

    fun cacheSubnets() {
        if (_tool.value.state == DiagnosticsIpv4WhitelistState.Running) {
            return
        }
        _tool.value =
            DiagnosticsIpv4WhitelistToolUiModel(
                state = DiagnosticsIpv4WhitelistState.Running,
                summary = "Fetching announced IPv4 prefixes...",
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
                            summary = "Cached ${progress.cachedCidrs.size} /24 ranges for ${progress.provider}.",
                            metrics =
                                listOf(
                                    DiagnosticsMetricUiModel(
                                        "providers",
                                        cachedProviders.size.toString(),
                                        DiagnosticsTone.Info,
                                    ),
                                    DiagnosticsMetricUiModel(
                                        "cached /24",
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
                        summary = "Cached $cachedCidrs candidate /24 ranges.",
                        metrics =
                            listOf(
                                DiagnosticsMetricUiModel(
                                    "providers",
                                    cachedProviders.size.toString(),
                                    DiagnosticsTone.Positive,
                                ),
                                DiagnosticsMetricUiModel(
                                    "cached /24",
                                    cachedCidrs.toString(),
                                    DiagnosticsTone.Positive,
                                ),
                            ).toPersistentList(),
                    )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail("Subnet cache failed.", error)
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
                summary = "Sampling cached subnets...",
                errorMessage = null,
            )
        scope.launch {
            val results = mutableListOf<WhitelistedSubnetResult>()
            runCatching {
                discoverer.checkCachedSubnets().collect { progress ->
                    results += progress.result
                    _tool.value =
                        results.toIpv4WhitelistUiModel(
                            state = DiagnosticsIpv4WhitelistState.Running,
                            summary = "Checked ${progress.checkedCount}/${progress.totalCount} cached subnets.",
                        )
                }
            }.onSuccess {
                _tool.value = results.toIpv4WhitelistUiModel()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail("Subnet check failed.", error)
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
    state: DiagnosticsIpv4WhitelistState = DiagnosticsIpv4WhitelistState.Complete,
    summary: String = "Checked $size cached subnets.",
): DiagnosticsIpv4WhitelistToolUiModel {
    val whitelisted = count(WhitelistedSubnetResult::whitelisted)
    return DiagnosticsIpv4WhitelistToolUiModel(
        state = state,
        summary = summary,
        metrics =
            listOf(
                DiagnosticsMetricUiModel("checked", size.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("whitelisted", whitelisted.toString(), countTone(whitelisted)),
            ).toPersistentList(),
        rows = map(WhitelistedSubnetResult::toUiModel).toPersistentList(),
        csv = toWhitelistedSubnetCsv(),
    )
}

private fun WhitelistedSubnetResult.toUiModel(): DiagnosticsIpv4WhitelistSubnetUiModel =
    DiagnosticsIpv4WhitelistSubnetUiModel(
        provider = provider,
        cidr = cidr,
        alive = "$aliveCount/$aliveSampled",
        verdict = if (whitelisted) "whitelisted" else "regular",
        tone = if (whitelisted) DiagnosticsTone.Positive else DiagnosticsTone.Neutral,
    )

private fun countTone(count: Int): DiagnosticsTone =
    if (count > 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Neutral
    }
