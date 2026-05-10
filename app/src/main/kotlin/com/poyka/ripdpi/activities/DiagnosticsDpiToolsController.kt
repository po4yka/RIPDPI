package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val DnsIntegrityPreviewDomainLimit = 5
private const val DomainReachabilityPreviewDomainLimit = 5

internal class DiagnosticsDpiToolsController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val dnsIntegrityChecker: DnsIntegrityChecker = DnsIntegrityChecker(),
    private val domainReachabilityScanner: DomainReachabilityScanner = DomainReachabilityScanner(),
) {
    private val _dnsIntegrityTool = MutableStateFlow(DiagnosticsDnsIntegrityToolUiModel())
    val dnsIntegrityTool: StateFlow<DiagnosticsDnsIntegrityToolUiModel> = _dnsIntegrityTool.asStateFlow()

    private val _domainReachabilityTool = MutableStateFlow(DiagnosticsDomainReachabilityToolUiModel())
    val domainReachabilityTool: StateFlow<DiagnosticsDomainReachabilityToolUiModel> =
        _domainReachabilityTool.asStateFlow()

    private var latestDnsStubIps: Set<String> = emptySet()

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
                val domains = loadDomains(DomainReachabilityPreviewDomainLimit)
                check(domains.isNotEmpty()) { "No bundled DPI domains are available." }
                domainReachabilityScanner.scan(domains, stubIps)
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

    private suspend fun loadDomains(limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            DpiAssetLoader(appContext)
                .loadDomains()
                .take(limit)
        }
}

private fun DnsIntegrityResult.toUiModel(): DiagnosticsDnsIntegrityToolUiModel {
    val flagged = domains.count { result -> result.verdict != DnsIntegrityVerdict.DNS_OK }
    val checked = domains.size
    return DiagnosticsDnsIntegrityToolUiModel(
        state = DiagnosticsDnsIntegrityState.Complete,
        summary =
            if (flagged == 0) {
                "No DNS substitution detected across $checked bundled domains."
            } else {
                "$flagged of $checked domains showed DNS integrity warnings."
            },
        metrics =
            listOf(
                DiagnosticsMetricUiModel("checked", checked.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("flagged", flagged.toString(), countTone(flagged)),
                DiagnosticsMetricUiModel("stub IPs", stubIps.size.toString(), DiagnosticsTone.Neutral),
                DiagnosticsMetricUiModel("DoH blocked", dohBlocked.toString(), countTone(dohBlocked)),
            ).toPersistentList(),
        rows =
            domains
                .map { result ->
                    DiagnosticsDnsIntegrityDomainUiModel(
                        domain = result.domain,
                        verdict = result.verdict.displayLabel(),
                        udpAnswer = result.udpRecords.joinToString().ifBlank { "timeout" },
                        dohAnswer = result.dohIps.joinToString().ifBlank { "unavailable" },
                        tone = result.verdict.tone(),
                    )
                }.toPersistentList(),
    )
}

private fun List<DomainReachabilityResult>.toUiModel(stubIpCount: Int): DiagnosticsDomainReachabilityToolUiModel {
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
            listOf(
                DiagnosticsMetricUiModel("checked", checked.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("flagged", blocked.toString(), countTone(blocked)),
                DiagnosticsMetricUiModel("stub IPs", stubIpCount.toString(), DiagnosticsTone.Neutral),
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

private fun DnsIntegrityVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun DomainVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun AttemptResult.displayLabel(): String =
    buildList {
        add(status.name.lowercase(Locale.US).replace('_', ' '))
        statusCode?.let { code -> add(code.toString()) }
        error?.let { error -> add(error.name.lowercase(Locale.US).replace('_', ' ')) }
    }.joinToString(" · ")

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }

private fun DnsIntegrityVerdict.tone(): DiagnosticsTone =
    when (this) {
        DnsIntegrityVerdict.DNS_OK -> DiagnosticsTone.Positive

        DnsIntegrityVerdict.DOH_BLOCKED,
        DnsIntegrityVerdict.DNS_SUBSTITUTION,
        DnsIntegrityVerdict.DNS_INTERCEPTION,
        DnsIntegrityVerdict.FAKE_NXDOMAIN,
        DnsIntegrityVerdict.FAKE_IP,
        -> DiagnosticsTone.Warning

        DnsIntegrityVerdict.UNKNOWN -> DiagnosticsTone.Neutral
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
