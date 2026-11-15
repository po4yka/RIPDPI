package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityDomainResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DoqProbeResult
import com.poyka.ripdpi.diagnostics.dpi.DoqVerdict
import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapResult
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList

internal fun DnsIntegrityResult.toUiModel(stringResolver: StringResolver): DiagnosticsDnsIntegrityToolUiModel {
    val flagged = domains.count { result -> result.verdict != DnsIntegrityVerdict.DNS_OK }
    val bootstrapFlagged = dohBootstrapResults.count { result -> result.verdict != BootstrapVerdict.OK }
    val checked = domains.size
    return DiagnosticsDnsIntegrityToolUiModel(
        state = DiagnosticsDnsIntegrityState.Complete,
        summary =
            if (flagged == 0 && !doqIncomplete && bootstrapFlagged == 0 && checked > 0) {
                stringResolver.getQuantityString(R.plurals.diagnostics_dns_complete_agree, checked, checked)
            } else {
                stringResolver.getString(
                    R.string.diagnostics_dns_integrity_complete_flagged,
                    flagged,
                    checked,
                    doqFlagged + doqMissing,
                    bootstrapFlagged,
                )
            },
        metrics =
            buildDnsIntegrityMetrics(
                stringResolver,
                checked,
                flagged,
                bootstrapFlagged,
            ),
        rows =
            domains
                .map { it.toDomainUiModel(stringResolver) }
                .toPersistentList(),
        doqRows =
            doqResults
                .map { it.toUiModel(stringResolver) }
                .toPersistentList(),
        dohBootstrapRows =
            dohBootstrapResults
                .map { it.toUiModel(stringResolver) }
                .toPersistentList(),
    )
}

private fun DnsIntegrityDomainResult.toDomainUiModel(strings: StringResolver): DiagnosticsDnsIntegrityDomainUiModel =
    DiagnosticsDnsIntegrityDomainUiModel(
        domain = domain,
        verdict = strings.getString(verdict.dnsVerdictLabel()),
        udpAnswer =
            udpRecords.joinToString().ifBlank {
                strings.getString(udpResponse?.let { dnsOutcomeLabel(it.outcome) } ?: R.string.diagnostics_dns_missing)
            },
        dohAnswer = dohIps.joinToString().ifBlank { strings.getString(R.string.diagnostics_dns_unknown) },
        tone = verdict.tone(),
        dnsResponses =
            listOf(
                dnsResponseGroup(strings, R.string.diagnostics_dns_source_udp, udpResponse).withResolver(udpResolver),
                dnsResponseGroup(
                    strings,
                    R.string.diagnostics_dns_source_doh_json,
                    dohJsonResponse,
                ).withResolver(dohJsonResolver),
                dnsResponseGroup(
                    strings,
                    R.string.diagnostics_dns_source_doh_wire,
                    dohWireResponse,
                ).withResolver(dohWireResolver),
            ).toPersistentList(),
    )

internal fun DnsIntegrityResult.doqCoverageLabel(strings: StringResolver): String =
    doqExpectedChecks?.let { expected -> "${doqResults.size}/$expected" }
        ?: strings.getString(R.string.diagnostics_probe_status_skipped)

private fun DiagnosticsContextGroupUiModel.withResolver(resolver: String?): DiagnosticsContextGroupUiModel =
    resolver?.let { copy(title = "$title · $it") } ?: this

private fun DnsIntegrityResult.buildDnsIntegrityMetrics(
    stringResolver: StringResolver,
    checked: Int,
    flagged: Int,
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
            stringResolver.getString(R.string.diagnostics_dns_encrypted_unavailable),
            dohUnavailable.toString(),
            countTone(dohUnavailable),
        ),
    )
    add(
        DiagnosticsMetricUiModel(
            stringResolver.getString(R.string.diagnostics_dns_integrity_metric_doq_checks),
            doqCoverageLabel(stringResolver),
            if (doqIncomplete) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
        ),
    )
    if (doqExpectedChecks != null) {
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

private fun DohBootstrapResult.toUiModel(stringResolver: StringResolver): DiagnosticsDohBootstrapUiModel =
    DiagnosticsDohBootstrapUiModel(
        provider = providerName,
        hostname = dohHostname,
        verdict = verdict.displayLabel(stringResolver),
        detail = bootstrapDetail(),
        tone = verdict.tone(),
    )

private fun DoqProbeResult.toUiModel(stringResolver: StringResolver): DiagnosticsDnsIntegrityDoqUiModel =
    DiagnosticsDnsIntegrityDoqUiModel(
        provider = provider,
        domain = domain,
        verdict = verdict.displayLabel(stringResolver),
        endpoint = endpoint,
        resolvedIps =
            resolvedIps.joinToString().ifBlank {
                stringResolver.getString(
                    response?.let { dnsOutcomeLabel(it.outcome) } ?: R.string.diagnostics_value_unavailable,
                )
            },
        detail =
            errorDetail
                ?: latencyMs?.let { stringResolver.getString(R.string.unit_milliseconds_format, it) }
                ?: stringResolver.getString(R.string.diagnostics_value_complete),
        tone = verdict.tone(),
    )

private fun DnsIntegrityVerdict.dnsVerdictLabel(): Int =
    when (this) {
        DnsIntegrityVerdict.DNS_OK -> R.string.diagnostics_dns_ok

        DnsIntegrityVerdict.DNS_NEGATIVE -> R.string.diagnostics_dns_negative

        DnsIntegrityVerdict.DNS_PARTIAL_AGREEMENT -> R.string.diagnostics_dns_partial_agreement

        DnsIntegrityVerdict.DNS_FAILURE_AGREEMENT -> R.string.diagnostics_dns_failure_agreement

        DnsIntegrityVerdict.UDP_UNAVAILABLE -> R.string.diagnostics_dns_udp_unavailable

        DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE,
        DnsIntegrityVerdict.DOH_BLOCKED,
        -> R.string.diagnostics_dns_encrypted_unavailable

        DnsIntegrityVerdict.DNS_DISAGREEMENT,
        DnsIntegrityVerdict.DNS_SUBSTITUTION,
        DnsIntegrityVerdict.DNS_INTERCEPTION,
        DnsIntegrityVerdict.FAKE_NXDOMAIN,
        DnsIntegrityVerdict.FAKE_IP,
        -> R.string.diagnostics_dns_disagreement

        DnsIntegrityVerdict.UNKNOWN -> R.string.diagnostics_dns_unknown
    }

internal fun DoqVerdict.displayLabel(strings: StringResolver): String =
    strings.getString(
        when (this) {
            DoqVerdict.DOQ_OK -> R.string.diagnostics_doq_ok
            DoqVerdict.DOQ_TIMEOUT -> R.string.diagnostics_doq_timeout
            DoqVerdict.DOQ_BLOCKED_QUIC -> R.string.diagnostics_doq_blocked_quic
            DoqVerdict.DOQ_DPI_REJECT -> R.string.diagnostics_doq_dpi_reject
            DoqVerdict.DOQ_INTEGRITY_DIVERGENT -> R.string.diagnostics_doq_integrity_divergent
            DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE -> R.string.diagnostics_dns_unknown
        },
    )

private fun BootstrapVerdict.displayLabel(strings: StringResolver): String =
    strings.getString(
        when (this) {
            BootstrapVerdict.OK -> R.string.diagnostics_bootstrap_ok
            BootstrapVerdict.SPOOFED -> R.string.diagnostics_bootstrap_spoofed
            BootstrapVerdict.RESOLVE_FAILED -> R.string.diagnostics_bootstrap_resolve_failed
        },
    )

private fun DohBootstrapResult.bootstrapDetail(): String =
    buildList {
        bootstrapIp?.let(::add)
        discoveredAsn?.let { asn -> add("AS$asn") }
        discoveredOrg?.let(::add)
    }.joinToString(" · ").ifBlank { expectedFilter }

private fun DnsIntegrityVerdict.tone(): DiagnosticsTone =
    when (this) {
        DnsIntegrityVerdict.DNS_OK -> DiagnosticsTone.Positive

        DnsIntegrityVerdict.DNS_NEGATIVE,
        DnsIntegrityVerdict.DNS_PARTIAL_AGREEMENT,
        -> DiagnosticsTone.Neutral

        DnsIntegrityVerdict.DNS_DISAGREEMENT,
        DnsIntegrityVerdict.UDP_UNAVAILABLE,
        DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE,
        DnsIntegrityVerdict.DOH_BLOCKED,
        DnsIntegrityVerdict.DNS_SUBSTITUTION,
        DnsIntegrityVerdict.DNS_INTERCEPTION,
        DnsIntegrityVerdict.FAKE_NXDOMAIN,
        DnsIntegrityVerdict.FAKE_IP,
        DnsIntegrityVerdict.DNS_FAILURE_AGREEMENT,
        -> DiagnosticsTone.Warning

        DnsIntegrityVerdict.UNKNOWN -> DiagnosticsTone.Neutral
    }

internal fun DoqVerdict.tone(): DiagnosticsTone =
    when (this) {
        DoqVerdict.DOQ_OK -> DiagnosticsTone.Positive

        DoqVerdict.DOQ_TIMEOUT,
        DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE,
        -> DiagnosticsTone.Neutral

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
