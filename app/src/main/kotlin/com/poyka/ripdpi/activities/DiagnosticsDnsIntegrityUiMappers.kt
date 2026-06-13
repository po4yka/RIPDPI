package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DoqProbeResult
import com.poyka.ripdpi.diagnostics.dpi.DoqVerdict
import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapResult
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList
import java.util.Locale

internal fun DnsIntegrityResult.toUiModel(stringResolver: StringResolver): DiagnosticsDnsIntegrityToolUiModel {
    val flagged = domains.count { result -> result.verdict != DnsIntegrityVerdict.DNS_OK }
    val doqFlagged = doqResults.count { result -> result.verdict != DoqVerdict.DOQ_OK }
    val bootstrapFlagged = dohBootstrapResults.count { result -> result.verdict != BootstrapVerdict.OK }
    val checked = domains.size
    return DiagnosticsDnsIntegrityToolUiModel(
        state = DiagnosticsDnsIntegrityState.Complete,
        summary =
            if (flagged == 0 && doqFlagged == 0 && bootstrapFlagged == 0) {
                stringResolver.getString(R.string.diagnostics_dns_integrity_complete_clean, checked)
            } else {
                stringResolver.getString(
                    R.string.diagnostics_dns_integrity_complete_flagged,
                    flagged,
                    checked,
                    doqFlagged,
                    bootstrapFlagged,
                )
            },
        metrics =
            buildDnsIntegrityMetrics(
                stringResolver,
                checked,
                flagged,
                doqFlagged,
                bootstrapFlagged,
            ),
        rows =
            domains
                .map { result ->
                    DiagnosticsDnsIntegrityDomainUiModel(
                        domain = result.domain,
                        verdict = result.verdict.displayLabel(),
                        udpAnswer =
                            result.udpRecords.joinToString().ifBlank {
                                stringResolver.getString(R.string.diagnostics_value_timeout)
                            },
                        dohAnswer =
                            result.dohIps.joinToString().ifBlank {
                                stringResolver.getString(R.string.diagnostics_value_unavailable)
                            },
                        tone = result.verdict.tone(),
                    )
                }.toPersistentList(),
        doqRows =
            doqResults
                .map { it.toUiModel(stringResolver) }
                .toPersistentList(),
        dohBootstrapRows =
            dohBootstrapResults
                .map(DohBootstrapResult::toUiModel)
                .toPersistentList(),
    )
}

private fun DnsIntegrityResult.buildDnsIntegrityMetrics(
    stringResolver: StringResolver,
    checked: Int,
    flagged: Int,
    doqFlagged: Int,
    bootstrapFlagged: Int,
) = buildList {
    add(
        DiagnosticsMetricUiModel(
            stringResolver.getString(R.string.diagnostics_dns_integrity_metric_checked),
            checked.toString(),
            DiagnosticsTone.Info,
        ),
    )
    add(
        DiagnosticsMetricUiModel(
            stringResolver.getString(R.string.diagnostics_dns_integrity_metric_flagged),
            flagged.toString(),
            countTone(flagged),
        ),
    )
    add(
        DiagnosticsMetricUiModel(
            stringResolver.getString(R.string.diagnostics_dns_integrity_metric_stub_ips),
            stubIps.size.toString(),
            DiagnosticsTone.Neutral,
        ),
    )
    add(
        DiagnosticsMetricUiModel(
            stringResolver.getString(R.string.diagnostics_dns_integrity_metric_doh_blocked),
            dohBlocked.toString(),
            countTone(dohBlocked),
        ),
    )
    if (doqResults.isNotEmpty()) {
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_dns_integrity_metric_doq_checks),
                doqResults.size.toString(),
                DiagnosticsTone.Info,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_dns_integrity_metric_doq_flagged),
                doqFlagged.toString(),
                countTone(doqFlagged),
            ),
        )
    }
    if (dohBootstrapResults.isNotEmpty()) {
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_dns_integrity_metric_doh_bootstrap),
                dohBootstrapResults.size.toString(),
                DiagnosticsTone.Info,
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_dns_integrity_metric_bootstrap_flagged),
                bootstrapFlagged.toString(),
                countTone(bootstrapFlagged),
            ),
        )
    }
}.toPersistentList()

private fun DohBootstrapResult.toUiModel(): DiagnosticsDohBootstrapUiModel =
    DiagnosticsDohBootstrapUiModel(
        provider = providerName,
        hostname = dohHostname,
        verdict = verdict.displayLabel(),
        detail = bootstrapDetail(),
        tone = verdict.tone(),
    )

private fun DoqProbeResult.toUiModel(stringResolver: StringResolver): DiagnosticsDnsIntegrityDoqUiModel =
    DiagnosticsDnsIntegrityDoqUiModel(
        provider = provider,
        domain = domain,
        verdict = verdict.displayLabel(),
        endpoint = endpoint,
        resolvedIps =
            resolvedIps.joinToString().ifBlank {
                stringResolver.getString(R.string.diagnostics_value_unavailable)
            },
        detail =
            errorDetail
                ?: latencyMs?.let { "$it ms" }
                ?: stringResolver.getString(R.string.diagnostics_value_complete),
        tone = verdict.tone(),
    )

private fun DnsIntegrityVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun DoqVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun BootstrapVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun DohBootstrapResult.bootstrapDetail(): String =
    buildList {
        bootstrapIp?.let(::add)
        discoveredAsn?.let { asn -> add("AS$asn") }
        discoveredOrg?.let(::add)
    }.joinToString(" · ").ifBlank { expectedFilter }

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

private fun DoqVerdict.tone(): DiagnosticsTone =
    when (this) {
        DoqVerdict.DOQ_OK -> DiagnosticsTone.Positive

        DoqVerdict.DOQ_TIMEOUT -> DiagnosticsTone.Neutral

        DoqVerdict.DOQ_BLOCKED_QUIC,
        DoqVerdict.DOQ_DPI_REJECT,
        DoqVerdict.DOQ_INTEGRITY_DIVERGENT,
        -> DiagnosticsTone.Warning
    }

private fun BootstrapVerdict.tone(): DiagnosticsTone =
    when (this) {
        BootstrapVerdict.OK -> DiagnosticsTone.Positive
        BootstrapVerdict.SPOOFED -> DiagnosticsTone.Warning
        BootstrapVerdict.RESOLVE_FAILED -> DiagnosticsTone.Neutral
    }

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }
