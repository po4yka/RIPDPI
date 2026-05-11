package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DoqProbeResult
import com.poyka.ripdpi.diagnostics.dpi.DoqVerdict
import kotlinx.collections.immutable.toPersistentList
import java.util.Locale

internal fun DnsIntegrityResult.toUiModel(): DiagnosticsDnsIntegrityToolUiModel {
    val flagged = domains.count { result -> result.verdict != DnsIntegrityVerdict.DNS_OK }
    val doqFlagged = doqResults.count { result -> result.verdict != DoqVerdict.DOQ_OK }
    val checked = domains.size
    return DiagnosticsDnsIntegrityToolUiModel(
        state = DiagnosticsDnsIntegrityState.Complete,
        summary =
            if (flagged == 0 && doqFlagged == 0) {
                "No DNS substitution detected across $checked bundled domains."
            } else {
                "$flagged of $checked domains and $doqFlagged DoQ checks showed DNS integrity warnings."
            },
        metrics =
            buildList {
                add(DiagnosticsMetricUiModel("checked", checked.toString(), DiagnosticsTone.Info))
                add(DiagnosticsMetricUiModel("flagged", flagged.toString(), countTone(flagged)))
                add(DiagnosticsMetricUiModel("stub IPs", stubIps.size.toString(), DiagnosticsTone.Neutral))
                add(DiagnosticsMetricUiModel("DoH blocked", dohBlocked.toString(), countTone(dohBlocked)))
                if (doqResults.isNotEmpty()) {
                    add(DiagnosticsMetricUiModel("DoQ checks", doqResults.size.toString(), DiagnosticsTone.Info))
                    add(DiagnosticsMetricUiModel("DoQ flagged", doqFlagged.toString(), countTone(doqFlagged)))
                }
            }.toPersistentList(),
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
        doqRows =
            doqResults
                .map(DoqProbeResult::toUiModel)
                .toPersistentList(),
    )
}

private fun DoqProbeResult.toUiModel(): DiagnosticsDnsIntegrityDoqUiModel =
    DiagnosticsDnsIntegrityDoqUiModel(
        provider = provider,
        domain = domain,
        verdict = verdict.displayLabel(),
        endpoint = endpoint,
        resolvedIps = resolvedIps.joinToString().ifBlank { "unavailable" },
        detail = errorDetail ?: latencyMs?.let { "$it ms" } ?: "complete",
        tone = verdict.tone(),
    )

private fun DnsIntegrityVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun DoqVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

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

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }
